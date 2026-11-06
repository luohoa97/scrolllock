package com.neilluo.scrolllock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHealthTest {
    private fun gaps(a: Boolean = true, n: Boolean = true, b: Boolean = true, na: Boolean = true, want: Boolean = false) =
        PermissionHealth.gaps(a, n, b, na, want).map { it.need }

    @Test fun nothingMissingMeansNoGaps() = assertTrue(gaps().isEmpty())

    @Test fun accessibilityOffComesFirstAndSaysNothingIsBlocked() {
        val g = PermissionHealth.gaps(false, false, false, true, false)
        assertEquals(PermissionHealth.Need.Accessibility, g.first().need)
        assertTrue(g.first().detail.contains("NOT blocked"))
    }

    @Test fun notificationAccessOnlyCountsAfterItWasGranted() {
        assertTrue(gaps(na = false, want = false).isEmpty())
        assertEquals(listOf(PermissionHealth.Need.NotificationAccess), gaps(na = false, want = true))
    }
}
