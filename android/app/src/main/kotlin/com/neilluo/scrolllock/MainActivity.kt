package com.neilluo.scrolllock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neilluo.scrolllock.service.PermissionAlerts
import com.neilluo.scrolllock.ui.OnboardingScreen
import com.neilluo.scrolllock.ui.accessibilityEnabled
import com.neilluo.scrolllock.ui.startSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.neilluo.scrolllock.ui.ScrollLockTheme
import com.neilluo.scrolllock.ui.SettingsScreen
import com.neilluo.scrolllock.ui.StatusScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The "where you are" notification needs this on Android 13+.
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        enableEdgeToEdge()
        setContent { ScrollLockTheme { Root() } }
        coldStart = savedInstanceState == null
    }

    private var coldStart = false

    /**
     * Android switches an accessibility service off when its app is force-stopped, and nothing runs afterwards
     * until the app is opened again. So opening it is the moment to notice: raise the alert, and on a fresh
     * launch take the user straight to the accessibility screen instead of waiting for them to find the card.
     */
    override fun onResume() {
        super.onResume()
        val first = coldStart
        coldStart = false
        AppGraph.scope.launch(Dispatchers.Main) {
            if (!AppGraph.settings.current().onboarded) return@launch
            if (accessibilityEnabled(this@MainActivity)) {
                PermissionAlerts.accessibilityBack(this@MainActivity)
            } else {
                PermissionAlerts.accessibilityOff(this@MainActivity)
                if (first) startSettings(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}

@Composable
private fun Root() {
    val settings by AppGraph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    Surface(Modifier.fillMaxSize()) {
        when {
            s == null -> Unit
            !s.onboarded -> OnboardingScreen(Modifier.safeDrawingPadding())
            else -> Home()
        }
    }
}

@Composable
private fun Home() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Lock, null) }, label = { Text("Status") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        if (tab == 0) StatusScreen(m) else SettingsScreen(m)
    }
}
