package com.pilot.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

/**
 * Agent 的浏览器渲染引擎（「页面尽量不占主屏」第一步）。
 *
 * 一个进程内**唯一的 WebView**，可被两种表面承载：
 * - 无头/后台：不挂到任何窗口，仅用于加载与提取（不占屏、不顶掉用户当前 App）；
 * - 悬浮小窗：[BrowserOverlay] 把同一个 WebView 放进悬浮窗容器。
 *
 * 复用时状态连续：cookie 由系统 CookieManager 共享；当前 URL/title 由本对象记录，
 * 切到全屏 [BrowserActivity] 或从小窗收回时都按 [currentUrl] 续上同一页面。
 *
 * 注意：所有 WebView 操作都在主线程；未授予悬浮窗权限时上层应退回无头/全屏。
 */
object BrowserEngine {

    const val TAG = "PilotBrowser"

    private val main = Handler(Looper.getMainLooper())
    private val navSeq = AtomicLong(0)

    @Volatile
    private var webView: WebView? = null

    /** 无头模式下的“假宿主”，给 WebView 一个尺寸，保证它按正常视口布局/渲染。 */
    private var holder: ViewGroup? = null

    @Volatile
    private var navWaiter: CompletableDeferred<Boolean>? = null

    @Volatile
    private var navId: Long = -1

    @Volatile
    private var lastError: String? = null

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var currentTitle: String = ""
        private set

    @Volatile
    var gone: Boolean = false
        private set

    fun isLoaded(): Boolean = webView != null

    /** 供 [BrowserOverlay] 摘取同一个 WebView 放进悬浮窗容器。 */
    fun takeWebView(): WebView? = webView

