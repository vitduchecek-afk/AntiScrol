package cz.antiscrol.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cz.antiscrol.core.Category
import cz.antiscrol.core.Decision
import cz.antiscrol.core.NavGuard
import cz.antiscrol.core.Rules
import cz.antiscrol.core.UrlPolicy
import cz.antiscrol.core.UserAgents

enum class Section { STORIES, MESSAGES, ADD_STORY }

/**
 * Owns the WebView and enforces the URL policy on it.
 *
 * Three independent layers keep blocked pages away:
 *  1. shouldOverrideUrlLoading - full page loads and redirects,
 *  2. the injected script - in-page (history.pushState) navigation, asked before it happens,
 *  3. doUpdateVisitedHistory - whatever still got through is detected and navigated away from.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebController(
    context: Context,
    private val host: Host,
    initialRules: Rules,
    profilesEnabled: Boolean,
) {
    interface Host {
        fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?)
        fun onWebPermissionRequest(request: PermissionRequest)
        fun openExternal(url: String)
        fun showMessage(text: String)
    }

    /** The "Tohle je zablokované" screen is showing. */
    var blockedVisible by mutableStateOf(false)
        private set

    /** Which tab of the bottom bar the current page belongs to. */
    var section by mutableStateOf(Section.STORIES)
        private set

    /** Page load progress, 0-100. */
    var progress by mutableIntStateOf(0)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val script: String = context.assets.open("antiscrol.js").bufferedReader().use { it.readText() }

    @Volatile
    private var rules: Rules = initialRules

    @Volatile
    private var loginCheckedAt = 0L

    @Volatile
    private var loginCached = false

    private var pendingAddStory = false
    private var documentStartScript = false

    val webView: WebView = WebView(context)

    private val guard = NavGuard(
        policy = UrlPolicy(initialRules.policy, profilesEnabled),
        isLoggedIn = ::isLoggedIn,
        listener = object : NavGuard.Listener {
            override fun onAllowed(url: String, decision: Decision.Allow) {
                mainHandler.post { section = sectionOf(decision.category) }
            }

            override fun onBlockedAttempt(url: String) {
                mainHandler.post {
                    blockedVisible = true
                    // The page asked for a blocked route and was refused, so its own state may be
                    // inconsistent. Reload the page the user is really on (hidden by the overlay).
                    webView.reload()
                }
            }

            override fun onBlockedCommitted(url: String) {
                mainHandler.post {
                    blockedVisible = true
                    leaveBlockedPage()
                }
            }
        },
    )

    init {
        configure()
    }

    // ------------------------------------------------------------------ public API

    fun start() {
        webView.loadUrl(guard.policy.homeUrl)
    }

    fun openStories() {
        blockedVisible = false
        pendingAddStory = false
        webView.loadUrl(guard.policy.homeUrl)
    }

    fun openMessages() {
        blockedVisible = false
        pendingAddStory = false
        webView.loadUrl(guard.policy.directUrl)
    }

    /** Goes to the stories row and tries to tap the page's own "your story" control. */
    fun addStory() {
        blockedVisible = false
        if (guard.currentCategory == Category.HOME && progress >= 100) {
            triggerAddStory()
        } else {
            pendingAddStory = true
            webView.loadUrl(guard.policy.homeUrl)
        }
    }

    /**
     * Back button. Steps back to the nearest history entry that is allowed from here, so it
     * can never end on a blocked page. Returns false when there is nowhere to go back to.
     */
    fun handleBack(): Boolean {
        if (blockedVisible) {
            blockedVisible = false
            return true
        }
        return goBackToAllowed()
    }

    /** The button on the blocked screen. */
    fun dismissBlockedToStories() {
        blockedVisible = false
        pendingAddStory = false
        if (guard.currentCategory != Category.HOME) webView.loadUrl(guard.policy.homeUrl)
    }

    fun setProfilesEnabled(enabled: Boolean) {
        if (guard.policy.profilesEnabled == enabled) return
        guard.policy = UrlPolicy(rules.policy, enabled)
        // If the user is on a profile right now, the reload makes the policy catch it.
        webView.reload()
    }

    fun updateRules(newRules: Rules) {
        rules = newRules
        guard.policy = UrlPolicy(newRules.policy, guard.policy.profilesEnabled)
        webView.evaluateJavascript("window.__antiscrol&&window.__antiscrol.refresh()", null)
    }

    fun isAppHost(hostName: String?): Boolean = guard.policy.isAppHost(hostName)

    /** Signs out by deleting everything the WebView stored. */
    fun clearAllData(onDone: () -> Unit) {
        blockedVisible = false
        pendingAddStory = false
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            loginCheckedAt = 0L
            guard.reset()
            webView.clearHistory()
            webView.loadUrl(guard.policy.homeUrl)
            onDone()
        }
    }

    fun onResume() {
        webView.onResume()
    }

    fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // ------------------------------------------------------------------ internals

    private fun sectionOf(category: Category): Section = when (category) {
        Category.DIRECT, Category.CONTENT, Category.REELS_VIEWER -> Section.MESSAGES
        Category.CREATE -> Section.ADD_STORY
        else -> Section.STORIES
    }

    /** Called from the JavaScript bridge thread, hence the short cache. */
    private fun isLoggedIn(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - loginCheckedAt < 1000) return loginCached
        val cookies = try {
            CookieManager.getInstance().getCookie(rules.policy.homeUrl)
        } catch (e: Exception) {
            null
        }
        val loggedIn = cookies?.split(';')?.any {
            val name = it.substringBefore('=').trim()
            val value = it.substringAfter('=', "").trim()
            name == "sessionid" && value.isNotEmpty()
        } == true
        loginCached = loggedIn
        loginCheckedAt = now
        return loggedIn
    }

    private fun goBackToAllowed(): Boolean {
        val history = webView.copyBackForwardList()
        val current = history.currentIndex
        for (index in current - 1 downTo 0) {
            val url = history.getItemAtIndex(index)?.url ?: continue
            if (guard.check(url) is Decision.Allow) {
                webView.goBackOrForward(index - current)
                return true
            }
        }
        return false
    }

    private fun leaveBlockedPage() {
        if (!goBackToAllowed()) webView.loadUrl(guard.policy.homeUrl)
    }

    private fun triggerAddStory() {
        pendingAddStory = false
        webView.evaluateJavascript("window.__antiscrol?window.__antiscrol.addStory():false", null)
        host.showMessage("Pokud se výběr fotky neotevřel sám, klepni na svůj kroužek s plusem vlevo v liště stories.")
    }

    private fun inject(view: WebView) {
        // Idempotent: the script returns immediately when it is already installed in the page.
        view.evaluateJavascript(script, null)
    }

    private fun configure() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // The same user agent mobile Chrome on this phone sends (no "wv" marker).
        settings.userAgentString = UserAgents.chromeLike(settings.userAgentString)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Do not send the app's package name with every request.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.addJavascriptInterface(Bridge(), "AntiScrolBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                documentStartScript = true
            } catch (e: Exception) {
                documentStartScript = false
            }
        }

        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
    }

    /** Exposed to the page as `AntiScrolBridge`. Methods run on the WebView's bridge thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun getConfig(): String = rules.uiJson()

        @JavascriptInterface
        fun check(url: String?): String = guard.encode(guard.check(url.orEmpty()))

        @JavascriptInterface
        fun commit(url: String?): String = guard.encode(guard.commit(url.orEmpty()))

        @JavascriptInterface
        fun blocked(url: String?) {
            guard.reportBlockedAttempt(url.orEmpty())
        }

        @JavascriptInterface
        fun report(event: String?, detail: String?) {
            // Diagnostics only (e.g. "safeMode"); nothing leaves the device.
            android.util.Log.d("AntiScrol", "page: $event $detail")
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return when (guard.check(url)) {
                is Decision.Allow -> false
                is Decision.Block -> {
                    blockedVisible = true
                    true
                }
                Decision.External -> {
                    host.openExternal(url)
                    true
                }
                Decision.Ignore -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress = 0
            if (!documentStartScript) inject(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            inject(view)
            CookieManager.getInstance().flush()
            if (pendingAddStory && guard.currentCategory == Category.HOME) {
                pendingAddStory = false
                // Give the page a moment to render the stories row.
                mainHandler.postDelayed({ triggerAddStory() }, 1500)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // Also called for in-page navigation, independently of the injected script.
            if (url != null) guard.commit(url)
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress = newProgress
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null) return false
            host.onShowFileChooser(filePathCallback, fileChooserParams)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            mainHandler.post { host.onWebPermissionRequest(request) }
        }
    }
}
