package com.neilluo.scrolllock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "scrolllock")

data class Settings(
    val relayUrl: String = "",
    val readToken: String = "",
    val mode: UnlockMode = UnlockMode.ClaudeBusy,
    val myTurnMinutes: Int = 15,
    val graceSeconds: Int = 10,
    val onboarded: Boolean = false,
    val lockdown: Boolean = false,
    /** Armed bypass. Can only be switched on while Claude is working (see StateRepository). */
    val bypassEnabled: Boolean = false,
    val coolingOffHours: Int = 24,
    val unlockRequest: UnlockRequest? = null,
    val bypass: BypassState? = null,
    val fcmToken: String = "",
    /** Hash of what the relay was last told about this device, so we only re-register on change. */
    val registeredSig: String = "",
) {
    val configured: Boolean get() = relayUrl.isNotBlank() && readToken.isNotBlank()
}

/** Everything the user can set, persisted in one DataStore. Small enough that Room would be noise. */
class SettingsStore(private val context: Context) {
    private object K {
        val relayUrl = stringPreferencesKey("relayUrl")
        val readToken = stringPreferencesKey("readToken")
        val mode = stringPreferencesKey("mode")
        val myTurnMinutes = intPreferencesKey("myTurnMinutes")
        val graceSeconds = intPreferencesKey("graceSeconds")
        val onboarded = booleanPreferencesKey("onboarded")
        val lockdown = booleanPreferencesKey("lockdown")
        val bypassEnabled = booleanPreferencesKey("bypassEnabled")
        val coolingOffHours = intPreferencesKey("coolingOffHours")
        val reqBoot = intPreferencesKey("unlockReqBoot")
        val reqElapsed = longPreferencesKey("unlockReqElapsed")
        val bypassBoot = intPreferencesKey("bypassBoot")
        val bypassUntil = longPreferencesKey("bypassUntil")
        val bypassDay = stringPreferencesKey("bypassDay")
        val fcmToken = stringPreferencesKey("fcmToken")
        val registeredSig = stringPreferencesKey("registeredSig")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            relayUrl = p[K.relayUrl].orEmpty(),
            readToken = p[K.readToken].orEmpty(),
            mode = UnlockMode.fromWire(p[K.mode]),
            myTurnMinutes = p[K.myTurnMinutes] ?: 15,
            graceSeconds = p[K.graceSeconds] ?: 10,
            onboarded = p[K.onboarded] ?: false,
            lockdown = p[K.lockdown] ?: false,
            bypassEnabled = p[K.bypassEnabled] ?: false,
            coolingOffHours = p[K.coolingOffHours] ?: 24,
            unlockRequest = p[K.reqElapsed]?.let { UnlockRequest(p[K.reqBoot] ?: 0, it) },
            bypass = p[K.bypassDay]?.let { BypassState(p[K.bypassBoot] ?: 0, p[K.bypassUntil] ?: 0, it) },
            fcmToken = p[K.fcmToken].orEmpty(),
            registeredSig = p[K.registeredSig].orEmpty(),
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun setRelay(url: String, token: String) = context.dataStore.edit {
        it[K.relayUrl] = url.trim().trimEnd('/')
        it[K.readToken] = token.trim()
        it[K.registeredSig] = ""
    }

    suspend fun setMode(mode: UnlockMode, minutes: Int, grace: Int) = context.dataStore.edit {
        it[K.mode] = mode.wire
        it[K.myTurnMinutes] = minutes.coerceIn(1, 240)
        it[K.graceSeconds] = grace.coerceIn(0, 120)
        it[K.registeredSig] = ""
    }

    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[K.onboarded] = v }
    suspend fun setLockdown(v: Boolean) = context.dataStore.edit { it[K.lockdown] = v }

    suspend fun setBypassEnabled(v: Boolean) = context.dataStore.edit { it[K.bypassEnabled] = v }
    suspend fun setCoolingOffHours(h: Int) = context.dataStore.edit { it[K.coolingOffHours] = h.coerceIn(1, 168) }

    suspend fun setUnlockRequest(r: UnlockRequest?) = context.dataStore.edit {
        if (r == null) {
            it.remove(K.reqBoot); it.remove(K.reqElapsed)
        } else {
            it[K.reqBoot] = r.bootCount; it[K.reqElapsed] = r.requestedAtElapsed
        }
    }

    suspend fun setBypass(b: BypassState) = context.dataStore.edit {
        it[K.bypassBoot] = b.bootCount
        it[K.bypassUntil] = b.untilElapsed
        it[K.bypassDay] = b.lastUsedDay.orEmpty()
    }

    suspend fun setFcmToken(token: String) = context.dataStore.edit {
        it[K.fcmToken] = token
        it[K.registeredSig] = ""
    }

    suspend fun setRegisteredSig(sig: String) = context.dataStore.edit { it[K.registeredSig] = sig }
}
