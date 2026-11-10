package cz.antiscrol.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the real docs/rules.json that ships in the app. */
class UrlPolicyTest {

    private val rules: Rules = Rules.parse(File(System.getProperty("antiscrol.rulesFile")).readText())
    private val policy = UrlPolicy(rules.policy, profilesEnabled = false)
    private val withProfiles = UrlPolicy(rules.policy, profilesEnabled = true)

    private val ig = "https://www.instagram.com"
    private val home = "$ig/"
    private val inbox = "$ig/direct/inbox/"
    private val thread = "$ig/direct/t/340282366841710300949128137443944319000/"

    private fun allow(category: Category, id: String? = null) = Decision.Allow(category, id)

    private fun assertBlocked(url: String, from: String? = home, p: UrlPolicy = policy) {
        val decision = p.decide(url, from)
        assertTrue("$url from $from should be blocked but was $decision", decision is Decision.Block)
    }

    // --- PLAN.md table: login and 2FA ---------------------------------------------------

    @Test
    fun loginAndTwoFactorAreAllowed() {
        for (path in listOf(
            "/accounts/login/",
            "/accounts/login",
            "/accounts/login/?next=%2Fdirect%2Finbox%2F",
            "/accounts/login/two_factor?next=%2F",
            "/accounts/onetap/?next=%2F",
            "/accounts/edit/",
            "/accounts/password/reset/",
            "/challenge/",
            "/challenge/action/AbCdEf/xyz/",
            "/auth_platform/codeentry/?apc=abc",
        )) {
            assertEquals(path, allow(Category.AUTH), policy.decide(ig + path, null))
        }
    }

    // --- PLAN.md table: home ------------------------------------------------------------

    @Test
    fun homeIsAllowed() {
        assertEquals(allow(Category.HOME), policy.decide(home, null))
        assertEquals(allow(Category.HOME), policy.decide(ig, null))
        assertEquals(allow(Category.HOME), policy.decide("$ig/?variant=following", inbox))
        assertEquals(allow(Category.HOME), policy.decide("https://instagram.com/#top", null))
        assertEquals(allow(Category.HOME), policy.decide("HTTPS://WWW.INSTAGRAM.COM/", null))
    }

    // --- PLAN.md table: stories ---------------------------------------------------------

    @Test
    fun storiesAreAllowed() {
        assertEquals(allow(Category.STORIES), policy.decide("$ig/stories/kamarad/3123456789012345678/", home))
        assertEquals(allow(Category.STORIES), policy.decide("$ig/stories/highlights/17900000000000000/", home))
        assertEquals(allow(Category.STORIES), policy.decide("$ig/stories/kamarad/", thread))
    }

    // --- PLAN.md table: messages --------------------------------------------------------

    @Test
    fun directMessagesAreAllowed() {
        for (url in listOf(inbox, thread, "$ig/direct/", "$ig/direct", "$ig/direct/new/", "$ig/direct/requests/")) {
            assertEquals(url, allow(Category.DIRECT), policy.decide(url, home))
        }
    }

    // --- PLAN.md table: reel / post only when opened from messages ----------------------

    @Test
    fun reelOpenedFromMessagesIsAllowed() {
        assertEquals(allow(Category.CONTENT, "DAbc_12-xyz"), policy.decide("$ig/reel/DAbc_12-xyz/", thread))
        assertEquals(allow(Category.CONTENT, "DAbc_12-xyz"), policy.decide("$ig/reel/DAbc_12-xyz/?igsh=abc", inbox))
        assertEquals(allow(Category.CONTENT, "Cxyz"), policy.decide("$ig/p/Cxyz/", thread))
        assertEquals(allow(Category.CONTENT, "Cxyz"), policy.decide("$ig/kamarad/p/Cxyz/", thread))
        assertEquals(allow(Category.CONTENT, "Cxyz"), policy.decide("$ig/kamarad/reel/Cxyz/", thread))
        assertEquals(allow(Category.REELS_VIEWER, "DAbc"), policy.decide("$ig/reels/DAbc/", thread))
    }

    @Test
    fun reelOpenedFromAnywhereElseIsBlocked() {
        val reel = "$ig/reel/DAbc/"
        assertEquals(Decision.Block(BlockReason.CONTENT_NOT_FROM_DIRECT), policy.decide(reel, home))
        assertEquals(Decision.Block(BlockReason.CONTENT_NOT_FROM_DIRECT), policy.decide(reel, null))
        assertBlocked(reel, from = "$ig/stories/kamarad/1/")
        assertBlocked(reel, from = "$ig/accounts/login/")
        assertBlocked(reel, from = "$ig/explore/")
        assertBlocked("$ig/p/Cxyz/", from = home)
        assertBlocked("$ig/reels/DAbc/", from = home)
        // Coming from a look-alike of the inbox on another host does not count.
        assertBlocked(reel, from = "https://evil.example/direct/inbox/")
    }