    /** 从悬浮窗容器摘回“假宿主”，小窗隐藏后仍可继续无头提取。 */
    fun releaseToHolder() {
        val wv = webView ?: return
        val h = holder ?: return
        main.post {
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                h.removeView(wv)
                h.addView(wv, ViewGroup.LayoutParams(h.width, h.height))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(ctx: Context): WebView {
        webView?.let { if (!gone) return it }
        val app = ctx.applicationContext ?: ctx
        val h = android.widget.FrameLayout(app).apply {
            // 800x1200 的虚拟视口，接近手机竖屏，避免 inline 脚本按 0 宽度布局。
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 800, 1200)
        }
        holder = h
        val wv = WebView(app).apply {
            layoutParams = ViewGroup.LayoutParams(h.width, h.height)
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url != null) currentUrl = url
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null) currentUrl = url
                    navWaiter?.complete(true)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        lastError = error?.description?.toString() ?: "加载错误"
                        navWaiter?.complete(false)
                    }
                }
            }
        }
        h.addView(wv, ViewGroup.LayoutParams(h.width, h.height))
        webView = wv
        gone = false
        return wv
    }

    /** 把 WebView 放进指定容器（悬浮窗）。必须在主线程。 */
    fun attachTo(container: ViewGroup) {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.removeView(wv)
        container.addView(wv, ViewGroup.LayoutParams(-1, -1))
    }

    suspend fun load(ctx: Context, url: String): BrowserResult = withContext(Dispatchers.Main) {
        val wv = ensure(ctx)
        val id = navSeq.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        navWaiter = deferred
        navId = id
        lastError = null
        runCatching { wv.loadUrl(url) }.onFailure {
            navWaiter = null
            return@withContext BrowserResult(false, "网页加载失败：${it.message?.take(80) ?: "无法加载"}", url = url)
        }
        val ok = withTimeoutOrNull(15_000L) { deferred.await() } ?: false
        if (navId == id) navWaiter = null
        if (!ok) {
            return@withContext BrowserResult(
                false,
                "网页加载失败：${lastError ?: "加载超时"}（$url）",
                url = currentUrl ?: url,
            )
        }
        val title = evalJs(wv, "document.title")?.trim().orEmpty()
        currentUrl = evalJs(wv, "location.href")?.trim()?.takeIf { it.isNotEmpty() } ?: url
        currentTitle = title
        BrowserResult(true, "已加载「${title.ifBlank { currentUrl ?: url }}」", title, currentUrl ?: url)
    }

    suspend fun read(maxChars: Int): BrowserResult {
        val wv = webView ?: return notOpen()
        val text = evalJs(
            wv,
            "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()",
        ).orEmpty()
        return if (text.isBlank()) {
            BrowserResult(false, "页面已打开，但没有可读正文（可能是空白页或需要登录）。", currentTitle, currentUrl.orEmpty())
        } else {
            BrowserResult(true, text.take(maxChars), currentTitle, currentUrl.orEmpty(), text.take(16_000))
        }
    }

    /**
     * 截取当前页面画面（移植 Minis 的虚拟屏效果）。
     *
     * 优先走 [HeadlessRenderHost] 的 GPU 离屏渲染：私有虚拟屏 + Presentation 给无头 WebView
     * 真实合成表面，能截到 WebGL / video 等 GPU-only 层，**普通签名即可、无需任何权限**。
     * GPU 路径不适用或失败时退回软件 draw（CPU 光栅化，GPU 层可能空白）。
     *
     * @return 页面 Bitmap；WebView 未加载时返回 null。
     */
    suspend fun screenshot(): Bitmap? {
        val wv = webView ?: return null
        val w = holder?.width?.takeIf { it > 0 } ?: 800
        val h = holder?.height?.takeIf { it > 0 } ?: 1200
        // GPU 路径只在 WebView 未挂窗（无头）时适用；已挂悬浮窗/全屏时它返回 null。
        HeadlessRenderHost.capture(wv, w, h)?.let { return it }
        return withContext(Dispatchers.Main) {
            runCatching {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                wv.draw(canvas)
                bmp
            }.getOrNull()
        }
    }

    suspend fun click(target: String): BrowserResult {
        val wv = webView ?: return notOpen()
        val q = jsString(target)
        val r = evalJs(
            wv,
            "(function(){var q=$q;var els=Array.prototype.slice.call(document.querySelectorAll('a,button,input,[role=button]'));" +
                "var el=els.find(function(e){return (e.innerText||e.value||e.getAttribute('aria-label')||'').trim().indexOf(q)>=0});" +
                "if(!el){var all=Array.prototype.slice.call(document.querySelectorAll('*'));" +
                "el=all.find(function(e){return (e.children.length===0&&(e.innerText||'').trim().indexOf(q)>=0)});}" +
                "if(!el)return 'no-match';try{el.click();return 'clicked'}catch(e){return 'err:'+e}})()",
        ).orEmpty()
        return when {
            r.startsWith("clicked") -> BrowserResult(true, "已点击「$target」", currentTitle, currentUrl.orEmpty())
            r.startsWith("no-match") -> BrowserResult(false, "页面上没有找到「$target」")
            else -> BrowserResult(false, "点击「$target」失败：${r.take(60)}")
        }
    }

    suspend fun type(target: String, text: String): BrowserResult {
        val wv = webView ?: return notOpen()
        val q = jsString(target)
        val v = jsString(text)
        val r = evalJs(
            wv,
            "(function(){var q=$q;var v=$v;var ins=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var el=ins.find(function(e){return (e.name||e.id||e.placeholder||e.getAttribute('aria-label')||'').indexOf(q)>=0})||ins[0];" +
                "if(!el)return 'no-input';el.focus();el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok'})()",
        ).orEmpty()
        return if (r == "ok") BrowserResult(true, "已填写「$target」", currentTitle, currentUrl.orEmpty())
        else BrowserResult(false, "页面上没有可填写的输入框")
    }

    suspend fun scroll(forward: Boolean): BrowserResult {
        val wv = webView ?: return notOpen()
        evalJs(wv, "window.scrollBy(0, ${if (forward) "window.innerHeight*0.8" else "-window.innerHeight*0.8"})")
        return BrowserResult(true, if (forward) "已向下滚动" else "已向上滚动", currentTitle, currentUrl.orEmpty())
    }

    suspend fun back(): BrowserResult {
        val wv = webView ?: return notOpen()
        return withContext(Dispatchers.Main) {
            if (wv.canGoBack()) {
                wv.goBack()
                BrowserResult(true, "已返回上一页", currentTitle, currentUrl.orEmpty())
            } else {
                BrowserResult(false, "没有上一页可返回")
            }
        }
    }

    fun close() {
        main.post {
            val wv = webView
            webView = null
            gone = true
            currentUrl = null
            currentTitle = ""
            runCatching { (wv?.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv?.stopLoading(); wv?.destroy() }
            holder = null
        }
    }

    private fun notOpen(): BrowserResult =
        BrowserResult(false, "当前没有可复用的浏览器页面，请先打开网址。")

    private suspend fun evalJs(wv: WebView, script: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val posted = runCatching {
                wv.evaluateJavascript(script) { raw ->
                    val value = if (raw == null || raw == "null") {
                        null
                    } else {
                        runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.toString() ?: raw
                    }
                    if (cont.isActive) cont.resume(value)
                }
            }
            if (posted.isFailure && cont.isActive) cont.resume(null)
        }
    }

    private fun jsString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
