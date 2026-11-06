package com.neilluo.scrolllock.service

import android.content.Context
import com.neilluo.scrolllock.detect.Rules
import java.io.File

/**
 * Rules ship in assets. A copy dropped at files/rules.json wins, so a broken Instagram release can
 * be fixed by pushing a file to the phone instead of rebuilding the app. A bad override is ignored.
 */
object RulesLoader {
    const val OVERRIDE_NAME = "rules.json"

    fun load(context: Context): Rules {
        val override = File(context.filesDir, OVERRIDE_NAME)
        if (override.isFile) {
            runCatching { Rules.parse(override.readText()) }.onSuccess { return it }
        }
        return Rules.parse(context.assets.open("rules.json").bufferedReader().use { it.readText() })
    }
}
