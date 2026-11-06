package com.neilluo.scrolllock.surface

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.data.Gate
import java.text.DateFormat
import java.util.Date

/**
 * Home-screen widget. It shows "open until 14:32" using a clock time, so a widget that has not been
 * redrawn for a while still says something true instead of a stale countdown.
 */
class StatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AppGraph.init(context)
        val gate = AppGraph.state.gateNow(AppGraph.settings.current())
        val untilMs = (gate as? Gate.Open)?.remainingMs ?: (gate as? Gate.Bypass)?.remainingMs
        val line = when {
            untilMs != null -> "until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + untilMs))
            else -> "locked"
        }
        provideContent { Content(if (gate.isOpen) "Instagram open" else "Instagram", line) }
    }

    @Composable
    private fun Content(title: String, line: String) {
        GlanceTheme {
            Column(
                GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                Text(line, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
            }
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}
