package com.neilluo.scrolllock.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
 * behind it. It is not focusable, so the system Back key still reaches Instagram. Plain views, not
 * Compose, because an overlay window has no lifecycle owner to hang a composition on.
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
    private var snapshot: Bitmap? = null
    private var snapshotView: ImageView? = null

    val isShowing: Boolean get() = root != null

    private val night: Boolean
        get() = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * [shot] is a screenshot of the display taken just before the overlay goes up. It is blurred by
     * the overlay's own view, which works whether or not the system allows window blur. Without a
     * shot the overlay falls back to the system's window blur, then to an opaque scrim.
     */
    fun show(bounds: Box, model: OverlayModel, shot: Bitmap? = null) {
        if (shot != null) snapshot = shot
        val blur = wm.isCrossWindowBlurEnabled && snapshot == null
        if (root != null) {
            // Blur support can flip while we're up (battery saver, a developer option, thermal limits).
            // The window flags and scrim are fixed when the window is made, so rebuild if it changed.
            if (blur != builtWithBlur) removeNow()
            else {
                update(bounds, model)
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
            if (blur) blurBehindRadius = BLUR_RADIUS
            title = "ScrollLock"
        }
        val view = build(model, blur, bounds)
        view.alpha = 0f
        try {
            wm.addView(view, lp)
        } catch (_: Exception) {
            return // service is going away or the window type was refused; fail without crashing
        }
        root = view
        params = lp
        builtWithBlur = blur
        view.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun update(bounds: Box, model: OverlayModel) {
        bind(model)
        val lp = params ?: return
        val view = root ?: return
        val w = bounds.width.coerceAtLeast(1)
        val h = bounds.height.coerceAtLeast(1)
        if (lp.width != w || lp.height != h || lp.x != bounds.left || lp.y != bounds.top) {
            lp.width = w; lp.height = h; lp.x = bounds.left; lp.y = bounds.top
            placeSnapshot(bounds)
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
    }

    private fun removeNow() {
        val view = root ?: return
        root = null; params = null
        titleView = null; subtitleView = null; elapsedView = null; bypassView = null; snapshotView = null
        view.animate().cancel()
        try { wm.removeViewImmediate(view) } catch (_: Exception) {}
    }

    fun hide() {
        val view = root ?: return
        root = null
        params = null
        titleView = null; subtitleView = null; elapsedView = null; bypassView = null; snapshotView = null
        snapshot = null
        view.animate().cancel()
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

    /** Shifts the full-display screenshot so the part under this window lines up with it. */
    private fun placeSnapshot(bounds: Box) {
        val iv = snapshotView ?: return
        iv.imageMatrix = Matrix().apply { setTranslate(-bounds.left.toFloat(), -bounds.top.toFloat()) }
    }

    private fun build(model: OverlayModel, blur: Boolean, bounds: Box): FrameLayout {
        val fg = if (night) Color.WHITE else Color.parseColor("#111111")
        val muted = if (night) Color.parseColor("#B3FFFFFF") else Color.parseColor("#99000000")
        val shot = snapshot
        val soft = blur || shot != null // content behind is already blurred, so the scrim can be light
        val scrim = when {
            soft && night -> Color.parseColor("#990B0B0B")
            soft -> Color.parseColor("#99FFFFFF")
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
            isClickable = true // swallow touches so nothing reaches the Reel underneath
            if (shot != null) {
                // The blur is done by this view, not by the window manager, so it can't be switched
                // off by battery saver or a vendor power mode.
                val iv = ImageView(service).apply {
                    scaleType = ImageView.ScaleType.MATRIX
                    setImageBitmap(shot)
                    setRenderEffect(RenderEffect.createBlurEffect(SNAPSHOT_BLUR, SNAPSHOT_BLUR, Shader.TileMode.CLAMP))
                }
                snapshotView = iv
                placeSnapshot(bounds)
                addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
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
        const val SNAPSHOT_BLUR = 48f
    }
}