    @Test
    fun anotherReelFromAnOpenReelIsBlocked() {
        val first = "$ig/reel/AAA/"
        // Swiping to the next reel or tapping a recommended one.
        assertBlocked("$ig/reel/BBB/", from = first)
        assertBlocked("$ig/reels/BBB/", from = first)
        assertBlocked("$ig/reels/BBB/", from = "$ig/reels/AAA/")
        assertBlocked("$ig/p/BBB/", from = "$ig/p/AAA/")
        // The same reel under a different URL stays allowed (reload, comments, redirect).
        assertEquals(allow(Category.CONTENT, "AAA"), policy.decide(first, first))
        assertEquals(allow(Category.CONTENT, "AAA"), policy.decide("$ig/kamarad/reel/AAA/", first))
        assertEquals(allow(Category.CONTENT, "AAA"), policy.decide("$ig/p/AAA/comments/", "$ig/p/AAA/"))
        assertEquals(allow(Category.REELS_VIEWER, "AAA"), policy.decide("$ig/reels/AAA/", first))
    }

    // --- PLAN.md table: profiles (optional) ---------------------------------------------

    @Test
    fun profilesAreBlockedByDefault() {
        assertEquals(Decision.Block(BlockReason.PROFILES_DISABLED), policy.decide("$ig/kamarad/", thread))
        assertEquals(Decision.Block(BlockReason.PROFILES_DISABLED), policy.decide("$ig/ka.ma_rad99", home))
    }

    @Test
    fun profilesCanBeEnabled() {
        assertEquals(allow(Category.PROFILE), withProfiles.decide("$ig/kamarad/", thread))
        // A post opened from a profile grid is content picked by the user, not by the algorithm.
        assertEquals(allow(Category.CONTENT, "Cxyz"), withProfiles.decide("$ig/p/Cxyz/", "$ig/kamarad/"))
        // Still not from the home page, and never the blocked sections.
        assertBlocked("$ig/p/Cxyz/", from = home, p = withProfiles)
        assertBlocked("$ig/explore/", p = withProfiles)
        assertBlocked("$ig/reels/", p = withProfiles)
        // Profile sub-pages (their reels tab, tagged, followers) stay blocked.
        assertBlocked("$ig/kamarad/reels/", from = "$ig/kamarad/", p = withProfiles)
        assertBlocked("$ig/kamarad/followers/", from = "$ig/kamarad/", p = withProfiles)
    }

    @Test
    fun reservedWordsAreNeverProfiles() {
        for (segment in listOf("explore", "reels", "reel", "p", "about", "notifications", "search", "legal", "api")) {
            assertBlocked("$ig/$segment/", p = withProfiles)
            assertBlocked("$ig/${segment.uppercase()}/", p = withProfiles)
        }
    }

    // --- PLAN.md table: reels tab, explore and everything else --------------------------

    @Test
    fun reelsTabAndExploreAreBlocked() {
        for (path in listOf("/reels/", "/reels", "/explore/", "/explore", "/explore/search/", "/explore/tags/kocky/", "/explore/locations/1/")) {
            assertEquals(path, Decision.Block(BlockReason.ROUTE_BLOCKED), policy.decide(ig + path, home))
            assertEquals(path, Decision.Block(BlockReason.ROUTE_BLOCKED), policy.decide(ig + path, thread))
        }
    }

    @Test
    fun unknownRoutesAreBlocked() {
        for (path in listOf(
            "/notifications/",
            "/your_activity/",
            "/reels/audio/123/",
            "/kamarad/reels/",
            "/kamarad/tagged/",
            "/something/deeper/here/",
            "/web/search/topsearch/",
            "//explore/",
            "/stories/",
        )) {
            assertBlocked(ig + path)
        }
    }

    @Test
    fun pathTricksAreBlocked() {
        assertBlocked("$ig/direct/../explore/")
        assertBlocked("$ig/direct/./../reels/")
        assertBlocked("$ig/%65xplore/")
        assertBlocked("$ig/direct/%2e%2e/explore/")
        assertBlocked("$ig\\explore\\")
        assertBlocked("not a url")
        assertBlocked("")
        assertBlocked("/explore/")
    }

