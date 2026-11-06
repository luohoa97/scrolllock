package com.neilluo.scrolllock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.R
import com.neilluo.scrolllock.data.ClosedReason
import com.neilluo.scrolllock.data.Gate
import com.neilluo.scrolllock.data.Settings
import com.neilluo.scrolllock.data.TimeFormat
import com.neilluo.scrolllock.detect.Box
import com.neilluo.scrolllock.detect.DetectionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * Covers the blocked apps (see [BLOCKED]) whenever one is visible and the gate is closed. Window-change events from the
 * system wake the service; each wake lists the windows on screen and finds a blocked app's. Which
 * screen of the app it is does not matter: a visible window of a blocked app gets the overlay, sized to
 * that window only (so split screen leaves the other app alone).
 *
 * Nothing runs while Instagram is off screen. While it is on screen and locked, a [HEARTBEAT_MS]
 * check re-posts the overlay and the mute if anything removed them, so one missed event can't
 * leave Instagram open.
 */
class BlockerService : AccessibilityService(), OverlayActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayController
    private lateinit var engine: DetectionEngine
    private var cfg = Settings()
    private var rules = com.neilluo.scrolllock.detect.Rules()
    private var guardPending = false

    private var igVisible = false // some blocked app is on screen
    private var blockedPkg = ""
    private var bounds = Box(0, 0, 0, 0)
    private var frontJobs: Job? = null
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var screenReceiver: BroadcastReceiver? = null
    private var capturing = false
    private var weMuted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Window lists need this flag. Setting it here as well as in the XML covers an updated install
        // whose service config the system hasn't re-read.
        serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        overlay = OverlayController(this, this)
        rules = RulesLoader.load(this)
        engine = DetectionEngine(rules)
        scope.launch { AppGraph.settings.flow.collect { s -> cfg = s } }
        scope.launch { AppGraph.fcm.ensureRegistered() }
        restoreStaleMute()
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { nudges.trySend(Unit) }
        }.also {
            registerReceiver(it, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        scope.launch {
            while (true) {
                pollOnce()
                // Event-driven when Instagram is off screen. Heartbeat only while it is on screen.
                if (igVisible) withTimeoutOrNull(HEARTBEAT_MS) { nudges.receive() } else nudges.receive()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        nudges.trySend(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && cfg.lockdown && event.packageName?.toString() in rules.guardPackages) scheduleGuard()
    }

    /** One look at the screen. Decides whether Instagram is visible, then whether to cover it. */
    private fun pollOnce() {
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) {
            // Screen off: nothing is visible, nothing to cover. Keep Instagram's state so waking
            // straight back into it is covered on the first poll.
            hideOverlay(); return
        }
        val found = blockedWindow()
        val was = igVisible
        igVisible = found != null
        if (found != null) bounds = found
        if (igVisible && !was) onInstagramFront()
        if (!igVisible && was) onInstagramGone()
        apply()
        captureIfAsked()
    }

    /** The bounds of the largest visible window of a blocked app, or null if none is on screen. */
    private fun blockedWindow(): Box? {
        var bestPkg = ""
        var best: Rect? = null
        val r = Rect()
        val list = runCatching { windows }.getOrNull().orEmpty()
        for (w in list) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            try {
                val pkg = root.packageName?.toString()
                if (pkg == null || pkg !in BLOCKED) continue
                w.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) continue
                if (best == null || r.width() * r.height() > best.width() * best.height()) { best = Rect(r); bestPkg = pkg }
            } finally {
                @Suppress("DEPRECATION") root.recycle()
            }
        }
        if (best == null && list.isEmpty()) {
            // The system gave no window list (flag not applied yet): fall back to the active window.
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val pkg = root.packageName?.toString()
                    if (pkg != null && pkg in BLOCKED) {
                        val b = Rect(); root.getBoundsInScreen(b)
                        if (b.width() > 0 && b.height() > 0) { best = b; bestPkg = pkg }
                    }
                } finally {
                    @Suppress("DEPRECATION") root.recycle()
                }
            }
        }
        if (bestPkg.isNotEmpty()) blockedPkg = bestPkg
        return best?.let { Box(it.left, it.top, it.right, it.bottom) }
    }

    private fun onInstagramFront() {
        log("instagram visible")
        frontJobs?.cancel()
        frontJobs = scope.launch {
            launch { AppGraph.state.refresh() }
            // The gate only needs a clock tick while Instagram is on screen.
            launch { AppGraph.state.gate.collect { apply() } }
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
    }

    private fun onInstagramGone() {
        log("instagram not visible")
        frontJobs?.cancel()
        frontJobs = null
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

    /** Debug: after "Capture in 5s" in Settings, copy a redacted dump of the next Instagram screen. */
    private fun captureIfAsked() {
        val at = AppGraph.captureAt
        if (at == 0L || !igVisible || android.os.SystemClock.elapsedRealtime() < at) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() !in BLOCKED) return
            AppGraph.captureAt = 0L
            val screen = windowManager().currentWindowMetrics.bounds
            val tree = TreeReader.read(root, Box(0, 0, screen.width(), screen.height()))
            val surface = engine.detect(tree)
            val text = com.neilluo.scrolllock.detect.TreeDump.toJson(tree, surface, engine.explain(tree))
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("ScrollLock screen dump", text))
            android.widget.Toast.makeText(this, "Screen dump copied (${tree.nodes.size} nodes, $surface)", android.widget.Toast.LENGTH_LONG).show()
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    /** One place decides whether the overlay is up: Instagram visible and the gate closed. */
    private fun apply() {
        if (!igVisible) { hideOverlay(); return }
        val gate = AppGraph.state.gateNow(cfg)
        if (gate !is Gate.Closed) { hideOverlay(); return }
        showOverlay(bounds, model(gate))
        if (overlay.isShowing) muteMedia()
    }

    private fun model(g: Gate.Closed) = OverlayModel(
        title = getString(R.string.overlay_title, BLOCKED[blockedPkg] ?: "App"),
        subtitle = getString(
            when (g.reason) {
                ClosedReason.RelayUnreachable -> R.string.overlay_relay_down
                ClosedReason.NoSetup -> R.string.overlay_no_setup
                else -> R.string.overlay_claude_idle
            },
        ),
        elapsed = g.elapsedMs?.let { TimeFormat.elapsed(it) }.orEmpty(),
        bypassAvailable = AppGraph.state.canBypassNow(cfg),
    )

    private var shownAt: Long? = null

    private var focusRequest: android.media.AudioFocusRequest? = null

    /**
     * Shows or updates the overlay. A new overlay waits for a screenshot first (taken before the
     * overlay exists, so it holds Instagram and not the overlay), which the overlay blurs itself.
     * If the screenshot fails or is slow, the overlay goes up anyway with the system blur or an
     * opaque scrim. Instagram is never left uncovered for more than [CAPTURE_TIMEOUT_MS].
     */
    private fun showOverlay(b: Box, m: OverlayModel) {
        if (overlay.isShowing) { overlay.show(b, m); return }
        if (capturing) return
        capturing = true
        var done = false
        fun finish(shot: Bitmap?) {
            if (done) return
            done = true
            capturing = false
            val gate = AppGraph.state.gateNow(cfg)
            if (!igVisible || gate !is Gate.Closed) return
            if (shownAt == null) {
                shownAt = android.os.SystemClock.elapsedRealtime()
                takeAudioFocus()
                log("overlay up (snapshot=${shot != null})")
            }
            overlay.show(bounds, model(gate), shot)
            muteMedia()
        }
        val started = runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = runCatching {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    }.getOrNull()
                    runCatching { result.hardwareBuffer.close() }
                    finish(bmp)
                }
                override fun onFailure(errorCode: Int) { log("screenshot failed ($errorCode)"); finish(null) }
            })
        }.isSuccess
        if (!started) finish(null)
        else scope.launch { delay(CAPTURE_TIMEOUT_MS); finish(null) }
    }

    /**
     * Stops Instagram's sound. The overlay hides a Reel but not its audio, and a plain app can't
     * mute another app, so this mutes the phone's media stream while the lock is up and unmutes it
     * after. Anything else playing media is silenced too. Android keeps the previous volume.
     */
    private fun muteMedia() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val stream = android.media.AudioManager.STREAM_MUSIC
        if (am.isStreamMute(stream)) return // already silent: either us, or the user's own mute
        runCatching { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, 0) }
        weMuted = true
        getSharedPreferences("mute", MODE_PRIVATE).edit().putBoolean("byUs", true).apply()
    }

    private fun unmuteMedia() {
        if (!weMuted) return
        weMuted = false
        getSharedPreferences("mute", MODE_PRIVATE).edit().putBoolean("byUs", false).apply()
        runCatching {
            getSystemService(android.media.AudioManager::class.java)
                ?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    /** If the service died while it had the stream muted, undo that on the next start. */
    private fun restoreStaleMute() {
        val prefs = getSharedPreferences("mute", MODE_PRIVATE)
        if (prefs.getBoolean("byUs", false)) { weMuted = true; unmuteMedia() }
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
            unmuteMedia()
            log("overlay down (igVisible=$igVisible gate=${AppGraph.state.gateNow(cfg)::class.simpleName})")
        }
        overlay.hide()
    }

    private fun log(msg: String) { android.util.Log.i("ScrollLock", msg) }

    private fun windowManager() = getSystemService(android.view.WindowManager::class.java)

    // OverlayActions

    override fun backToClaude() {
        val launch = packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai"))
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun bypass() {
        scope.launch {
            if (AppGraph.state.startBypass()) apply()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hideOverlay()
        unmuteMedia()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /**
         * Apps that are blocked whole while Claude isn't working: every app with reels or short
         * video. Package -> name for the lock screen. Discord is left out on purpose (work tool).
         * Check these against the phone: adb shell pm list packages | grep -E 'instagram|youtube|musically|trill|snapchat|facebook'
         */
        val BLOCKED = mapOf(
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.snapchat.android" to "Snapchat",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook",
        )
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        const val HEARTBEAT_MS = 1_000L
        const val CAPTURE_TIMEOUT_MS = 700L
        const val GUARD_DELAY_MS = 350L
        const val SAFETY_FETCH_MS = 30_000L
        const val PUSHLESS_POLL_MS = 3_000L
        const val STALE_PUSH_MS = 120_000L
    }
}
