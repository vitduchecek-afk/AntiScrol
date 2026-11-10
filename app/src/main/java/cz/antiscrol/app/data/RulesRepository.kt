package cz.antiscrol.app.data

import android.content.Context
import cz.antiscrol.app.BuildConfig
import cz.antiscrol.core.Rules
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RulesSource { BUNDLED, DOWNLOADED }

data class LoadedRules(val rules: Rules, val source: RulesSource)

/**
 * rules.json ships inside the app. A newer version may be downloaded from GitHub Pages;
 * it is used only when it parses, validates and has a higher version number.
 */
class RulesRepository(private val context: Context) {

    private val cacheFile: File get() = File(context.filesDir, "rules.json")

    /** Fast and offline: the bundled rules, or a previously downloaded newer version. */
    fun loadLocal(): LoadedRules {
        val bundled = Rules.parse(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
        val cached = try {
            if (cacheFile.exists()) Rules.parseOrNull(cacheFile.readText()) else null
        } catch (e: Exception) {
            null
        }
        return if (cached != null && cached.version > bundled.version) {
            LoadedRules(cached, RulesSource.DOWNLOADED)
        } else {
            LoadedRules(bundled, RulesSource.BUNDLED)
        }
    }

    /** Returns newer rules, or null when there are none (or the download failed). */
    suspend fun fetchNewer(current: Rules): LoadedRules? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(BuildConfig.RULES_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val text = connection.inputStream.use { readLimited(it) } ?: return@withContext null
                val remote = Rules.parseOrNull(text) ?: return@withContext null
                if (remote.version <= current.version) return@withContext null
                cacheFile.writeText(text)
                LoadedRules(remote, RulesSource.DOWNLOADED)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readLimited(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val ASSET_NAME = "rules.json"
        const val MAX_BYTES = 256 * 1024
    }
}
