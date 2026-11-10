package cz.antiscrol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The rules file (docs/rules.json): bundled in the app as the default and optionally
 * replaced by a newer version downloaded from GitHub Pages.
 *
 * It contains only data (URL patterns, CSS, selectors) and never executable code.
 */
@Serializable
data class Rules(
    val schema: Int = SUPPORTED_SCHEMA,
    val version: Int,
    val policy: PolicyRules,
    /** Passed to the injected script as-is; the script has its own defaults. */
    val ui: JsonObject = JsonObject(emptyMap()),
) {
    fun uiJson(): String = ui.toString()

    companion object {
        const val SUPPORTED_SCHEMA = 1

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and fully validates a rules file. Throws when it is not usable. */
        fun parse(text: String): Rules {
            val rules = json.decodeFromString(serializer(), text)
            require(rules.schema == SUPPORTED_SCHEMA) { "Unsupported rules schema ${rules.schema}" }
            require(rules.version >= 1) { "Invalid rules version ${rules.version}" }
            // Compiles every pattern and checks that the home and inbox URLs stay reachable,
            // so a broken remote file can never lock the user out of the app.
            UrlPolicy(rules.policy)
            return rules
        }

        fun parseOrNull(text: String): Rules? = try {
            parse(text)
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
data class PolicyRules(
    val homeUrl: String = "https://www.instagram.com/",
    val directUrl: String = "https://www.instagram.com/direct/inbox/",
    /** Hosts the route table applies to. */
    val appHosts: List<String>,
    /** Other hosts that may load inside the app, but only on the listed paths (login flows). */
    val authHosts: List<HostRule> = emptyList(),
    /** Ordered; the first matching pattern wins. Paths that match nothing are blocked. */
    val routes: List<Route>,
    /** First path segments that are never treated as a profile name. */
    val reservedSegments: List<String> = emptyList(),
)

@Serializable
data class HostRule(val host: String, val patterns: List<String>)

@Serializable
data class Route(val category: String, val patterns: List<String>)
