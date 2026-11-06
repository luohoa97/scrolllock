package com.neilluo.scrolllock.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.data.Settings
import com.neilluo.scrolllock.data.TimeFormat
import com.neilluo.scrolllock.data.UnlockMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val s by AppGraph.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("Unlock rule")
        ModeRow("While Claude is working", s.mode == UnlockMode.ClaudeBusy) {
            scope.launch { AppGraph.settings.setMode(UnlockMode.ClaudeBusy, s.myTurnMinutes, s.graceSeconds) }
        }
        ModeRow("While it's my turn (after I prompt)", s.mode == UnlockMode.MyTurn) {
            scope.launch { AppGraph.settings.setMode(UnlockMode.MyTurn, s.myTurnMinutes, s.graceSeconds) }
        }
        if (s.mode == UnlockMode.MyTurn) {
            LabeledSlider("My-turn window: ${s.myTurnMinutes} min", s.myTurnMinutes.toFloat(), 1f..60f) {
                scope.launch { AppGraph.settings.setMode(s.mode, it.toInt(), s.graceSeconds) }
            }
        }
        LabeledSlider("Grace after Claude stops: ${s.graceSeconds}s", s.graceSeconds.toFloat(), 0f..60f) {
            scope.launch { AppGraph.settings.setMode(s.mode, s.myTurnMinutes, it.toInt()) }
        }

        HorizontalDivider()
        Header("What gets blocked")
        ToggleRow("Reels", s.blockReels) { scope.launch { AppGraph.settings.setBlocked(it, s.blockExplore) } }
        ToggleRow("Explore", s.blockExplore) { scope.launch { AppGraph.settings.setBlocked(s.blockReels, it) } }
        Text("Messages are never blocked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        Header("Relay")
        RelayEditor(s)

        HorizontalDivider()
        Header("Lockdown")
        LockdownSection(s)

        HorizontalDivider()
        Header("Debug")
        Text(
            "If Reels or Explore stop being blocked after an Instagram update, tap this, open the screen that " +
                "slipped through, and paste the copied dump into an issue. Message text is never included.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            AppGraph.captureAt = android.os.SystemClock.elapsedRealtime() + 5_000
        }) { Text("Capture next Instagram screen in 5s") }
    }
}

@Composable
private fun RelayEditor(s: Settings) {
    val scope = rememberCoroutineScope()
    var url by remember(s.relayUrl) { mutableStateOf(s.relayUrl) }
    var token by remember(s.readToken) { mutableStateOf(s.readToken) }
    var result by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(url, { url = it }, label = { Text("Relay URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text("Read token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            scope.launch {
                AppGraph.settings.setRelay(url, token)
                result = AppGraph.state.refresh().fold({ "Connected" }, { "Failed: ${it.message}" })
                AppGraph.fcm.ensureRegistered()
            }
        }) { Text("Save and test") }
        result?.let { Text(it, Modifier.align(Alignment.CenterVertically)) }
    }
}

@Composable
private fun LockdownSection(s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock = AppGraph.lockdown
    var remaining by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(s.unlockRequest, s.lockdown) {
        while (true) {
            remaining = lock.unlockRemainingMs() ?: -1L
            delay(1000)
        }
    }

    if (!lock.allowed) {
        Text("Lockdown is switched off in debug builds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(
        "Makes ScrollLock hard to remove on impulse: uninstall is greyed out, its Settings pages are blocked, " +
            "and turning Lockdown off takes a ${s.coolingOffHours} hour wait. A reboot restarts the wait.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!s.lockdown) {
        Button(onClick = {
            scope.launch {
                if (!lock.isAdminActive()) ctx.startActivity(lock.addAdminIntent())
                lock.enable()
            }
        }) { Text("Turn on Lockdown") }
        return
    }
    Text("Lockdown is on" + if (lock.isAdminActive()) "" else " (device admin not active yet)", fontWeight = FontWeight.SemiBold)
    if (!lock.isAdminActive()) {
        OutlinedButton(onClick = { ctx.startActivity(lock.addAdminIntent()) }) { Text("Activate device admin") }
    }
    if (!lock.batteryExempt()) {
        OutlinedButton(onClick = { ctx.startActivity(lock.batteryIntent()) }) { Text("Allow background running") }
    }
    when {
        s.unlockRequest == null -> OutlinedButton(onClick = { scope.launch { lock.requestUnlock() } }) { Text("Request unlock") }
        remaining > 0 -> {
            Text("Unlock in ${TimeFormat.elapsed(remaining)}")
            OutlinedButton(onClick = { scope.launch { lock.cancelUnlock() } }) { Text("Cancel request") }
        }
        else -> Button(onClick = { scope.launch { lock.confirmUnlock() } }) { Text("Confirm: turn Lockdown off") }
    }
}

@Composable private fun Header(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked, onChange)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) })
    }
}
