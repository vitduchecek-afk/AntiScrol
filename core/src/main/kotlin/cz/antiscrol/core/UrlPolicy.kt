package cz.antiscrol.core

enum class Category {
    HOME,
    STORIES,
    DIRECT,
    CREATE,
    AUTH,

    /** A single post or reel page: /p/{id}, /reel/{id}. */
    CONTENT,

    /** The swipeable reels player opened on one reel: /reels/{id}. */
    REELS_VIEWER,
    PROFILE,

    /** Explicitly blocked routes (reels tab, explore). */
    BLOCKED;

    companion object {
        fun fromName(name: String): Category? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class BlockReason {
    ROUTE_BLOCKED,
    UNKNOWN_ROUTE,
    CONTENT_NOT_FROM_DIRECT,
    PROFILES_DISABLED,
    INVALID_URL,
}

sealed interface Decision {
    /** Load inside the app. [contentId] is set for posts and reels. */
    data class Allow(val category: Category, val contentId: String? = null) : Decision

    /** Do not load; show the "blocked" screen. */
    data class Block(val reason: BlockReason) : Decision

    /** Not part of Instagram: hand over to the system browser. */
    data object External : Decision

    /** Silently drop (app deep links such as intent:// or instagram://). */
    data object Ignore : Decision
}

internal data class ParsedUrl(val scheme: String, val host: String, val path: String)

/**
 * Minimal, forgiving URL splitter. java.net.URI rejects many URLs that browsers accept,
 * and a rejected URL must never be mistaken for a safe one.
 */
internal fun parseUrl(raw: String): ParsedUrl? {
    val s = raw.trim()
    val schemeEnd = s.indexOf(':')
    if (schemeEnd <= 0) return null
    val scheme = s.substring(0, schemeEnd).lowercase()
    if (!scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
    if (scheme != "http" && scheme != "https") return ParsedUrl(scheme, "", "")

    var rest = s.substring(schemeEnd + 1)
    if (!rest.startsWith("//")) return null
    rest = rest.substring(2)
    // Browsers treat a backslash like a slash in http(s) URLs.
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
    val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
    val afterAuthority = if (authorityEnd < 0) "" else rest.substring(authorityEnd)

    // "https://www.instagram.com@evil.example/" points at evil.example.
    val hostPort = authority.substringAfterLast('@')
    val host = hostPort.substringBefore(':').lowercase().trimEnd('.')
    if (host.isEmpty()) return null

    val pathEnd = afterAuthority.indexOfFirst { it == '?' || it == '#' }
    val path = (if (pathEnd < 0) afterAuthority else afterAuthority.substring(0, pathEnd)).ifEmpty { "/" }
    return ParsedUrl(scheme, host, path)
}

/**
 * Decides what may be shown. Pure Kotlin, no Android dependency.
 *
 * The one context-dependent rule: a post or reel is allowed only when it is opened from
 * the messages (someone sent it), or - with profiles enabled - from a profile page.
 */
class UrlPolicy(private val rules: PolicyRules, val profilesEnabled: Boolean = false) {

    private class CompiledRoute(val category: Category, val patterns: List<Regex>)

    private data class RouteHit(val category: Category, val contentId: String?)

    private val appHosts: Set<String> = rules.appHosts.map { it.lowercase() }.toSet()

    private val authHosts: Map<String, List<Regex>> =
        rules.authHosts.associate { rule -> rule.host.lowercase() to rule.patterns.map { Regex(it) } }

    // Routes with a category this app version does not know are skipped, so that a newer
    // rules file still works with an older app.
    private val routes: List<CompiledRoute> = rules.routes.mapNotNull { route ->
        Category.fromName(route.category)?.let { category ->
            CompiledRoute(category, route.patterns.map { Regex(it) })
        }
    }

    private val reservedSegments: Set<String> = rules.reservedSegments.map { it.lowercase() }.toSet()

    val homeUrl: String get() = rules.homeUrl
    val directUrl: String get() = rules.directUrl

    init {
        require(appHosts.isNotEmpty()) { "No app hosts" }
        require(decide(rules.homeUrl, null) == Decision.Allow(Category.HOME)) { "homeUrl is not an allowed home page" }
        require(decide(rules.directUrl, null) == Decision.Allow(Category.DIRECT)) { "directUrl is not an allowed inbox page" }
    }

    fun isAppHost(host: String?): Boolean = host != null && host.lowercase().trimEnd('.') in appHosts

    /**
     * @param targetUrl the URL about to be shown (or already shown)
     * @param currentUrl the last allowed URL the user was on, or null at start
     */
    fun decide(targetUrl: String, currentUrl: String?): Decision {
        val target = parseUrl(targetUrl) ?: return Decision.Block(BlockReason.INVALID_URL)
        if (target.scheme != "http" && target.scheme != "https") return Decision.Ignore

        if (target.host !in appHosts) {
            val authPatterns = authHosts[target.host]
            return if (authPatterns != null && isCleanPath(target.path) && authPatterns.any { it.matches(target.path) }) {
                Decision.Allow(Category.AUTH)
            } else {
                Decision.External
            }
        }

        if (!isCleanPath(target.path)) return Decision.Block(BlockReason.INVALID_URL)
        val hit = classify(target.path) ?: return Decision.Block(BlockReason.UNKNOWN_ROUTE)
        return when (hit.category) {
            Category.BLOCKED -> Decision.Block(BlockReason.ROUTE_BLOCKED)
            Category.CONTENT, Category.REELS_VIEWER ->
                if (contentAllowedFrom(currentUrl, hit.contentId)) {
                    Decision.Allow(hit.category, hit.contentId)
                } else {
                    Decision.Block(BlockReason.CONTENT_NOT_FROM_DIRECT)
                }
            Category.PROFILE ->
                if (profilesEnabled) Decision.Allow(Category.PROFILE) else Decision.Block(BlockReason.PROFILES_DISABLED)
            else -> Decision.Allow(hit.category)
        }
    }

    private fun contentAllowedFrom(currentUrl: String?, contentId: String?): Boolean {
        val current = currentUrl?.let { parseUrl(it) } ?: return false
        if (current.host !in appHosts || !isCleanPath(current.path)) return false
        val currentHit = classify(current.path) ?: return false
        return when (currentHit.category) {
            Category.DIRECT -> true
            // The same post under another URL (reload, comments, /reel/x -> /reels/x), never a different one.
            Category.CONTENT, Category.REELS_VIEWER -> contentId != null && currentHit.contentId == contentId
            Category.PROFILE -> profilesEnabled
            else -> false
        }
    }

    private fun classify(path: String): RouteHit? {
        for (route in routes) {
            for (pattern in route.patterns) {
                val match = pattern.matchEntire(path) ?: continue
                if (route.category == Category.PROFILE) {
                    val firstSegment = path.trim('/').substringBefore('/').lowercase()
                    if (firstSegment.isEmpty() || firstSegment in reservedSegments) continue
                }
                val group = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                return RouteHit(route.category, group)
            }
        }
        return null
    }

    /** Rejects paths a browser would rewrite (so the pattern would match something else than what loads). */
    private fun isCleanPath(path: String): Boolean {
        if (!path.startsWith("/")) return false
        if (path.contains('%') || path.contains('\\')) return false
        return path.split('/').none { it == "." || it == ".." }
    }
}
