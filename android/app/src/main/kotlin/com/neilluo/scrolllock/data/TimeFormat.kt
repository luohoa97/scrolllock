package com.neilluo.scrolllock.data

object TimeFormat {
    /** 45s, 12m 04s, 3h 12m, 2d 3h. Whole seconds, never negative. */
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            s < 60 -> "${sec}s"
            s < 3600 -> "${m}m ${sec.toString().padStart(2, '0')}s"
            s < 86_400 -> "${h}h ${m.toString().padStart(2, '0')}m"
            else -> "${d}d ${h}h"
        }
    }
}
