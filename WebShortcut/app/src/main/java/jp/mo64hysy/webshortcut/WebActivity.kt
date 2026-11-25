package jp.mo64hysy.webshortcut

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class WebActivity : Activity() {
    companion object { const val EXTRA_ID = "entry_id" }
    private lateinit var webView: WebView
    private var entry: WebEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: intent.data?.lastPathSegment
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
        // Keep web content inside the safe area around system bars and display cutouts.
        webView.setOnApplyWindowInsetsListener { view, insets ->
            val safe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        webView.requestApplyInsets()
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
        // Always navigate to the registered URL when this shortcut window is created.
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

        // JSON quoting safely transports arbitrary script text into evaluateJavascript.
        val quotedScript = JSONObject.quote(source)
        webView.evaluateJavascript(
            "(function(){try{(0,eval)($quotedScript)}catch(e){console.error('UserScript:',e)}})();",
            null
        )
    }

    private fun patternMatches(pattern: String, url: String): Boolean {
        if (pattern == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
        val regexPattern = buildString {
            pattern.forEach { ch ->
                if (ch == '*') append(".*") else append(Regex.escape(ch.toString()))
            }
        }
        return runCatching { Regex("^$regexPattern$").matches(url) }.getOrDefault(false)
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
