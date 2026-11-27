package com.pilot.agent.browser

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import com.pilot.agent.action.LaunchOutcome
import com.pilot.agent.action.VerifiedLauncher
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers

data class BrowserResult(
    val ok: Boolean,
    val message: String,
    val title: String = "",
    val url: String = "",
    val text: String = "",
)

/**
 * One persistent, visible browser session for the assistant.
 *
 * WebView owns the cookies, so a website login survives later browser actions
 * and app restarts until the user clears the browser data.
 */
object BrowserSessionManager {

    /** 弱引用持有（审核 R3）：不再用静态强引用拖住已销毁的 Activity。 */
    @Volatile
    private var activityRef: WeakReference<BrowserActivity>? = null

    /** 只返回仍可用的页面：已 finish / destroy / 渲染进程崩溃的一律视为没有。 */
    private val activity: BrowserActivity?
        get() = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && !it.webViewGone }

    internal fun attach(value: BrowserActivity) {
        activityRef = WeakReference(value)
    }

    internal fun detach(value: BrowserActivity) {
        if (activityRef?.get() === value) activityRef = null
    }

    suspend fun execute(
        context: Context,
        action: String,
        target: String?,
        text: String?,
        maxChars: Int?,
    ): BrowserResult {
        if (action == "browser_open") {
            val url = normaliseHttpUrl(target ?: text)
                ?: return BrowserResult(false, "只允许打开 HTTP 或 HTTPS 网页。")
            return open(context.applicationContext, url)
        }

        val page = activity ?: return BrowserResult(
            false,
            "当前没有可复用的浏览器页面，请先使用 browser_open 打开网址。",
        )
        return when (action) {
            "browser_read" -> page.read((maxChars ?: 6_000).coerceIn(500, 20_000))
            "browser_click" -> page.click(target.orEmpty())
            "browser_type" -> page.type(target.orEmpty(), text.orEmpty())
            "browser_scroll" -> page.scroll(target != "backward")
            "browser_back" -> page.goBack()
            "browser_close" -> page.closeSession()
            else -> BrowserResult(false, "不支持的浏览器操作：$action")
        }
    }

    suspend fun clearSessionData(context: Context) {
        withContext(Dispatchers.Main.immediate) {
            activity?.closeSession()
            activityRef = null
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        context.getSharedPreferences("browser_state", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private suspend fun open(context: Context, url: String): BrowserResult {
        var page = activity
        if (page == null) {
            // 审核 D-01：经 VerifiedLauncher 启动并回查；被后台拦截时如实返回失败。
            val intent = Intent(context, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .setData(android.net.Uri.parse(url))
            val outcome = VerifiedLauncher.launch(context, intent, context.packageName, 1_500L)
            when (outcome) {
                is LaunchOutcome.Blocked ->
                    return BrowserResult(false, "系统拦下了打开 Pilot 浏览器（${outcome.reason.take(60)}），请回到 Pilot 后再试。")
                is LaunchOutcome.Failed ->
                    return BrowserResult(false, "无法启动 Pilot 浏览器会话：${outcome.error.javaClass.simpleName}")
                else -> Unit // Ok / Unknown：以 Activity 是否真的 attach 为准
            }
            withTimeoutOrNull(6_000L) {
                while (activity == null) delay(50L)
            }
            page = activity
        }
        if (page == null) return BrowserResult(false, "浏览器会话没有启动起来（可能被系统的后台限制拦下），这次没有打开网页。")
        return page.navigate(url)
    }

    private fun normaliseHttpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val withScheme = if ("://" in value) value else "https://$value"
        return runCatching {
            val uri = android.net.Uri.parse(withScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") null
            else if (uri.host.isNullOrBlank()) null
            else withScheme
        }.getOrNull()
    }
}
