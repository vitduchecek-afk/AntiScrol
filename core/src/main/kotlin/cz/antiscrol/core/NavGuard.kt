package cz.antiscrol.core

/**
 * Tracks where the user currently is and applies the [UrlPolicy] to every navigation.
 *
 * Called from the WebView's JavaScript bridge thread and from the main thread, so it is
 * thread-safe. Listener callbacks run on the calling thread.
 */
class NavGuard(
    policy: UrlPolicy,
    private val isLoggedIn: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** The page at [url] is now shown and is allowed. */
        fun onAllowed(url: String, decision: Decision.Allow)

        /** A blocked page was requested but did not load (the user is still on an allowed page). */
        fun onBlockedAttempt(url: String)

        /** A blocked page is already in the WebView and must be navigated away from. */
        fun onBlockedCommitted(url: String)
    }

    @Volatile
    var policy: UrlPolicy = policy

    /** The last allowed URL that was actually shown. */
    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var currentCategory: Category? = null
        private set

    private var lastBlockedCommit: String? = null

    /** Would [url] be allowed right now? No side effects. */
    fun check(url: String): Decision = policy.decide(url, currentUrl)

    /** [url] has become the WebView's location. */
    @Synchronized
    fun commit(url: String): Decision {
        val decision = policy.decide(url, currentUrl)
        when (decision) {
            is Decision.Allow -> {
                currentUrl = url
                currentCategory = decision.category
                lastBlockedCommit = null
                listener.onAllowed(url, decision)
            }
            is Decision.Block, Decision.External -> {
                // Reported both by the injected script and by the WebView itself: react once.
                if (lastBlockedCommit != url) {
                    lastBlockedCommit = url
                    listener.onBlockedCommitted(url)
                }
            }
            Decision.Ignore -> Unit
        }
        return decision
    }

    fun reportBlockedAttempt(url: String) {
        listener.onBlockedAttempt(url)
    }

    @Synchronized
    fun reset() {
        currentUrl = null
        currentCategory = null
        lastBlockedCommit = null
    }

    /**
     * The answer handed to the injected script:
     * "allow:<mode>[:<contentId>]", "block", "external" or "ignore".
     */
    fun encode(decision: Decision): String = when (decision) {
        is Decision.Allow -> when (decision.category) {
            // Logged out, "/" is the login page: it must stay scrollable and uncovered.
            Category.HOME -> if (isLoggedIn()) "allow:home" else "allow:plain"
            Category.STORIES -> "allow:stories"
            Category.DIRECT -> "allow:direct"
            Category.CREATE -> "allow:create"
            Category.PROFILE -> "allow:profile"
            Category.CONTENT -> "allow:content:" + (decision.contentId ?: "")
            Category.REELS_VIEWER -> "allow:reelsViewer:" + (decision.contentId ?: "")
            Category.AUTH, Category.BLOCKED -> "allow:plain"
        }
        is Decision.Block -> "block"
        Decision.External -> "external"
        Decision.Ignore -> "ignore"
    }
}
