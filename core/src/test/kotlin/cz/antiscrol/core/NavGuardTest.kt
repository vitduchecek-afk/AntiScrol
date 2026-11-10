package cz.antiscrol.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGuardTest {

    private val rules: Rules = Rules.parse(File(System.getProperty("antiscrol.rulesFile")).readText())
    private val ig = "https://www.instagram.com"
    private val thread = "$ig/direct/t/123/"

    private class Recorder : NavGuard.Listener {
        val events = mutableListOf<String>()
        override fun onAllowed(url: String, decision: Decision.Allow) { events += "allowed ${decision.category}" }
        override fun onBlockedAttempt(url: String) { events += "attempt $url" }
        override fun onBlockedCommitted(url: String) { events += "committed $url" }
    }

    private var loggedIn = true
    private val recorder = Recorder()
    private val guard = NavGuard(UrlPolicy(rules.policy), { loggedIn }, recorder)

    @Test
    fun reelFlowFromMessagesAndBack() {
        guard.commit("$ig/")
        guard.commit(thread)
        assertTrue(guard.check("$ig/reel/AAA/") is Decision.Allow)
        guard.commit("$ig/reel/AAA/")
        assertEquals(Category.CONTENT, guard.currentCategory)

        // Next reel: refused, and the user stays on the first one.
        assertTrue(guard.check("$ig/reel/BBB/") is Decision.Block)
        assertEquals("$ig/reel/AAA/", guard.currentUrl)

        // Back to the thread, then the Stories tab; the reel is no longer reachable from there.
        guard.commit(thread)
        guard.commit("$ig/")
        assertTrue(guard.check("$ig/reel/AAA/") is Decision.Block)
    }

    @Test
    fun blockedCommitIsReportedOnceAndKeepsTheLastAllowedPage() {
        guard.commit(thread)
        guard.commit("$ig/explore/")
        guard.commit("$ig/explore/")
        assertEquals(listOf("allowed DIRECT", "committed $ig/explore/"), recorder.events)
        assertEquals(thread, guard.currentUrl)

        // After the user is back on an allowed page, a new blocked commit is reported again.
        guard.commit(thread)
        guard.commit("$ig/explore/")
        assertEquals(2, recorder.events.count { it.startsWith("committed") })
    }

    @Test
    fun blankPagesAreIgnored() {
        guard.commit("about:blank")
        assertTrue(recorder.events.isEmpty())
        assertNull(guard.currentUrl)
    }

    @Test
    fun verdictsForTheScript() {
        assertEquals("allow:home", guard.encode(guard.check("$ig/")))
        loggedIn = false
        assertEquals("allow:plain", guard.encode(guard.check("$ig/")))
        assertEquals("allow:plain", guard.encode(guard.check("$ig/accounts/login/")))
        assertEquals("allow:direct", guard.encode(guard.check(thread)))
        assertEquals("allow:stories", guard.encode(guard.check("$ig/stories/kamarad/1/")))
        assertEquals("block", guard.encode(guard.check("$ig/reels/")))
        assertEquals("external", guard.encode(guard.check("https://example.com/")))
        assertEquals("ignore", guard.encode(guard.check("intent://x")))
        guard.commit(thread)
        assertEquals("allow:content:AAA", guard.encode(guard.check("$ig/reel/AAA/")))
        assertEquals("allow:reelsViewer:AAA", guard.encode(guard.check("$ig/reels/AAA/")))
    }

    @Test
    fun turningProfilesOffTakesEffectImmediately() {
        guard.policy = UrlPolicy(rules.policy, profilesEnabled = true)
        assertTrue(guard.commit("$ig/kamarad/") is Decision.Allow)
        guard.policy = UrlPolicy(rules.policy, profilesEnabled = false)
        assertTrue(guard.check("$ig/kamarad/") is Decision.Block)
    }

    @Test
    fun resetForgetsTheCurrentPage() {
        guard.commit(thread)
        guard.reset()
        assertNull(guard.currentUrl)
        assertTrue(guard.check("$ig/reel/AAA/") is Decision.Block)
    }

    @Test
    fun userAgentLooksLikeChrome() {
        val webViewUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.002; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.6723.58 Mobile Safari/537.36",
            UserAgents.chromeLike(webViewUa),
        )
        assertTrue(UserAgents.chromeLike(null).contains("Chrome/"))
        assertTrue(UserAgents.chromeLike("something odd").contains("Mobile Safari"))
    }
}
