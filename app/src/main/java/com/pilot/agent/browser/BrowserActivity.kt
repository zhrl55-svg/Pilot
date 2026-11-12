package com.pilot.agent.browser

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Visible browser shell. Keeping WebView in an Activity makes permission,
 * login and human take-over natural while still allowing the agent to drive
 * the same DOM and cookie jar over multiple steps.
 */
class BrowserActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private var pageLoaded = CompletableDeferred<Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        BrowserSessionManager.attach(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        savedInstanceState?.getString(STATE_URL)?.let(::load)
            ?: intent?.dataString?.takeIf { it.isNotBlank() }?.let(::load)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.takeIf { it.isNotBlank() }?.let(::load)
    }

    override fun onDestroy() {
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.stopLoading() }
        BrowserSessionManager.detach(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_URL, webView.url)
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 8.dp, 10.dp, 8.dp)
            setBackgroundColor(Color.rgb(12, 18, 28))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PILOT 浏览器"
            setTextColor(Color.rgb(96, 165, 250))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(title)
        topRow.addView(Button(this).apply {
            text = "返回"
            isAllCaps = false
            setOnClickListener {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        topRow.addView(Button(this).apply {
            text = "关闭"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        bar.addView(topRow)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp, 0, 0)
        }
        address = EditText(this).apply {
            hint = "输入网址，例如 example.com"
            setHintTextColor(Color.rgb(100, 116, 139))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 30, 44))
                cornerRadius = 10f * resources.displayMetrics.density
                setStroke(1.dp, Color.rgb(51, 65, 85))
            }
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openAddress()
                    true
                } else {
                    false
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    openAddress()
                    true
                } else {
                    false
                }
            }
        }
        addressRow.addView(address)
        addressRow.addView(Button(this).apply {
            text = "打开"
            isAllCaps = false
            setOnClickListener { openAddress() }
        })
        bar.addView(addressRow)
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = ProgressBar.GONE
        }
        root.addView(progress)
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.rgb(8, 10, 15))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                userAgentString = "$userAgentString PilotAgent/1.0"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        Toast.makeText(
                            this@BrowserActivity,
                            "浏览器会话只允许 HTTP/HTTPS 页面",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    this@BrowserActivity.progress.visibility = ProgressBar.GONE
                    updateAddress(url)
                    if (!pageLoaded.isCompleted) pageLoaded.complete(true)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@BrowserActivity.progress.progress = newProgress
                    this@BrowserActivity.progress.visibility =
                        if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
                }
            }
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(
                    this@BrowserActivity,
                    "下载链接未执行，请使用系统浏览器处理下载",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            addJavascriptInterface(SafeBridge(), "Pilot")
        }
        root.addView(webView)
        return root
    }

    suspend fun navigate(target: String): BrowserResult = runBlockingMain {
        load(target)
        val loaded = withTimeoutOrNull(12_000L) { pageLoaded.await() } ?: false
        val result = read(20_000)
        result.copy(
            ok = true,
            message = if (loaded) {
                "已打开 ${result.title.ifBlank { result.url }}"
            } else {
                "网页仍在加载，已打开 ${target}"
            },
        )
    }

    suspend fun read(maxChars: Int): BrowserResult = runBlockingMain {
        val js = """
            (function(){
              var body = document.body;
              var text = body ? (body.innerText || "") : "";
              text = text.replace(/\u00a0/g, " ").replace(/[ \t]+\n/g, "\n").trim();
              return JSON.stringify({
                url: location.href || "",
                title: document.title || "",
                text: text.slice(0, $maxChars)
              });
            })()
        """.trimIndent()
        val raw = decodeJsString(evaluate(js))
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            return@runBlockingMain BrowserResult(false, "网页正文读取失败：${it.message}")
        }
        BrowserResult(
            ok = true,
            message = "已读取网页正文",
            title = obj.optString("title"),
            url = obj.optString("url"),
            text = obj.optString("text"),
        )
    }

    suspend fun click(locator: String): BrowserResult = runBlockingMain {
        if (locator.isBlank()) {
            return@runBlockingMain BrowserResult(false, "点击需要文本或 CSS 选择器。")
        }
        val q = JSONObject.quote(locator)
        val js = """
            (function(){
              var locator = $q;
              var el = null;
              if (locator.indexOf("css:") === 0) {
                el = document.querySelector(locator.slice(4));
              } else {
                var nodes = Array.from(document.querySelectorAll(
                  "a,button,input,[role=button],summary,label,[onclick]"
                ));
                var wanted = locator.trim().toLowerCase();
                el = nodes.find(function(n){
                  var s = ((n.innerText || n.value || n.getAttribute("aria-label") ||
                            n.getAttribute("title") || "") + "").trim().toLowerCase();
                  return s === wanted;
                }) || nodes.find(function(n){
                  var s = ((n.innerText || n.value || n.getAttribute("aria-label") ||
                            n.getAttribute("title") || "") + "").trim().toLowerCase();
                  return s.indexOf(wanted) >= 0;
                });
              }
              if (!el) return JSON.stringify({ok:false,error:"not_found"});
              el.scrollIntoView({block:"center", inline:"center"});
              el.focus && el.focus();
              el.click();
              return JSON.stringify({ok:true,label:(el.innerText || el.value || locator).slice(0,80)});
            })()
        """.trimIndent()
        val result = JSONObject(decodeJsString(evaluate(js)))
        BrowserResult(
            ok = result.optBoolean("ok"),
            message = if (result.optBoolean("ok")) {
                "已点击「${result.optString("label").take(80)}」"
            } else {
                "网页中没找到「${locator.take(80)}」"
            },
            url = webView.url.orEmpty(),
            title = webView.title.orEmpty(),
        )
    }

    suspend fun type(locator: String, value: String): BrowserResult = runBlockingMain {
        if (locator.isBlank()) {
            return@runBlockingMain BrowserResult(false, "输入需要文本或 CSS 选择器。")
        }
        val q = JSONObject.quote(locator)
        val v = JSONObject.quote(value)
        val js = """
            (function(){
              var locator = $q, value = $v, el = null;
              if (locator.indexOf("css:") === 0) {
                el = document.querySelector(locator.slice(4));
              } else {
                var nodes = Array.from(document.querySelectorAll(
                  "input,textarea,[contenteditable=true]"
                ));
                var wanted = locator.trim().toLowerCase();
                el = nodes.find(function(n){
                  var s = (n.getAttribute("placeholder") || n.getAttribute("name") ||
                           n.getAttribute("aria-label") || n.getAttribute("title") || "")
                           .trim().toLowerCase();
                  return s === wanted;
                }) || nodes.find(function(n){
                  var s = (n.getAttribute("placeholder") || n.getAttribute("name") ||
                           n.getAttribute("aria-label") || n.getAttribute("title") || "")
                           .trim().toLowerCase();
                  return s.indexOf(wanted) >= 0;
                });
              }
              if (!el) return JSON.stringify({ok:false,error:"not_found"});
              el.focus && el.focus();
              if (el.isContentEditable) {
                el.textContent = value;
              } else {
                var proto = el instanceof HTMLTextAreaElement
                  ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, "value").set;
                setter.call(el, value);
              }
              el.dispatchEvent(new Event("input", {bubbles:true}));
              el.dispatchEvent(new Event("change", {bubbles:true}));
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
        val result = JSONObject(decodeJsString(evaluate(js)))
        BrowserResult(
            ok = result.optBoolean("ok"),
            message = if (result.optBoolean("ok")) "已填写网页输入框"
            else "网页中没找到输入框「${locator.take(80)}」",
            url = webView.url.orEmpty(),
            title = webView.title.orEmpty(),
        )
    }

    suspend fun scroll(forward: Boolean): BrowserResult = runBlockingMain {
        val factor = if (forward) "0.82" else "-0.82"
        val raw = decodeJsString(
            evaluate("window.scrollBy({top: window.innerHeight * $factor, behavior:'smooth'}); true"),
        )
        BrowserResult(
            ok = raw != "null",
            message = if (forward) "网页已向下滚动" else "网页已向上滚动",
            url = webView.url.orEmpty(),
            title = webView.title.orEmpty(),
        )
    }

    suspend fun goBack(): BrowserResult = runBlockingMain {
        if (!webView.canGoBack()) {
            return@runBlockingMain BrowserResult(false, "网页已经是第一页。")
        }
        pageLoaded = CompletableDeferred()
        webView.goBack()
        BrowserResult(true, "网页已后退", url = webView.url.orEmpty(), title = webView.title.orEmpty())
    }

    suspend fun closeSession(): BrowserResult = runBlockingMain {
        finish()
        BrowserResult(true, "浏览器会话已关闭，登录 Cookie 仍保留。")
    }

    private fun openAddress() {
        val raw = address.text.toString().trim()
        if (raw.isEmpty()) {
            address.requestFocus()
            Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show()
            return
        }
        val target = normaliseAddress(raw)
        if (target == null) {
            Toast.makeText(this, "请输入 HTTP/HTTPS 网址", Toast.LENGTH_SHORT).show()
            return
        }
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        load(target)
    }

    private fun normaliseAddress(raw: String): String? {
        val value = raw.trim()
        val withScheme = if ("://" in value) value else "https://$value"
        return runCatching {
            val uri = android.net.Uri.parse(withScheme)
            val scheme = uri.scheme?.lowercase()
            when {
                scheme != "http" && scheme != "https" -> null
                uri.host.isNullOrBlank() -> null
                else -> withScheme
            }
        }.getOrNull()
    }

    private fun updateAddress(url: String) {
        if (url.isBlank() || address.hasFocus()) return
        address.setText(url)
        address.setSelection(address.text.length)
    }

    private fun load(target: String) {
        val uri = android.net.Uri.parse(target)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(this, "只允许 HTTP/HTTPS 页面", Toast.LENGTH_SHORT).show()
            return
        }
        pageLoaded = CompletableDeferred()
        address.setText(target)
        address.setSelection(address.text.length)
        webView.loadUrl(target)
    }

    private suspend fun evaluate(script: String): String =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value ?: "null")
                }
            }
        }

    private suspend fun <T> runBlockingMain(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    private fun decodeJsString(raw: String): String = runCatching {
        val value = org.json.JSONTokener(raw).nextValue()
        value as? String ?: raw
    }.getOrElse { raw }

    private inner class SafeBridge {
        @JavascriptInterface
        fun currentUrl(): String = webView.url.orEmpty()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_URL = "browser_url"
    }
}
