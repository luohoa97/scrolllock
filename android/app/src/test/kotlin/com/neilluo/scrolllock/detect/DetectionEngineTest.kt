package com.neilluo.scrolllock.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionEngineTest {
    private val engine = DetectionEngine(Fixtures.shippedRules())

    private fun surface(fixture: String) = engine.detect(Fixtures.load(fixture))

    @Test fun homeFeedIsHome() = assertEquals(Surface.Home, surface("home"))
    @Test fun reelsTabIsReels() = assertEquals(Surface.Reels, surface("reels_tab"))
    @Test fun reelsViewerWithoutTabBarIsReels() = assertEquals(Surface.Reels, surface("reels_opened_from_profile"))
    @Test fun exploreTabIsExplore() = assertEquals(Surface.Explore, surface("explore"))
    @Test fun profileIsProfile() = assertEquals(Surface.Profile, surface("profile"))
    @Test fun dmInboxIsDm() = assertEquals(Surface.Dm, surface("dm_inbox"))
    @Test fun dmThreadIsDm() = assertEquals(Surface.Dm, surface("dm_thread"))
    @Test fun renamedIdsAreUnknown() = assertEquals(Surface.Unknown, surface("unknown_after_update"))
    @Test fun otherPackageIsUnknownAndUnexplained() {
        val t = Fixtures.load("other_app")
        assertEquals(Surface.Unknown, engine.detect(t))
        assertNull(engine.explain(t))
    }

    @Test fun explainNamesTheRuleThatFired() {
        assertEquals("home-tab-selected-by-label", engine.explain(Fixtures.load("home")))
    }

    @Test fun lowerPriorityNumberWinsWhenTwoRulesMatch() {
        // reels_tab carries both clips_viewer ids (priority 20) and a selected Reels tab (21).
        assertEquals("reels-viewer", engine.explain(Fixtures.load("reels_tab")))
    }

    @Test fun noneClauseExcludesAMatch() {
        val rules = Rules(
            rules = listOf(
                Rule("r", "Reels", all = listOf(Matcher(idContains = "viewer")), none = listOf(Matcher(idContains = "tab_bar"))),
            ),
        )
        val e = DetectionEngine(rules)
        val screen = Box(0, 0, 100, 100)
        val viewer = NodeSnapshot(id = "x:id/viewer")
        assertEquals(Surface.Reels, e.detect(TreeSnapshot("com.instagram.android", screen, listOf(viewer))))
        assertEquals(
            Surface.Unknown,
            e.detect(TreeSnapshot("com.instagram.android", screen, listOf(viewer, NodeSnapshot(id = "x:id/tab_bar")))),
        )
    }

    @Test fun coversScreenMatcherUsesRatio() {
        val rules = Rules(rules = listOf(Rule("pager", "Reels", all = listOf(Matcher(classContains = "ViewPager", coversScreen = true)))))
        val e = DetectionEngine(rules)
        val screen = Box(0, 0, 100, 100)
        val big = NodeSnapshot(cls = "ViewPager2", bounds = Box(0, 0, 100, 95))
        val small = NodeSnapshot(cls = "ViewPager2", bounds = Box(0, 0, 100, 40))
        assertEquals(Surface.Reels, e.detect(TreeSnapshot("com.instagram.android", screen, listOf(big))))
        assertEquals(Surface.Unknown, e.detect(TreeSnapshot("com.instagram.android", screen, listOf(small))))
    }

    @Test fun matchingIsCaseInsensitiveAndTrimsDescriptions() {
        val rules = Rules(rules = listOf(Rule("r", "Home", all = listOf(Matcher(descEquals = "home", selected = true)))))
        val e = DetectionEngine(rules)
        val n = NodeSnapshot(desc = "  HOME ", selected = true)
        assertEquals(Surface.Home, e.detect(TreeSnapshot("com.instagram.android", Box(0, 0, 1, 1), listOf(n))))
    }

    @Test fun rulesParseIgnoringUnknownKeys() {
        val r = Rules.parse("""{"version":2,"future":true,"rules":[{"name":"a","surface":"Home","all":[{"descEquals":"Home"}]}]}""")
        assertEquals(1, r.rules.size)
    }
}
