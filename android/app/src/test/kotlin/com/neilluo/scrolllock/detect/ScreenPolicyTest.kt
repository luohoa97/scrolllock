package com.neilluo.scrolllock.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenPolicyTest {
    @Test fun everythingButMessagesIsBlocked() {
        val p = ScreenPolicy()
        for (s in listOf(Surface.Reels, Surface.Explore, Surface.Home, Surface.Profile)) {
            assertEquals("$s", Verdict.Block, p.feed(s))
        }
    }

    @Test fun anUnrecognisedScreenIsBlocked() {
        assertEquals(Verdict.Block, ScreenPolicy().feed(Surface.Unknown))
    }

    @Test fun messagesAreAllowedOnceConfirmed() {
        val p = ScreenPolicy()
        assertEquals(Verdict.Undecided, p.feed(Surface.Dm))
        assertEquals(Verdict.Allow, p.feed(Surface.Dm))
    }

    @Test fun oneStrayMessagesResultDoesNotLiftABlock() {
        val p = ScreenPolicy()
        assertEquals(Verdict.Block, p.feed(Surface.Reels))
        assertEquals(Verdict.Undecided, p.feed(Surface.Dm))
        assertEquals(Verdict.Block, p.feed(Surface.Reels))
        assertEquals(Verdict.Undecided, p.feed(Surface.Dm))
        assertEquals(Verdict.Allow, p.feed(Surface.Dm))
    }

    @Test fun leavingMessagesBlocksAtOnce() {
        val p = ScreenPolicy()
        p.feed(Surface.Dm); p.feed(Surface.Dm)
        assertEquals(Verdict.Block, p.feed(Surface.Home))
    }

    @Test fun resetForgetsProgress() {
        val p = ScreenPolicy()
        p.feed(Surface.Dm)
        p.reset()
        assertEquals(Verdict.Undecided, p.feed(Surface.Dm))
    }

    @Test fun theAllowedSetIsConfigurable() {
        val p = ScreenPolicy(allowed = setOf(Surface.Dm, Surface.Home))
        p.feed(Surface.Home)
        assertEquals(Verdict.Allow, p.feed(Surface.Home))
    }
}
