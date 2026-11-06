package com.neilluo.scrolllock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.R
import com.neilluo.scrolllock.data.ClosedReason
import com.neilluo.scrolllock.data.Gate
import com.neilluo.scrolllock.data.Settings
import com.neilluo.scrolllock.data.TimeFormat
import com.neilluo.scrolllock.detect.Box
import com.neilluo.scrolllock.detect.DetectionEngine
import com.neilluo.scrolllock.detect.ScreenPolicy
import com.neilluo.scrolllock.detect.Surface
import com.neilluo.scrolllock.detect.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches which app is in front and, while Instagram is, decides whether the screen is a blocked
 * surface. The node tree is read only when the foreground app is Instagram. Window events from
 * other apps are used for one thing: noticing that Instagram is no longer in front.
 */
class BlockerService : AccessibilityService(), OverlayActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayController
    private lateinit var engine: DetectionEngine
    private var policy = ScreenPolicy()
    private var cfg = Settings()
    private val tracker = ForegroundTracker(INSTAGRAM) { ignoredPackages() }

    private var verdict = Verdict.Undecided
    private var bounds = Box(0, 0, 0, 0)
    private var bypassOk = false
    private var evalPending = false
    private var frontJobs: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this, this)
        engine = DetectionEngine(RulesLoader.load(this))
        scope.launch {
            AppGraph.settings.flow.collect { s ->
                val blockChanged = s.blockReels != cfg.blockReels || s.blockExplore != cfg.blockExplore
                cfg = s
                if (blockChanged) {
                    policy = ScreenPolicy(blockedSurfaces(s))
                    verdict = Verdict.Undecided
                }
            }
        }
        scope.launch { AppGraph.fcm.ensureRegistered() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (tracker.onWindowState(pkg)) {
                    if (tracker.targetInFront) onInstagramFront() else onInstagramGone()
                } else if (tracker.targetInFront && pkg == INSTAGRAM) {
                    scheduleEvaluate()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (tracker.targetInFront && pkg == INSTAGRAM) scheduleEvaluate()
            }
        }
    }

    private fun onInstagramFront() {
        policy.reset()
        verdict = Verdict.Undecided
        frontJobs?.cancel()
        frontJobs = scope.launch {
            launch { AppGraph.state.refresh() }
            launch { bypassOk = AppGraph.state.canBypass() }
            // The gate only needs a clock tick while Instagram is on screen.
            launch { AppGraph.state.gate.collect { apply() } }
            // Safety net: if no push has arrived for two minutes, ask the relay directly.
            launch {
                while (true) {
                    delay(SAFETY_FETCH_MS)
                    val age = AppGraph.state.ageMs()
                    if (age == null || age > STALE_PUSH_MS) AppGraph.state.refresh()
                }
            }
        }
        scheduleEvaluate()
    }

    private fun onInstagramGone() {
        frontJobs?.cancel()
        frontJobs = null
        verdict = Verdict.Undecided
        overlay.hide()
    }

    private fun scheduleEvaluate() {
        if (evalPending) return
        evalPending = true
        scope.launch {
            delay(DEBOUNCE_MS)
            evalPending = false
            evaluate()
        }
    }

    private fun evaluate() {
        if (!tracker.targetInFront) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != INSTAGRAM) return
            val screen = windowManager().currentWindowMetrics.bounds
            val tree = TreeReader.read(root, Box(0, 0, screen.width(), screen.height()))
            verdict = policy.feed(engine.detect(tree))
            val r = android.graphics.Rect()
            root.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) bounds = Box(r.left, r.top, r.right, r.bottom)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
        apply()
    }

    /** One place decides whether the overlay is up. Undecided leaves things as they are. */
    private fun apply() {
        if (!tracker.targetInFront) { overlay.hide(); return }
        val gate = AppGraph.state.gateNow(cfg)
        if (gate !is Gate.Closed) { overlay.hide(); return }
        when (verdict) {
            Verdict.Allow -> overlay.hide()
            Verdict.Block -> overlay.show(bounds.takeIf { it.area > 0 } ?: fullScreen(), model(gate))
            Verdict.Undecided -> Unit
        }
    }

    private fun model(g: Gate.Closed) = OverlayModel(
        title = getString(R.string.overlay_title),
        subtitle = getString(
            when (g.reason) {
                ClosedReason.RelayUnreachable -> R.string.overlay_relay_down
                ClosedReason.NoSetup -> R.string.overlay_no_setup
                else -> R.string.overlay_claude_idle
            },
        ),
        elapsed = g.elapsedMs?.let { TimeFormat.elapsed(it) }.orEmpty(),
        bypassAvailable = bypassOk,
    )

    private fun blockedSurfaces(s: Settings): Set<Surface> = buildSet {
        if (s.blockReels) add(Surface.Reels)
        if (s.blockExplore) add(Surface.Explore)
    }

    private fun ignoredPackages(): Set<String> {
        val ime = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }.orEmpty()
        return (ime + packageName + SYSTEM_UI).toSet()
    }

    private fun windowManager() = getSystemService(android.view.WindowManager::class.java)

    private fun fullScreen(): Box {
        val b = windowManager().currentWindowMetrics.bounds
        return Box(0, 0, b.width(), b.height())
    }

    // OverlayActions

    override fun backToClaude() {
        val launch = packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai"))
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun bypass() {
        scope.launch {
            if (AppGraph.state.startBypass()) {
                bypassOk = false
                apply()
            }
        }
    }

    override fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        const val SYSTEM_UI = "com.android.systemui"
        const val DEBOUNCE_MS = 150L
        const val SAFETY_FETCH_MS = 30_000L
        const val STALE_PUSH_MS = 120_000L
    }
}
