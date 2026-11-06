package com.neilluo.scrolllock.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.neilluo.scrolllock.data.AgentStatus
import com.neilluo.scrolllock.data.AgentSummary
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.data.Gate
import com.neilluo.scrolllock.data.StatsSummary
import com.neilluo.scrolllock.data.TimeFormat
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val gate by AppGraph.state.gate.collectAsStateWithLifecycle()
    val stats by AppGraph.stats.summary.collectAsStateWithLifecycle(initialValue = null)
    // The relay state lives in memory, so a fresh process has none. Ask on open and whenever the
    // screen comes back, and keep it current only while it's on screen: refresh when the answer
    // is missing, locked (no push), or the unlock is about to run out. Nothing runs when it's closed.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val ctx = LocalContext.current
    var notificationsOn by remember { mutableStateOf(true) }
    var gaps by remember { mutableStateOf(emptyList<com.neilluo.scrolllock.data.PermissionHealth.Gap>()) }
    val agents by AppGraph.state.agents.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by AppGraph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            notificationsOn = ctx.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false
            // Checked every time the screen comes back, so a revoked permission is asked for again, not once.
            gaps = com.neilluo.scrolllock.data.PermissionHealth.gaps(
                accessibilityOn = accessibilityEnabled(ctx),
                notificationsOn = notificationsOn,
                batteryExempt = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
                notificationAccessOn = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
                notificationAccessWanted = com.neilluo.scrolllock.service.PermissionAlerts.listenerWanted(ctx),
            )
            AppGraph.state.refresh()
            while (true) {
                delay(3_000)
                val s = AppGraph.settings.current()
                val g = AppGraph.state.gateNow(s)
                val age = AppGraph.state.ageMs()
                if (age == null || age > 120_000 || g is Gate.Closed || (g is Gate.Open && g.remainingMs < 5_000)) {
                    AppGraph.state.refresh()
                }
            }
        }
    }

    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }
    val progress by animateFloatAsState(if (gate.isOpen) 1f else 0f, label = "hero")

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(168.dp)
                .clip(MorphShape(morph, progress))
                .background(if (gate.isOpen) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (gate.isOpen) Icons.Filled.Check else Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = if (gate.isOpen) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(Describe.headline(gate), style = MaterialTheme.typography.headlineMediumEmphasized, fontWeight = FontWeight.SemiBold)
        Text(Describe.detail(gate), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(16.dp))
        if (gate is Gate.Open && agents.isNotEmpty()) AgentsCard(agents, settings?.showTaskText == true)
        val needsYou = (gate as? Gate.Closed)?.reason == com.neilluo.scrolllock.data.ClosedReason.NeedsYou
        if (needsYou) {
            val local by AppGraph.state.localWaitingFlow.collectAsStateWithLifecycle()
            val snap by AppGraph.state.snapshots.collectAsStateWithLifecycle(initialValue = null)
            val who = AppGraph.state.waitersNow(settings?.showTaskText == true)
            WaitingCard(if (local.isEmpty() && snap == null) emptyList() else who)
        }
        for (g in gaps) GapCard(g) { ctx.startActivity(intentFor(ctx, g.need).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        stats?.let { StatsCard(it) }
        Spacer(Modifier.height(8.dp))
        BlockedCard()
    }
}

/** Every agent that's working, each with its own progress. Task titles appear only if you've turned that on. */
@Composable
private fun AgentsCard(agents: List<AgentStatus>, showText: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Working now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (a in agents) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(AgentSummary.one(a), style = MaterialTheme.typography.titleMedium)
                val total = a.total ?: 0
                if (total > 0) {
                    LinearProgressIndicator(progress = { (a.done ?: 0).toFloat() / total }, Modifier.fillMaxWidth())
                }
                a.now?.takeIf { showText && it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                a.next?.takeIf { showText && it.isNotBlank() }?.let {
                    Text("Next: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AgentSummary.rollup(agents)?.takeIf { agents.size > 1 }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Who is waiting on you. Project names show only if you turned on task titles. */
@Composable
private fun WaitingCard(waiters: List<com.neilluo.scrolllock.data.Waiter>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Waiting on you", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
        if (waiters.isEmpty()) Text("Something needs an answer.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
        for (w in waiters) {
            Text(
                w.detail?.let { "${w.name} · $it" } ?: w.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

internal fun intentFor(ctx: android.content.Context, need: com.neilluo.scrolllock.data.PermissionHealth.Need): android.content.Intent = when (need) {
    com.neilluo.scrolllock.data.PermissionHealth.Need.Accessibility -> android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
    com.neilluo.scrolllock.data.PermissionHealth.Need.NotificationAccess -> android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    com.neilluo.scrolllock.data.PermissionHealth.Need.Notifications -> android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    com.neilluo.scrolllock.data.PermissionHealth.Need.Battery -> android.content.Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"),
    )
}

/** Something the lock needs has been switched off. Shown until it's back. */
@Composable
private fun GapCard(g: com.neilluo.scrolllock.data.PermissionHealth.Gap, onOpen: () -> Unit) {
    val loud = g.need == com.neilluo.scrolllock.data.PermissionHealth.Need.Accessibility
    val bg = if (loud) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (loud) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(g.title, style = MaterialTheme.typography.titleMedium, color = fg)
        Text(g.detail, style = MaterialTheme.typography.bodyMedium, color = fg)
        TextButton(onOpen) { Text(g.action) }
    }
}

@Composable
private fun BlockedCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Locked while Claude isn't working", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(com.neilluo.scrolllock.service.BlockedApps.names.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatsCard(s: StatsSummary) {
    // Three equal columns, so a long label wraps inside its own column instead of running into the next.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 20.dp, horizontal = 8.dp),
    ) {
        Stat("Blocked today", TimeFormat.elapsed(s.blockedTodayMs), Modifier.weight(1f))
        Stat("Clean streak", "${s.cleanStreakDays}d", Modifier.weight(1f))
        Stat("Bypasses this week", s.bypassesThisWeek.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Clips to a point along a [Morph]. Material shapes are normalised to the unit square. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress).asComposePath()
        val m = android.graphics.Matrix().apply { setScale(size.width, size.height) }
        path.asAndroidPath().transform(m)
        return Outline.Generic(path)
    }
}
