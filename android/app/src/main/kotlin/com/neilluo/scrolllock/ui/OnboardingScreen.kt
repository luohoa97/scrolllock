package com.neilluo.scrolllock.ui

import android.content.ComponentName
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.service.BlockerService
import kotlinx.coroutines.launch

/** Is the ScrollLock accessibility service switched on in system settings? */
fun accessibilityEnabled(context: android.content.Context): Boolean {
    val me = ComponentName(context, BlockerService::class.java).flattenToString()
    val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableStateOf(0) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Step ${step + 1} of 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (step) {
            0 -> RelayStep { step = 1 }
            1 -> AccessibilityStep { step = 2 }
            2 -> BatteryStep { step = 3 }
            else -> DoneStep()
        }
    }
}

@Composable
private fun RelayStep(next: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    Text("Connect to your relay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("Paste the relay URL and the read token from your Vercel project. The read token only lets this phone ask whether Claude is working.")
    OutlinedTextField(url, { url = it }, label = { Text("Relay URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text("Read token") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = url.isNotBlank() && token.isNotBlank(), onClick = {
            scope.launch {
                AppGraph.settings.setRelay(url, token)
                val r = AppGraph.state.refresh()
                ok = r.isSuccess
                message = r.fold({ "Connected. Claude is ${if (it.working) "working" else "idle"} right now." }, { "Couldn't connect: ${it.message}" })
                if (ok) AppGraph.fcm.ensureRegistered()
            }
        }) { Text("Test connection") }
        if (ok) Button(onClick = next) { Text("Next") }
    }
    message?.let { Text(it) }
}

@Composable
private fun AccessibilityStep(next: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(accessibilityEnabled(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) enabled = accessibilityEnabled(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Text("Turn on the accessibility service", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "ScrollLock uses it to see when Instagram, YouTube, TikTok, Snapchat or Facebook is on screen and cover it " +
            "while Claude isn't working. It never reads message text.",
    )
    Text(
        "Sideloaded apps start with this switch greyed out. If it is: open App info, tap the three dots, choose " +
            "Allow restricted settings, then come back.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("App info") }
        Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility settings") }
    }
    Text(if (enabled) "On. Nice." else "Not on yet.", fontWeight = FontWeight.SemiBold)
    if (enabled) Button(onClick = next) { Text("Next") }
}

@Composable
private fun BatteryStep(next: () -> Unit) {
    val ctx = LocalContext.current
    Text("Let it run in the background", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("Battery optimisation can delay the push that unlocks Instagram. Exempting ScrollLock keeps unlocks fast.")
    OutlinedButton(onClick = { ctx.startActivity(AppGraph.lockdown.batteryIntent()) }) { Text("Allow background running") }
    Row { Button(onClick = next) { Text("Next") }; TextButton(onClick = next) { Text("Skip") } }
}

@Composable
private fun DoneStep() {
    val scope = rememberCoroutineScope()
    Text("You're set", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Open Instagram while Claude is idle: you should see the lock screen. Start a Claude Code " +
            "task and it should open within a couple of seconds.",
    )
    if (!AppGraph.fcm.firebaseAvailable()) {
        Text(
            "Push isn't set up in this build (no google-services.json), so unlocks are checked when Instagram opens.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = { scope.launch { AppGraph.settings.setOnboarded(true) } }) { Text("Finish") }
}
