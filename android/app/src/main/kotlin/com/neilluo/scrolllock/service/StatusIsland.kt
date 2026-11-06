package com.neilluo.scrolllock.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small black pill in the top unsafe area, wrapped around the camera cutout like a dynamic island,
 * that says which blocked app you're in and that Claude is working. It's drawn by us rather than by
 * the system's notification UI, so it sits in the status bar instead of floating over the app.
 * Not touchable, so it can never get in the way or become a way around the lock. No countdown:
 * you already know the app locks when Claude stops.
 */
class StatusIsland(private val service: AccessibilityService) {
    enum class Tone(val color: Int) { Working(0xFF34C759.toInt()), Waiting(0xFFFFB020.toInt()) }

    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var dotBg: GradientDrawable? = null
    private var shownKey = ""

    fun show(app: String, status: String, tone: Tone) {
        val key = "$app|$status|${tone.name}"
        if (root != null && key == shownKey) return
        shownKey = key
        val v = root
        if (v != null) {
            statusView?.text = status
            dotBg?.setColor(tone.color)
            return
        }
        build(app, status, tone)
    }

    fun hide() {
        val view = root ?: return
        root = null; statusView = null; dotBg = null; shownKey = ""
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(120).withEndAction {
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
        }.start()
    }

    private fun build(app: String, status: String, tone: Tone) {
        val metrics = wm.currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()
        val insets = metrics.windowInsets
        val statusBar = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        // A top, roughly centred camera cutout: the pill wraps it. Anything else: a plain pill under the top edge.
        val cut = insets.displayCutout?.boundingRects
            ?.firstOrNull { it.top < screenH / 6 && kotlin.math.abs(it.centerX() - screenW / 2) < screenW / 12 }

        val fg = Color.WHITE
        val dot = View(service).also {
            dotBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tone.color) }
            it.background = dotBg
        }
        val st = label(status, fg).also { statusView = it }

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 1000f }
            setPadding(dp(12), 0, dp(12), 0)
            // Dot on the left, the camera hole in the middle, one short word on the right. Nothing wider than that,
            // so it never reaches the status icons.
            addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
            if (cut != null) {
                addView(View(service), LinearLayout.LayoutParams(cut.width() + dp(10), 1))
            } else {
                addView(View(service), LinearLayout.LayoutParams(dp(6), 1))
            }
            addView(st)
        }

        val topY: Int
        val h: Int
        if (cut != null) {
            topY = (cut.top - dp(3)).coerceAtLeast(0)
            h = (cut.bottom + dp(3) - topY).coerceAtLeast(dp(24))
        } else {
            topY = dp(3)
            h = ((if (statusBar > 0) statusBar else dp(24)) - dp(6)).coerceAtLeast(dp(20))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topY
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "ScrollLock island"
        }
        row.alpha = 0f
        try { wm.addView(row, lp) } catch (_: Exception) { return }
        root = row
        row.animate().alpha(1f).setDuration(140).start()
    }

    private fun label(s: String, color: Int) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
}
