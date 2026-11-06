package com.neilluo.scrolllock.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeDumpTest {
    private val tree = TreeSnapshot(
        "com.instagram.android",
        Box(0, 0, 1080, 2400),
        listOf(
            NodeSnapshot(id = "com.instagram.android:id/row_inbox_username", desc = "Alice Smith, hey are you free tonight"),
            NodeSnapshot(id = "com.instagram.android:id/clips_tab", desc = "Reels", selected = true),
            NodeSnapshot(id = "com.instagram.android:id/feed_tab", desc = "Home"),
        ),
    )

    @Test fun redactsDescriptionsOfOrdinaryNodes() {
        val out = TreeDump.toJson(tree, Surface.Reels, "reels-viewer")
        assertFalse(out.contains("Alice Smith"))
        assertFalse(out.contains("free tonight"))
        assertTrue(out.contains("redacted len="))
    }

    @Test fun keepsSelectedAndTabDescriptions() {
        val out = TreeDump.toJson(tree, Surface.Reels, "reels-viewer")
        assertTrue(out.contains("\"desc\": \"Reels\""))
        assertTrue(out.contains("\"desc\": \"Home\""))
    }

    @Test fun recordsTheVerdict() {
        val out = TreeDump.toJson(tree, Surface.Reels, "reels-viewer")
        assertTrue(out.contains("\"surface\": \"Reels\""))
        assertTrue(out.contains("\"rule\": \"reels-viewer\""))
    }
}
