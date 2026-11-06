package com.neilluo.scrolllock.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.lifecycle.repeatOnLifecycle
import com.neilluo.scrolllock.data.PermissionHealth.Need as N
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))

        Section("Permissions") { PermissionsRows() }

        Section("Unlock rule") {
            ModeRow("While Claude is working", "Open whenever a Claude Code session is busy", s.mode == UnlockMode.ClaudeBusy) {
                scope.launch { AppGraph.settings.setMode(UnlockMode.ClaudeBusy, s.myTurnMinutes, s.graceSeconds) }
            }
            ModeRow("While it's my turn", "Open for a while after you send a prompt", s.mode == UnlockMode.MyTurn) {
                scope.launch { AppGraph.settings.setMode(UnlockMode.MyTurn, s.myTurnMinutes, s.graceSeconds) }
            }
            if (s.mode == UnlockMode.MyTurn) {
                LabeledSlider("My-turn window", "${s.myTurnMinutes} min", s.myTurnMinutes.toFloat(), 1f..60f) {
                    scope.launch { AppGraph.settings.setMode(s.mode, it.toInt(), s.graceSeconds) }
                }
            }
            LabeledSlider("Grace after Claude stops", "${s.graceSeconds}s", s.graceSeconds.toFloat(), 0f..60f) {
                scope.launch { AppGraph.settings.setMode(s.mode, s.myTurnMinutes, it.toInt()) }
            }
        }

        Section("Agent tasks") {
            Text(
                "The status notification and Status screen show which agents are working and how many tasks each has done.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ToggleRow("Show task titles", s.showTaskText) { on -> scope.launch { AppGraph.settings.setShowTaskText(on) } }
            Text(
                "Off: only agent names and counts. On: also the current and next task, if your agent's hook is set to send them (SCROLLLOCK_TASK_TEXT=1). Titles go to your relay and database, and never appear on the lock screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Capsule and notification") {
            ToggleRow("Show capsule around the camera", s.showCapsule) { on -> scope.launch { AppGraph.settings.setShowCapsule(on) } }
            Text(
                "A small capsule at the top while a blocked app is open and Claude is working. Its own drawing, not the phone maker's Magic Capsule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                OutlinedButton(onClick = {
                    ctx.startSettings(
                        android.content.Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        // Some phone makers have no Live Updates screen; their notification page for the app is the next best.
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                }) { Text("Live updates settings") }
                Text(
                    "ScrollLock asks the system to show its status as a Live Update chip while Claude is working. Whether your phone draws that as a capsule is up to the phone maker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Waiting on you") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text(
                "When the Claude app on this phone has a notification that needs you (approval, a question, a coordinator waiting), the blocked apps stay locked. Give ScrollLock notification access for that. It reads only the Claude app's notifications, keeps them in memory, and sends nothing anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                ctx.startSettings(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Notification access") }
            Text(
                "Sideloaded and greyed out? App info, three dots, Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                AppGraph.samples.clear()
                AppGraph.sampleUntil = android.os.SystemClock.elapsedRealtime() + 120_000
            }) { Text("Capture Claude notifications (2 min)") }
            OutlinedButton(onClick = {
                val text = AppGraph.samples.joinToString("\n").ifEmpty { "(nothing captured)" }
                ctx.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Claude notifications", text))
                android.widget.Toast.makeText(ctx, "Copied ${AppGraph.samples.size} notification(s)", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("Copy captured") }
            Text(
                "Tap Capture, then let the Claude app post a notification that needs you, then Copy. Paste it into an issue so the wording can be matched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Keep it running") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val guide = remember { com.neilluo.scrolllock.data.VendorGuide.forManufacturer(android.os.Build.MANUFACTURER) }
            val exempt = remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                exempt.value = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            }
            Text(
                "If your phone stops ScrollLock in the background, the blocked apps open. Steps for ${guide.maker}:",
                style = MaterialTheme.typography.bodyMedium,
            )
            for ((i, step) in guide.steps.withIndex()) Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (exempt.value) "Battery limits: ScrollLock is exempt." else "Battery limits: not exempt yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ctx.startSettings(
                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Battery") }
                OutlinedButton(onClick = {
                    // Try the maker's own screen; the names differ between versions, so fall back to App info.
                    val opened = guide.components.any { (pkg, cls) ->
                        runCatching {
                            ctx.startActivity(android.content.Intent().setClassName(pkg, cls).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                    }
                    if (!opened) ctx.startSettings(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Autostart") }
            }
        }

        Section("While locked") {
            Text(
                "Instagram, YouTube, TikTok, Snapchat and Facebook are blocked whole while Claude isn't working. Discord is left alone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val gate by AppGraph.state.gate.collectAsStateWithLifecycle()
            val claudeWorking = gate is com.neilluo.scrolllock.data.Gate.Open
            ToggleRow(
                "Allow one 5 minute bypass a day",
                s.bypassEnabled,
                enabled = s.bypassEnabled || claudeWorking,
            ) { on -> scope.launch { AppGraph.state.setBypassEnabled(on) } }
            if (!s.bypassEnabled && !claudeWorking) {
                Text(
                    "Locked. This can only be switched on while Claude is working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Relay") { RelayEditor(s) }

        Section("Lockdown") { LockdownSection(s) }

        Section("Debug", note = "For bug reports: tap this, open the app screen you mean, and paste the copied dump into an issue. Message text is never included.") {
            OutlinedButton(onClick = {
                AppGraph.captureAt = android.os.SystemClock.elapsedRealtime() + 5_000
            }) { Text("Capture next app screen in 5s") }
        }
    }
}

@Composable
private fun RelayEditor(s: Settings) {
    val scope = rememberCoroutineScope()
    var url by remember(s.relayUrl) { mutableStateOf(s.relayUrl) }
    var token by remember(s.readToken) { mutableStateOf(s.readToken) }
    var result by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(url, { url = it }, label = { Text("Relay URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text("Read token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
                if (!lock.isAdminActive()) ctx.startSettings(lock.addAdminIntent())
                lock.enable()
            }
        }) { Text("Turn on Lockdown") }
        return
    }
    Text("Lockdown is on" + if (lock.isAdminActive()) "" else " (device admin not active yet)", fontWeight = FontWeight.SemiBold)
    if (!lock.isAdminActive()) {
        OutlinedButton(onClick = { ctx.startSettings(lock.addAdminIntent()) }) { Text("Activate device admin") }
    }
    if (!lock.batteryExempt()) {
        OutlinedButton(onClick = { ctx.startSettings(lock.batteryIntent()) }) { Text("Allow background running") }
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

@Composable
private fun Section(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModeRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (v == value) valueText else v.toInt().toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) })
    }
}

/** What's switched on and what isn't, with a button to each system screen. Re-read every time the screen is shown. */
@Composable
private fun PermissionsRows() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var on by remember { mutableStateOf(mapOf<com.neilluo.scrolllock.data.PermissionHealth.Need, Boolean>()) }
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            on = mapOf(
                com.neilluo.scrolllock.data.PermissionHealth.Need.Accessibility to accessibilityEnabled(ctx),
                com.neilluo.scrolllock.data.PermissionHealth.Need.Notifications to
                    (ctx.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false),
                com.neilluo.scrolllock.data.PermissionHealth.Need.NotificationAccess to
                    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
                com.neilluo.scrolllock.data.PermissionHealth.Need.Battery to
                    ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            )
        }
    }
    val rows = listOf(
        Triple(N.Accessibility, "Accessibility", if (on[N.Accessibility] == true) "On. Blocked apps are blocked." else "Off. Blocked apps are NOT blocked."),
        Triple(N.Notifications, "Notifications", if (on[N.Notifications] == true) "On." else "Off. The status notification can't show."),
        Triple(N.NotificationAccess, "Claude app access", if (on[N.NotificationAccess] == true) "On. \"Waiting on you\" works." else "Off. Optional, needed for \"Waiting on you\"."),
        Triple(N.Battery, "Battery limits", if (on[N.Battery] == true) "Exempt." else "Not exempt. Some phones will stop ScrollLock."),
    )
    for ((need, name, state) in rows) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { ctx.startSettings(intentFor(ctx, need).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text(if (on[need] == true) "Open" else "Turn on")
            }
        }
    }
}
