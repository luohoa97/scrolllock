package com.neilluo.scrolllock.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.neilluo.scrolllock.R
import com.neilluo.scrolllock.detect.Box

data class OverlayModel(
    val title: String,
    val subtitle: String,
    /** Empty hides the line. */
    val elapsed: String,
    val bypassAvailable: Boolean,
)

interface OverlayActions {
    fun backToClaude()
    fun bypass()
}

/**
 * The block screen: an accessibility-overlay window sized to the Instagram window, blurring what is
 * behind it. It is not focusable, so the system Back key still reaches Instagram, but it is touchable:
 * it takes every tap (so nothing reaches the Reel underneath) and its own buttons work. Plain views, not
 * Compose, because an overlay window has no lifecycle owner to hang a composition on.
 *
 * It can also be raised *held*: in the window, taking touches, but fully transparent and unblurred. That is
 * for the moment between a blocked app opening and the phone knowing whether Claude is still working, so an
 * answer of "unlocked" removes a window nobody ever saw instead of flashing a lock screen.
 */
class OverlayController(
    private val service: AccessibilityService,
    private val actions: OverlayActions,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var elapsedView: TextView? = null
    private var bypassView: TextView? = null
    private var builtWithBlur = false
    private var held = false
    private var lastBounds: Box? = null
    private var lastModel: OverlayModel? = null

    /** The system can turn blur on or off while we're up. If it flips, rebuild so the window matches. */
    private val blurListener = java.util.function.Consumer<Boolean> {
        val b = lastBounds; val m = lastModel
        if (root != null && b != null && m != null) show(b, m)
    }.also { l ->
        runCatching { wm.addCrossWindowBlurEnabledListener(service.mainExecutor, l) }
    }

    fun release() {
        runCatching { wm.removeCrossWindowBlurEnabledListener(blurListener) }
    }

    /**
     * Makes the compositor re-sample what is behind the window, without taking the window down (re-adding it
     * flashes the app for a frame). The blur is only computed against whatever was drawn when the window was
     * added, so an app that was still launching, or restoring from recents, can leave it blurring nothing.
     * Changing the radius forces a fresh sample; the second step goes a frame later so the change registers.
     */
    fun refreshBlur() {
        val view = root ?: return
        val lp = params ?: return
        if (!builtWithBlur) return
        lp.blurBehindRadius = BLUR_RADIUS - 1
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) { return }
        view.post {
            if (root !== view) return@post
            lp.blurBehindRadius = BLUR_RADIUS
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
    }

    val isShowing: Boolean get() = root != null

    /** Up and taking touches, but not drawn yet. */
    val isHeld: Boolean get() = root != null && held

    private val night: Boolean
        get() = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Live blur from the compositor (FLAG_BLUR_BEHIND) when the system allows it. When it doesn't
     * (battery saver, a vendor power mode, the "disable window blurs" developer option, tunneled
     * video) the overlay is a near-opaque scrim instead, so the app is never left showing.
     */
    fun show(bounds: Box, model: OverlayModel, animate: Boolean = false, hold: Boolean = false) {
        lastBounds = bounds; lastModel = model
        val blur = wm.isCrossWindowBlurEnabled
        if (root != null) {
            // Blur support can flip while we're up (battery saver, a developer option, thermal limits).
            // The window flags and scrim are fixed when the window is made, so rebuild if it changed.
            if (blur != builtWithBlur) removeNow()
            else {
                update(bounds, model)
                if (held && !hold) reveal()
                return
            }
        }
        val lp = WindowManager.LayoutParams(
            bounds.width.coerceAtLeast(1),
            bounds.height.coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (blur) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            if (blur && !hold) blurBehindRadius = BLUR_RADIUS
            title = "ScrollLock"
        }
        val view = build(model, blur)
        view.alpha = if (animate || hold) 0f else 1f
        try {
            wm.addView(view, lp)
        } catch (_: Exception) {
            return // service is going away or the window type was refused; fail without crashing
        }
        root = view
        params = lp
        builtWithBlur = blur
        held = hold
        android.util.Log.i("ScrollLock", "overlay window added blur=$blur bounds=${bounds.width}x${bounds.height} animate=$animate hold=$hold")
        if (hold) return
        if (animate) view.animate().alpha(1f).setDuration(FADE_MS).start()
        if (blur) for (wait in BLUR_REFRESH_MS) view.postDelayed({ if (root === view) refreshBlur() }, wait)
    }

    /** Draws a held window, at once: the answer was "locked", and a lock that eases in is a lock that leaks. */
    private fun reveal() {
        val view = root ?: return
        val lp = params ?: return
        held = false
        android.util.Log.i("ScrollLock", "overlay revealed")
        view.alpha = 1f
        if (builtWithBlur) {
            lp.blurBehindRadius = BLUR_RADIUS
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            for (wait in BLUR_REFRESH_MS) view.postDelayed({ if (root === view) refreshBlur() }, wait)
        }
    }

    private fun update(bounds: Box, model: OverlayModel) {
        bind(model)
        val lp = params ?: return
        val view = root ?: return
        val w = bounds.width.coerceAtLeast(1)
        val h = bounds.height.coerceAtLeast(1)
        if (lp.width != w || lp.height != h || lp.x != bounds.left || lp.y != bounds.top) {
            lp.width = w; lp.height = h; lp.x = bounds.left; lp.y = bounds.top
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
    }

    private fun removeNow() {
        val view = root ?: return
        root = null; params = null; held = false
        titleView = null; subtitleView = null; elapsedView = null; bypassView = null
        view.animate().cancel()
        android.util.Log.i("ScrollLock", "overlay window removed (rebuild for blur change)")
        try { wm.removeViewImmediate(view) } catch (_: Exception) {}
    }

    fun hide() {
        lastBounds = null; lastModel = null
        val view = root ?: return
        root = null
        params = null
        held = false
        titleView = null; subtitleView = null; elapsedView = null; bypassView = null
        view.animate().cancel()
        android.util.Log.i("ScrollLock", "overlay window hiding")
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
        }.start()
    }

    private fun bind(model: OverlayModel) {
        titleView?.text = model.title
        subtitleView?.text = model.subtitle
        elapsedView?.apply {
            text = model.elapsed
            visibility = if (model.elapsed.isEmpty()) View.GONE else View.VISIBLE
        }
        bypassView?.visibility = if (model.bypassAvailable) View.VISIBLE else View.GONE
    }

    private fun build(model: OverlayModel, blur: Boolean): FrameLayout {
        val fg = if (night) Color.WHITE else Color.parseColor("#111111")
        val muted = if (night) Color.parseColor("#B3FFFFFF") else Color.parseColor("#99000000")
        val scrim = when {
            blur && night -> Color.parseColor("#CC0B0B0B")
            blur -> Color.parseColor("#CCFFFFFF")
            night -> Color.parseColor("#F20B0B0B")
            else -> Color.parseColor("#F2FFFFFF")
        }

        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        titleView = text(model.title, 24f, fg, bold = true).also { column.addView(it) }
        subtitleView = text(model.subtitle, 16f, muted).also {
            it.setPadding(0, dp(6), 0, 0)
            column.addView(it)
        }
        elapsedView = text(model.elapsed, 14f, muted).also {
            it.setPadding(0, dp(4), 0, 0)
            it.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            it.fontFeatureSettings = "tnum"
            it.visibility = if (model.elapsed.isEmpty()) View.GONE else View.VISIBLE
            column.addView(it)
        }

        val claude = pill(service.getString(R.string.overlay_back_to_claude), fg, if (night) Color.BLACK else Color.WHITE) {
            actions.backToClaude()
        }
        column.addView(claude, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(28) })

        // Ways out: wait here, go to Claude, or (only if armed while Claude was working) the bypass.
        val bypass = quiet(service.getString(R.string.overlay_bypass), fg) { actions.bypass() }
        bypassView = bypass
        bypass.visibility = if (model.bypassAvailable) View.VISIBLE else View.GONE
        column.addView(bypass, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(8) })

        return FrameLayout(service).apply {
            setBackgroundColor(scrim)
            // Takes every tap, so nothing reaches the Reel underneath, and lets the buttons inside it work.
            isClickable = true
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun quiet(label: String, color: Int, onClick: () -> Unit) = TextView(service).apply {
        text = label
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(service).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(24), 0, dp(24), 0)
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(24).toFloat() }
        isClickable = true
        setOnClickListener { onClick() }
        contentDescription = label
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val FADE_MS = 140L
        const val BLUR_RADIUS = 40
        val BLUR_REFRESH_MS = longArrayOf(120, 400, 1_000)
    }
}