    // --- Hosts and schemes --------------------------------------------------------------

    @Test
    fun otherSitesOpenInTheBrowser() {
        assertEquals(Decision.External, policy.decide("https://example.com/", thread))
        assertEquals(Decision.External, policy.decide("https://l.instagram.com/?u=https%3A%2F%2Fexample.com", thread))
        assertEquals(Decision.External, policy.decide("https://help.instagram.com/", home))
        assertEquals(Decision.External, policy.decide("https://www.instagram.com.evil.example/direct/inbox/", home))
        assertEquals(Decision.External, policy.decide("https://www.instagram.com@evil.example/direct/inbox/", home))
        assertEquals(Decision.External, policy.decide("https://evil.example/?x=https://www.instagram.com/direct/", home))
        assertEquals(Decision.External, policy.decide("https://evil.example#@www.instagram.com/direct/", home))
        // Facebook is reachable only for its login pages.
        assertEquals(Decision.External, policy.decide("https://www.facebook.com/", home))
        assertEquals(Decision.External, policy.decide("https://www.facebook.com/watch/", home))
    }

    @Test
    fun loginPagesOfTrustedHostsStayInTheApp() {
        assertEquals(allow(Category.AUTH), policy.decide("https://www.facebook.com/login.php?skip_api_login=1", home))
        assertEquals(allow(Category.AUTH), policy.decide("https://m.facebook.com/v19.0/dialog/oauth?client_id=1", home))
        assertEquals(allow(Category.AUTH), policy.decide("https://accountscenter.instagram.com/password_and_security/", home))
    }

    @Test
    fun appDeepLinksAreIgnored() {
        assertEquals(Decision.Ignore, policy.decide("intent://instagram.com/_n/mainfeed#Intent;scheme=https;end", home))
        assertEquals(Decision.Ignore, policy.decide("instagram://reels", home))
        assertEquals(Decision.Ignore, policy.decide("market://details?id=com.instagram.android", home))
        assertEquals(Decision.Ignore, policy.decide("about:blank", home))
        assertEquals(Decision.Ignore, policy.decide("javascript:alert(1)", home))
    }

    @Test
    fun plainHttpAndPortsAreClassifiedLikeHttps() {
        assertEquals(allow(Category.HOME), policy.decide("http://www.instagram.com/", null))
        assertEquals(allow(Category.DIRECT), policy.decide("https://www.instagram.com:443/direct/inbox/", null))
        assertBlocked("http://www.instagram.com/explore/")
    }

    // --- Rules file ---------------------------------------------------------------------

    @Test
    fun rulesExposeTheUiSectionForTheScript() {
        assertTrue(rules.uiJson().contains("storyTray"))
        assertEquals(home, policy.homeUrl)
        assertEquals(inbox, policy.directUrl)
        assertTrue(policy.isAppHost("WWW.instagram.com"))
        assertFalse(policy.isAppHost("evil.example"))
        assertFalse(policy.isAppHost(null))
    }

    @Test
    fun brokenRulesAreRejected() {
        assertNull(Rules.parseOrNull(""))
        assertNull(Rules.parseOrNull("{}"))
        assertNull(Rules.parseOrNull("<html>404</html>"))
        val text = File(System.getProperty("antiscrol.rulesFile")).readText()
        assertNotNull(Rules.parseOrNull(text))
        // Unknown schema.
        assertNull(Rules.parseOrNull(text.replaceFirst("\"schema\": 1", "\"schema\": 2")))
        // An invalid regular expression.
        assertNull(Rules.parseOrNull(text.replace("\"^/\$\"", "\"^/(\$\"")))
        // Rules that would make the home page unreachable.
        assertNull(Rules.parseOrNull(text.replace("\"category\": \"HOME\"", "\"category\": \"BLOCKED\"")))
        // Rules that would make the messages unreachable.
        assertNull(Rules.parseOrNull(text.replace("\"category\": \"DIRECT\"", "\"category\": \"BLOCKED\"")))
    }

    @Test
    fun unknownCategoriesAndKeysAreTolerated() {
        val text = File(System.getProperty("antiscrol.rulesFile")).readText()
            .replaceFirst("\"routes\": [", "\"futureKey\": true, \"routes\": [{\"category\": \"FUTURE\", \"patterns\": [\"^/future/\$\"]},")
        val parsed = Rules.parse(text)
        assertBlocked("$ig/future/", p = UrlPolicy(parsed.policy))
    }
}
