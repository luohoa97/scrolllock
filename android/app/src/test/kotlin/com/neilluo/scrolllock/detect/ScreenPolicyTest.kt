package com.neilluo.scrolllock.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPolicyTest {
    @Test fun blocksReelsAndExploreByDefault() {
        val p = ScreenPolicy()
        assertEquals(Verdict.Block, p.feed(Surface.Reels))
        assertEquals(Verdict.Block, p.feed(Surface.Explore))
    }

    @Test fun allowsHomeDmAndProfile() {
        val p = ScreenPolicy()
        for (s in listOf(Surface.Home, Surface.Dm, Surface.Profile)) assertEquals(Verdict.Allow, p.feed(s))
    }

    @Test fun unknownIsUndecidedUntilTheLimitThenFailsClosed() {
        val p = ScreenPolicy(unknownLimit = 3)
        assertEquals(Verdict.Undecided, p.feed(Surface.Unknown))
        assertEquals(Verdict.Undecided, p.feed(Surface.Unknown))
        assertFalse(p.failingClosed)
        assertEquals(Verdict.Block, p.feed(Surface.Unknown))
        assertTrue(p.failingClosed)
    }

    @Test fun anAllowedScreenResetsTheUnknownRun() {
        val p = ScreenPolicy(unknownLimit = 2)
        p.feed(Surface.Unknown)
        assertEquals(Verdict.Allow, p.feed(Surface.Dm))
        assertEquals(Verdict.Undecided, p.feed(Surface.Unknown))
    }

    @Test fun blockedSetIsConfigurable() {
        val p = ScreenPolicy(blocked = setOf(Surface.Reels))
        assertEquals(Verdict.Allow, p.feed(Surface.Explore))
    }
}
