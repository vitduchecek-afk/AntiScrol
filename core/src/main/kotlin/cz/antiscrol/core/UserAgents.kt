package cz.antiscrol.core

object UserAgents {
    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    /**
     * Turns the WebView's default user agent into the one mobile Chrome on the same device
     * sends: drops the "; wv" marker, the "Version/4.0" token and the build id.
     */
    fun chromeLike(defaultUserAgent: String?): String {
        if (defaultUserAgent.isNullOrBlank()) return FALLBACK
        val result = defaultUserAgent
            .replace(Regex(";\\s*wv\\b"), "")
            .replace(Regex("\\s+Version/\\d+(?:\\.\\d+)*"), "")
            .replace(Regex("\\s+Build/[^;)]+"), "")
            .trim()
        return if (result.contains("Chrome/") && !result.contains("wv")) result else FALLBACK
    }
}
