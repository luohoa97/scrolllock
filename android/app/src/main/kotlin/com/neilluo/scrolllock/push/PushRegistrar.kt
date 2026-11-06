package com.neilluo.scrolllock.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.neilluo.scrolllock.data.RelayClient
import com.neilluo.scrolllock.data.Settings
import com.neilluo.scrolllock.data.SettingsStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Keeps the relay's copy of this phone current: the FCM token plus the unlock mode it should
 * evaluate pushes with. It only calls the relay when that combination changed.
 *
 * Without app/google-services.json there is no Firebase app, and this does nothing. The app still
 * works, but on fetch-when-Instagram-opens alone, with no push.
 */
class PushRegistrar(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: RelayClient,
) {
    fun firebaseAvailable(): Boolean =
        FirebaseApp.getApps(context).isNotEmpty() || runCatching { FirebaseApp.initializeApp(context) }.getOrNull() != null

    suspend fun ensureRegistered(): Result<Unit> {
        if (!firebaseAvailable()) return Result.failure(IllegalStateException("Firebase is not configured"))
        var s = settings.current()
        if (!s.configured) return Result.failure(IllegalStateException("Relay is not set up"))
        if (s.fcmToken.isEmpty()) {
            val token = fetchToken() ?: return Result.failure(IllegalStateException("No FCM token yet"))
            settings.setFcmToken(token)
            s = settings.current()
        }
        val sig = signature(s)
        if (sig == s.registeredSig) return Result.success(Unit)
        return api.register(s, s.fcmToken).onSuccess { settings.setRegisteredSig(sig) }
    }

    suspend fun onNewToken(token: String) {
        settings.setFcmToken(token)
        ensureRegistered()
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
            if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
        }
    }

    internal companion object {
        fun signature(s: Settings): String {
            val raw = listOf(s.relayUrl, s.readToken, s.mode.wire, s.myTurnMinutes, s.graceSeconds, s.fcmToken).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
