package com.neilluo.scrolllock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.statsStore: DataStore<Preferences> by preferencesDataStore(name = "scrolllock_stats")

@Serializable
data class DayStats(val blockedMs: Long = 0, val bypasses: Int = 0)

data class StatsSummary(
    val blockedTodayMs: Long,
    val blockedTotalMs: Long,
    val bypassesThisWeek: Int,
    val bypassesTotal: Int,
    /** Days in a row, ending today, with no bypass, counted from the first day with any record. */
    val cleanStreakDays: Int,
)

/** Pure summary maths, kept apart from storage so it is unit tested. */
object StatsMath {
    fun summarize(days: Map<String, DayStats>, today: LocalDate): StatsSummary {
        val weekStart = today.minusDays(6)
        // Walk back from today to the first day we have any record for; a bypass ends the run.
        val first = days.keys.minOrNull()?.let { LocalDate.parse(it) }
        var streak = 0
        if (first != null) {
            var d = today
            while (!d.isBefore(first) && (days[d.toString()]?.bypasses ?: 0) == 0) {
                streak++
                d = d.minusDays(1)
            }
        }
        return StatsSummary(
            blockedTodayMs = days[today.toString()]?.blockedMs ?: 0,
            blockedTotalMs = days.values.sumOf { it.blockedMs },
            bypassesThisWeek = days.filterKeys { LocalDate.parse(it) in weekStart..today }.values.sumOf { it.bypasses },
            bypassesTotal = days.values.sumOf { it.bypasses },
            cleanStreakDays = streak,
        )
    }
}

class StatsStore(private val context: Context) {
    private val key = stringPreferencesKey("days")
    private val ser = MapSerializer(String.serializer(), DayStats.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    val days: Flow<Map<String, DayStats>> = context.statsStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()
    }

    val summary: Flow<StatsSummary> = days.map { StatsMath.summarize(it, LocalDate.now()) }

    suspend fun addBlocked(ms: Long, day: String = LocalDate.now().toString()) = update(day) { it.copy(blockedMs = it.blockedMs + ms) }
    suspend fun addBypass(day: String = LocalDate.now().toString()) = update(day) { it.copy(bypasses = it.bypasses + 1) }

    private suspend fun update(day: String, f: (DayStats) -> DayStats) {
        val current = days.first().toMutableMap()
        current[day] = f(current[day] ?: DayStats())
        // Keep a year of history; the numbers are tiny.
        val trimmed = current.entries.sortedByDescending { it.key }.take(366).associate { it.key to it.value }
        context.statsStore.edit { it[key] = json.encodeToString(ser, trimmed) }
    }
}
