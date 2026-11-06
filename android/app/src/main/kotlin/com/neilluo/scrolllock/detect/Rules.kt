package com.neilluo.scrolllock.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the user is inside Instagram. Only Reels and Explore are blocked by default. */
enum class Surface { Reels, Explore, Home, Dm, Profile, Unknown }

/**
 * One condition on a single node. Every field that is set must hold for the same node.
 * Id and description matching is case-insensitive.
 */
@Serializable
data class Matcher(
    val idContains: String? = null,
    val descEquals: String? = null,
    val descContains: String? = null,
    val classContains: String? = null,
    val selected: Boolean? = null,
    /** True: node covers at least [coverRatio] of the screen. */
    val coversScreen: Boolean? = null,
    val coverRatio: Double = 0.85,
)

/**
 * A rule says "this is [surface]" when every matcher in [all] matches some node and no matcher in
 * [none] matches any node. Lower [priority] numbers are tried first.
 */
@Serializable
data class Rule(
    val name: String,
    val surface: String,
    val priority: Int = 100,
    val all: List<Matcher> = emptyList(),
    val none: List<Matcher> = emptyList(),
)

@Serializable
data class Rules(
    val version: Int = 1,
    val packageName: String = "com.instagram.android",
    val rules: List<Rule> = emptyList(),
    /** Packages the Lockdown settings guard watches. */
    val guardPackages: List<String> = emptyList(),
    /** Words that, next to the app's name, mean the screen can remove or disable ScrollLock. */
    val guardKeywords: List<String> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): Rules = json.decodeFromString(serializer(), text)
    }
}
