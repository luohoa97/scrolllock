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
    private var rules = com.neilluo.scrolllock.detect.Rules()
    private var guardPending = false

    private var verdict = Verdict.Undecided
    private var bounds = Box(0, 0, 0, 0)
    private var bypassOk = false
    private var evalPending = false
    private var frontJobs: Job? = null
    private var frontSince = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this, this)
        rules = RulesLoader.load(this)
        engine = DetectionEngine(rules)
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
                if (cfg.lockdown && pkg in rules.guardPackages) scheduleGuard()
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
        frontSince = android.os.SystemClock.elapsedRealtime()
        frontJobs?.cancel()
        frontJobs = scope.launch {
            launch { AppGraph.state.refresh() }
            launch { bypassOk = AppGraph.state.canBypass() }
            // The gate only needs a clock tick while Instagram is on screen.
            launch {
                AppGraph.state.gate.collect {
                    apply()
                    // Don't rely on accessibility events alone: a returning app, a paused video or a
                    // null root can mean none arrive. While locked, look again every tick.
                    if (it is Gate.Closed) scheduleEvaluate()
                }
            }
            // Safety net: if no push has arrived for two minutes, ask the relay directly. Without
            // Firebase there are no pushes at all, so while Instagram is up and locked, ask every
            // few seconds so an unlock lands without pressing refresh.
            launch {
                val pushless = !AppGraph.fcm.firebaseAvailable()
                while (true) {
                    delay(if (pushless) PUSHLESS_POLL_MS else SAFETY_FETCH_MS)
                    val age = AppGraph.state.ageMs()
                    val locked = AppGraph.state.gateNow(cfg) is Gate.Closed
                    if (age == null || age > STALE_PUSH_MS || (pushless && locked)) AppGraph.state.refresh()
                }
            }
        }
        scheduleEvaluate()
    }

    private fun onInstagramGone() {
        frontJobs?.cancel()
        frontJobs = null
        verdict = Verdict.Undecided
        hideOverlay()
    }

    /** Lockdown: if this Settings screen could remove ScrollLock, send the user home. */
    private fun scheduleGuard() {
        if (guardPending) return
        guardPending = true
        scope.launch {
            delay(GUARD_DELAY_MS)
            guardPending = false
            val root = rootInActiveWindow ?: return@launch
            try {
                if (root.packageName?.toString() !in rules.guardPackages) return@launch
                val texts = TextReader.labels(root)
                if (SettingsGuard.shouldBlock(texts, getString(R.string.app_name), rules.guardKeywords)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } finally {
                @Suppress("DEPRECATION") root.recycle()
            }
        }
    }

    private fun scheduleEvaluate() {
        if (AppGraph.captureAt != 0L) {
            scope.launch {
                val wait = AppGraph.captureAt - android.os.SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait + 50)
                if (AppGraph.captureAt != 0L && tracker.targetInFront) evaluate()
            }
        }
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
            val surface = engine.detect(tree)
            verdict = policy.feed(surface)
            maybeCapture(tree, surface)
            val r = android.graphics.Rect()
            root.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) bounds = Box(r.left, r.top, r.right, r.bottom)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
        apply()
    }

    /** Debug: after "Capture in 5s" in Settings, copy a redacted dump of the next Instagram screen. */
    private fun maybeCapture(tree: com.neilluo.scrolllock.detect.TreeSnapshot, surface: Surface) {
        val at = AppGraph.captureAt
        if (at == 0L || android.os.SystemClock.elapsedRealtime() < at) return
        AppGraph.captureAt = 0L
        val text = com.neilluo.scrolllock.detect.TreeDump.toJson(tree, surface, engine.explain(tree))
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("ScrollLock screen dump", text))
        android.widget.Toast.makeText(this, "Screen dump copied (${tree.nodes.size} nodes, $surface)", android.widget.Toast.LENGTH_LONG).show()
    }

    /** One place decides whether the overlay is up. Undecided leaves things as they are. */
    private fun apply() {
        if (!tracker.targetInFront) { hideOverlay(); return }
        val gate = AppGraph.state.gateNow(cfg)
        if (gate !is Gate.Closed) { hideOverlay(); return }
        when (verdict) {
            Verdict.Allow -> hideOverlay()
            Verdict.Block -> showOverlay(bounds.takeIf { it.area > 0 } ?: fullScreen(), model(gate))
            // Nothing decisive yet. Leave an existing overlay alone, but don't stay open-ended: if we
            // still can't tell where we are after a moment, fail closed.
            Verdict.Undecided -> if (android.os.SystemClock.elapsedRealtime() - frontSince > UNDECIDED_LIMIT_MS) {
                showOverlay(bounds.takeIf { it.area > 0 } ?: fullScreen(), model(gate))
            }
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

    private var shownAt: Long? = null

    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun showOverlay(b: Box, m: OverlayModel) {
        if (shownAt == null) {
            shownAt = android.os.SystemClock.elapsedRealtime()
            takeAudioFocus()
        }
        overlay.show(b, m)
    }

    /**
     * The blur hides a Reel but does not stop its sound. Taking audio focus asks Instagram's player
     * to pause. Unverified on a real phone: it relies on Instagram honouring the focus loss.
     */
    private fun takeAudioFocus() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun releaseAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(req)
    }

    /** Hides the overlay and books the time it was up as blocked time. */
    private fun hideOverlay() {
        shownAt?.let { start ->
            shownAt = null
            val blockedMs = android.os.SystemClock.elapsedRealtime() - start
            AppGraph.scope.launch { AppGraph.stats.addBlocked(blockedMs) }
            releaseAudioFocus()
        }
        overlay.hide()
    }

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
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        const val SYSTEM_UI = "com.android.systemui"
        const val DEBOUNCE_MS = 150L
        const val UNDECIDED_LIMIT_MS = 1_500L
        const val GUARD_DELAY_MS = 350L
        const val SAFETY_FETCH_MS = 30_000L
        const val PUSHLESS_POLL_MS = 3_000L
        const val STALE_PUSH_MS = 120_000L
    }
}
