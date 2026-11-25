package jp.mo64hysy.webshortcut

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.net.URI

class WebActivity : Activity() {
    companion object { const val EXTRA_ID = "entry_id" }
    private lateinit var webView: WebView
    private var entry: WebEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
            ?: intent.data?.lastPathSegment
        entry = id?.let { AppStore.find(this, it) }
        val target = entry
        if (target == null) {
            Toast.makeText(this, "登録情報が見つかりません。元アプリから再登録してください。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        title = target.name
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "http" && scheme != "https"
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectUserScript(url)
            }
        }
        webView.webChromeClient = WebChromeClient()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // loadUrl is called on every shortcut launch; no offline page cache is used.
        webView.loadUrl(target.url)
    }

    private fun injectUserScript(currentUrl: String) {
        val path = entry?.scriptPath ?: return
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists()) return
        val source = runCatching { file.readText() }.getOrNull() ?: return
        val meta = source.substringAfter("// ==UserScript==", "").substringBefore("// ==/UserScript==", "")
        if (meta.isBlank()) return
        val matches = Regex("(?m)^\\s*//\\s*@match\\s+(.+)$").findAll(meta).map { it.groupValues[1].trim() }.toList()
        val includes = Regex("(?m)^\\s*//\\s*@include\\s+(.+)$").findAll(meta).map { it.groupValues[1].trim() }.toList()
        val excludes = Regex("(?m)^\\s*//\\s*@exclude\\s+(.+)$").findAll(meta).map { it.groupValues[1].trim() }.toList()
        val allow = matches + includes
        if (allow.isNotEmpty() && allow.none { patternMatches(it, currentUrl) }) return
        if (excludes.any { patternMatches(it, currentUrl) }) return
        val escaped = source.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
        webView.evaluateJavascript("(function(){try{(function(){`$escaped`}).call(window)}catch(e){console.error('UserScript:',e)}})();", null)
    }

    private fun patternMatches(pattern: String, url: String): Boolean {
        if (pattern == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$")
        return regex.matches(url)
    }

    @Deprecated("Deprecated by Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
