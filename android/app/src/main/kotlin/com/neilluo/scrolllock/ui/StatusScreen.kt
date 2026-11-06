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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val gate by AppGraph.state.gate.collectAsStateWithLifecycle()
    val stats by AppGraph.stats.summary.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

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
        FilledTonalButton(onClick = { scope.launch { AppGraph.state.refresh() } }) { Text("Refresh now") }
        Spacer(Modifier.height(16.dp))
        stats?.let { StatsCard(it) }
        Spacer(Modifier.height(8.dp))
        BlockedCard()
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
