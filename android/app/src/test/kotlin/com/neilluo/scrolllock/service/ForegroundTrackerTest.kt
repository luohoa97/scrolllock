package com.neilluo.scrolllock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    private val ig = "com.instagram.android"
    private fun tracker() = ForegroundTracker(ig) { setOf("com.android.systemui", "com.neilluo.scrolllock", "com.google.android.inputmethod.latin") }

    @Test fun instagramComingToFrontIsAChange() {
        val t = tracker()
        assertTrue(t.onWindowState(ig))
        assertTrue(t.targetInFront)
    }

    @Test fun sameAppAgainIsNotAChange() {
        val t = tracker()
        t.onWindowState(ig)
        assertFalse(t.onWindowState(ig))
    }

    @Test fun systemUiKeyboardAndOwnOverlayDoNotTakeTheForeground() {
        val t = tracker()
        t.onWindowState(ig)
        assertFalse(t.onWindowState("com.android.systemui"))
        assertFalse(t.onWindowState("com.google.android.inputmethod.latin"))
        assertFalse(t.onWindowState("com.neilluo.scrolllock"))
        assertTrue(t.targetInFront)
    }

    @Test fun anotherAppTakesTheForeground() {
        val t = tracker()
        t.onWindowState(ig)
        assertTrue(t.onWindowState("com.android.launcher"))
        assertFalse(t.targetInFront)
        assertEquals("com.android.launcher", t.current)
    }

    @Test fun nullAndEmptyAreIgnored() {
        val t = tracker()
        t.onWindowState(ig)
        assertFalse(t.onWindowState(null))
        assertFalse(t.onWindowState(""))
        assertTrue(t.targetInFront)
    }
}
