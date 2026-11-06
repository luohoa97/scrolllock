package com.neilluo.scrolllock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.R
import com.neilluo.scrolllock.data.ClosedReason
import com.neilluo.scrolllock.data.WaitingSummary
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import com.neilluo.scrolllock.data.AgentSummary
import kotlinx.coroutines.launch

/**
 * Covers the blocked apps (see [BlockedApps]) whenever one is visible and the gate is closed. Window-change events from the
 * system wake the service; each wake lists the windows on screen and finds a blocked app's. Which
 * screen of the app it is does not matter: a visible window of a blocked app gets the overlay, sized to
 * that window only (so split screen leaves the other app alone).
 *
 * Nothing runs while Instagram is off screen. While it is on screen and locked, a [HEARTBEAT_MS]
 * check re-posts the overlay and the mute if anything removed them, so one missed event can't
 * leave Instagram open.
 */
class BlockerService : AccessibilityService(), OverlayActions {

    // An exception in a coroutine here would otherwise crash the service and leave Instagram open.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("ScrollLock", "coroutine failed", e) },
    )
    private lateinit var overlay: OverlayController
    private lateinit var notifier: StatusNotifier
    private lateinit var island: StatusIsland
    private lateinit var engine: DetectionEngine
    private var cfg = Settings()
    private var rules = com.neilluo.scrolllock.detect.Rules()
    private var guardPending = false

    private var igVisible = false // some blocked app is on screen
    private var earlyUntil = 0L // elapsed-realtime until which a window-event cover is kept without a readable window
    private var blockedPkg = ""
    private var bounds = Box(0, 0, 0, 0)
    private var frontJobs: Job? = null
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var screenReceiver: BroadcastReceiver? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var weMuted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Window lists need this flag. Setting it here as well as in the XML covers an updated install
        // whose service config the system hasn't re-read.
        serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        overlay = OverlayController(this, this)
        notifier = StatusNotifier(this)
        island = StatusIsland(this)
        rules = RulesLoader.load(this)
        engine = DetectionEngine(rules)
        scope.launch { AppGraph.settings.flow.collect { s -> cfg = s } }
        scope.launch { keepNotificationCurrent() }
        runCatching {
            dismissReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { notifier.repost() }
            }.also {
                androidx.core.content.ContextCompat.registerReceiver(this, it, IntentFilter(StatusNotifier.ACTION_DISMISSED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
        scope.launch { AppGraph.fcm.ensureRegistered() }
        log("service connected, build 0.1.16")
        // The loop starts first: nothing below it may stop the lock from running.
        scope.launch {
            var settle = 0
            while (true) {
                runCatching { pollOnce() }.onFailure { android.util.Log.e("ScrollLock", "poll failed", it) }
                // Event-driven when a blocked app is off screen. Heartbeat only while one is on screen.
                // Right after an event, look again a few times as things settle: an app that is still
                // launching can have a window with no readable content yet, and no further event may
                // come until the user touches it.
                val wait: Long? = when {
                    igVisible -> HEARTBEAT_MS
                    settle < SETTLE_MS.size -> SETTLE_MS[settle++]
                    else -> null
                }
                val nudged = if (wait != null) withTimeoutOrNull(wait) { nudges.receive() } else nudges.receive()
                if (nudged != null) settle = 0
            }
        }
        runCatching { restoreStaleMute() }
        // Wake on screen-on, since no window event may fire when the phone wakes into a blocked app.
        // Android 14+ refuses a receiver registered without an exported flag.
        runCatching {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    nudges.trySend(Unit)
                    // One look at the relay when the screen comes on, so the notification is right when you pick the phone up.
                    if (i?.action == Intent.ACTION_USER_PRESENT) scope.launch { AppGraph.state.refresh() }
                }
            }.also {
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    it,
                    IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }.onFailure { android.util.Log.e("ScrollLock", "screen receiver failed", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // A blocked app's window just appeared. The event already names its package, so cover it now instead of
        // waiting for a poll to read the window's content, which on a cold launch can take around a second.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg in BlockedApps.packages) coverNow(pkg)
        }
        nudges.trySend(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && cfg.lockdown && event.packageName?.toString() in rules.guardPackages) scheduleGuard()
    }

    /**
     * Fail closed on launch: put the cover up from the event alone, full screen and with no fade, using the last
     * known state (no answer yet counts as locked). The next poll fixes the bounds, or removes it if the app
     * turns out not to be on screen.
     */
    private fun coverNow(pkg: String) {
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) return
        val was = igVisible
        igVisible = true
        earlyUntil = android.os.SystemClock.elapsedRealtime() + EARLY_COVER_MS
        blockedPkg = pkg
        if (!was) {
            val screen = windowManager().currentWindowMetrics.bounds
            bounds = Box(0, 0, screen.width(), screen.height())
            onInstagramFront()
            reassertBlur()
        }
        apply()
    }

    /** One look at the screen. Decides whether Instagram is visible, then whether to cover it. */
    private fun pollOnce() {
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) {
            // Screen off: nothing is visible, nothing to cover. Keep Instagram's state so waking
            // straight back into it is covered on the first poll.
            hideOverlay(); island.hide(); return
        }
        val found = blockedWindow()
        val was = igVisible
        // A cover raised from a window event stands for a moment even if the window's content can't be read yet.
        igVisible = found != null || android.os.SystemClock.elapsedRealtime() < earlyUntil
        if (found != null) bounds = found
        if (igVisible && !was) { onInstagramFront(); reassertBlur() }
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
                if (pkg == null || pkg !in BlockedApps.packages) continue
                w.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) continue
                if (best == null || r.width() * r.height() > best.width() * best.height()) { best = Rect(r); bestPkg = pkg }
            } finally {
                @Suppress("DEPRECATION") root.recycle()
            }
        }
        if (best == null) {
            // No blocked window had readable content (still launching, or no window list): try the active window.
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val pkg = root.packageName?.toString()
                    if (pkg != null && pkg in BlockedApps.packages) {
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
            // Asks the relay directly when it needs to, only while a blocked app is on screen:
            //  - locked and no Firebase (so no pushes): every few seconds, so an unlock lands at once;
            //  - unlocked and the lease is about to run out: leases can be a few seconds, and a push
            //    only says "working" once, so ask again before it ends;
            //  - nothing heard for two minutes: a safety refresh.
            launch {
                val pushless = !AppGraph.fcm.firebaseAvailable()
                var lastTry = 0L
                while (true) {
                    delay(1_000)
                    val now = android.os.SystemClock.elapsedRealtime()
                    val age = AppGraph.state.ageMs()
                    val g = AppGraph.state.gateNow(cfg)
                    // While unlocked, ask every couple of seconds: nothing pushes "Claude stopped", and waiting until
                    // the lease is nearly out let Instagram run on for most of it after the agent had finished.
                    val due = age == null || age > STALE_PUSH_MS ||
                        (g is Gate.Closed && pushless) ||
                        g is Gate.Open
                    if (due && now - lastTry >= MIN_REFRESH_GAP_MS) {
                        lastTry = now
                        AppGraph.state.refresh()
                    }
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
            if (root.packageName?.toString() !in BlockedApps.packages) return
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

    /**
     * A blocked app that was swiped away and is launched again can come up with the overlay's blur missing,
     * leaving only the tint. Re-adding the window a moment after launch, once the app has drawn, restores it.
     */
    private var blurJob: Job? = null
    private fun reassertBlur() {
        blurJob?.cancel()
        blurJob = scope.launch {
            for (wait in longArrayOf(250, 600, 1200)) {
                delay(wait)
                if (!igVisible) return@launch
                overlay.rebuild()
            }
        }
    }

    /** One place decides whether the overlay is up: Instagram visible and the gate closed. */
    private fun apply() {
        if (!igVisible) { hideOverlay(); announce(AppGraph.state.gateNow(cfg)); return }
        val gate = AppGraph.state.gateNow(cfg)
        announce(gate)
        if (gate !is Gate.Closed) { hideOverlay(); return }
        showOverlay(bounds, model(gate))
    }

    /**
     * Says where things stand. The notification is always there (shade and lock screen) and lists the agents
     * working; the island is the same thing in the status bar, only while a blocked app is open. No countdown.
     */
    private fun announce(g: Gate) {
        val app = if (igVisible) BlockedApps.packages[blockedPkg] ?: "App" else null
        val agents = if (g is Gate.Open) AppGraph.state.agentsNow() else emptyList()
        val summary = AgentSummary.short(agents)
        val waiters = if (g is Gate.Closed && g.reason == ClosedReason.NeedsYou) AppGraph.state.waitersNow(cfg.showTaskText) else emptyList()
        val (title, text) = when {
            g is Gate.Closed && g.reason == ClosedReason.NeedsYou ->
                (app?.let { "$it locked" } ?: "Apps locked") to ("Waiting on you" + WaitingSummary.names(waiters).takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty())
            g is Gate.Open -> (app?.let { "In $it" } ?: "Apps unlocked") to summary.ifEmpty { "Claude is working" }
            g is Gate.Bypass -> (app?.let { "In $it" } ?: "Bypass is on") to "Bypass is on"
            g is Gate.Closed && g.reason == ClosedReason.Checking -> (app?.let { "In $it" } ?: "ScrollLock") to "Checking Claude…"
            else -> (app?.let { "$it locked" } ?: "Apps locked") to "Claude isn't working"
        }
        val big = if (g is Gate.Open) AgentSummary.detailLines(agents, cfg.showTaskText).joinToString("\n") else ""
        val chip = when {
            g is Gate.Open -> AgentSummary.island(agents)
            g is Gate.Closed && g.reason == ClosedReason.NeedsYou -> "Waiting"
            else -> null
        }
        val publicText = if (g is Gate.Closed && g.reason == ClosedReason.NeedsYou) WaitingSummary.count(waiters.size) else text
        notifier.show(title, text, big, publicText = publicText, chip = chip)
        when {
            !cfg.showCapsule -> island.hide()
            app == null -> island.hide()
            g is Gate.Closed && g.reason == ClosedReason.NeedsYou -> island.show(app, "Waiting", StatusIsland.Tone.Waiting)
            g is Gate.Open -> island.show(app, AgentSummary.island(agents), StatusIsland.Tone.Working)
            g is Gate.Bypass -> island.show(app, "Bypass", StatusIsland.Tone.Waiting)
            g is Gate.Closed && g.reason == ClosedReason.Checking -> island.show(app, "Checking", StatusIsland.Tone.Waiting)
            else -> island.hide() // the lock screen says it all
        }
    }

    /**
     * Keeps the notification true while no blocked app is open, without ticking: it republishes when new
     * information arrives (a push, a fetch, a setting) and once more when an unlock is due to end.
     */
    private suspend fun keepNotificationCurrent() {
        combine(AppGraph.settings.flow, AppGraph.state.snapshots, AppGraph.state.failed, AppGraph.state.localWaitingFlow) { _, _, _, _ -> }.collectLatest {
            val g = AppGraph.state.gateNow(cfg)
            // A waiting item changes the gate itself, so a blocked app on screen may need its overlay raised or lifted.
            if (igVisible) apply() else announce(g)
            val left = when (g) { is Gate.Open -> g.remainingMs; is Gate.Bypass -> g.remainingMs; else -> 0L }
            if (left > 0) {
                delay(left + 200)
                if (igVisible) apply() else announce(AppGraph.state.gateNow(cfg))
            }
        }
    }

    private fun model(g: Gate.Closed) = OverlayModel(
        title = getString(R.string.overlay_title, BlockedApps.packages[blockedPkg] ?: "App"),
        subtitle = getString(
            when (g.reason) {
                ClosedReason.RelayUnreachable -> R.string.overlay_relay_down
                ClosedReason.NoSetup -> R.string.overlay_no_setup
                ClosedReason.Checking -> R.string.overlay_checking
                ClosedReason.NeedsYou -> R.string.overlay_needs_you
                else -> R.string.overlay_claude_idle
            },
        ),
        // Waiting on you: who is waiting. Otherwise how long it's been locked.
        elapsed = if (g.reason == ClosedReason.NeedsYou) WaitingSummary.names(AppGraph.state.waitersNow(cfg.showTaskText))
        else g.elapsedMs?.let { TimeFormat.elapsed(it) }.orEmpty(),
        bypassAvailable = g.reason != ClosedReason.NeedsYou && AppGraph.state.canBypassNow(cfg),
    )

    private var shownAt: Long? = null

    private var focusRequest: android.media.AudioFocusRequest? = null

    /** Shows or updates the overlay at once. Blurred if the system allows, a near-opaque scrim if not. */
    private fun showOverlay(b: Box, m: OverlayModel) {
        if (shownAt == null) {
            shownAt = android.os.SystemClock.elapsedRealtime()
            takeAudioFocus()
            log("overlay up (blur=${getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled})")
        }
        overlay.show(b, m)
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

    /** v8 muted the whole media stream while locked. If an install from then died muted, undo it. */
    private fun restoreStaleMute() {
        val prefs = getSharedPreferences("mute", MODE_PRIVATE)
        if (prefs.getBoolean("byUs", false)) { weMuted = true; unmuteMedia() }
    }

    /**
     * The blur hides a Reel but does not stop its sound. Taking audio focus asks the app's player to
     * pause. It is a request, not enforcement: an app that ignores focus loss keeps playing. A hard
     * per-app mute needs a shell-level grant (Shizuku or on-device wireless ADB) that this app lacks.
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
        overlay.release()
        island.hide()
        notifier.clear()
        unmuteMedia()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        dismissReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        const val HEARTBEAT_MS = 1_000L
        val SETTLE_MS = longArrayOf(100, 250, 500, 1_000, 2_000, 4_000)
        const val CAPTURE_TIMEOUT_MS = 700L
        const val GUARD_DELAY_MS = 350L
        const val REFRESH_AHEAD_MS = 5_000L
        const val EARLY_COVER_MS = 1_500L
        const val MIN_REFRESH_GAP_MS = 1_800L
        const val STALE_PUSH_MS = 120_000L
    }
}
