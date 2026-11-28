package com.pilot.agent.browser

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import com.pilot.agent.agent.AgentSettings
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

/** 网页打开方式（「页面尽量不占主屏」）。 */
enum class BrowserSurface { BACKGROUND, OVERLAY, FULLSCREEN }

/**
 * 助手浏览器的会话入口。
 *
 * 打开方式按设置（默认小窗）+「用户是否要亲眼看」选择：
 * - BACKGROUND：无头 WebView，不占屏、不顶掉当前 App（适合提取正文）；
 * - OVERLAY：悬浮小窗（可拖动/最小化为气泡/展开全屏/关闭），不顶掉当前 App；
 * - FULLSCREEN：全屏 [BrowserActivity]（用户说“打开给我看”或需要登录/扫码时）。
 *
 * cookie 由系统 CookieManager 共享；当前 URL 记录在 [BrowserEngine]，切全屏/收回小窗按它续页。
 * 结果如实回报：加载失败返回 ok=false，不报成功。
 */
object BrowserSessionManager {

    /** 弱引用持有（审核 R3）：不再用静态强引用拖住已销毁的 Activity。 */
    @Volatile
    private var activityRef: WeakReference<BrowserActivity>? = null

    private val activity: BrowserActivity?
        get() = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && !it.webViewGone }

    internal fun attach(value: BrowserActivity) {
        activityRef = WeakReference(value)
    }

    internal fun detach(value: BrowserActivity) {
        if (activityRef?.get() === value) activityRef = null
    }

    /**
     * @param showToUser 用户明确要“看”网页（打开给我看/展示/登录/扫码）→ 直接全屏。
     */
    suspend fun execute(
        context: Context,
        action: String,
        target: String?,
        text: String?,
        maxChars: Int?,
        showToUser: Boolean = false,
    ): BrowserResult {
        val app = context.applicationContext ?: context
        if (action == "browser_open") {
            val url = normaliseHttpUrl(target ?: text)
                ?: return BrowserResult(false, "只允许打开 HTTP 或 HTTPS 网页。")
            return open(app, url, showToUser)
        }
        if (action == "browser_screenshot") {
            // 虚拟屏 GPU 离屏截屏（移植 Minis）：普通签名可用，能截到 WebGL/video 等 GPU 层。
            val bmp = BrowserEngine.screenshot()
                ?: return BrowserResult(false, "当前没有可截屏的浏览器页面，请先打开网址。")
            val file = java.io.File(app.cacheDir, "browser_shot.jpg")
            val ok = runCatching {
                java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            }.isSuccess
            bmp.recycle()
            return if (ok) {
                BrowserResult(true, "已截取当前页面画面", text = "截图文件：${file.absolutePath}")
            } else {
                BrowserResult(false, "截屏保存失败")
            }
        }
        // 非打开类动作：优先作用于当前活动的全屏页，其次引擎（小窗/无头共用同一 WebView）。
        val page = activity
        if (page != null) {
            return when (action) {
                "browser_read" -> page.read((maxChars ?: 6_000).coerceIn(500, 20_000))
                "browser_click" -> page.click(target.orEmpty())
                "browser_type" -> page.type(target.orEmpty(), text.orEmpty())
                "browser_scroll" -> page.scroll(target != "backward")
                "browser_back" -> page.goBack()
                "browser_close" -> { page.closeSession(); BrowserEngine.close(); BrowserResult(true, "已关闭浏览器") }
                else -> BrowserResult(false, "不支持的浏览器操作：$action")
            }
        }
        return when (action) {
            "browser_read" -> BrowserEngine.read((maxChars ?: 6_000).coerceIn(500, 20_000))
            "browser_click" -> BrowserEngine.click(target.orEmpty())
            "browser_type" -> BrowserEngine.type(target.orEmpty(), text.orEmpty())
            "browser_scroll" -> BrowserEngine.scroll(target != "backward")
            "browser_back" -> BrowserEngine.back()
            "browser_close" -> {
                withContext(Dispatchers.Main) { BrowserOverlay.close() }
                BrowserEngine.close()
                BrowserResult(true, "已关闭浏览器")
            }
            else -> BrowserResult(false, "不支持的浏览器操作：$action")
        }
    }

    suspend fun clearSessionData(context: Context) {
        withContext(Dispatchers.Main.immediate) {
            activity?.closeSession()
            activityRef = null
            runCatching { BrowserOverlay.close() }
            BrowserEngine.close()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        context.getSharedPreferences("browser_state", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private suspend fun open(context: Context, url: String, showToUser: Boolean): BrowserResult {
        val setting = runCatching { AgentSettings.load().browserMode }.getOrDefault(AgentSettings.BROWSER_MODE_OVERLAY)
        val surface = resolveSurface(context, showToUser, setting)

        // 已有全屏页：直接在其内跳转，不另开表面。
        activity?.let { page -> return page.navigate(url) }

        return when (surface.surface) {
            BrowserSurface.BACKGROUND -> {
                val loaded = BrowserEngine.load(context, url)
                if (!loaded.ok) loaded
                else loaded.copy(
                    message = loaded.message + surface.note.let { if (it.isBlank()) "（后台打开，不占屏）" else it },
                )
            }
            BrowserSurface.OVERLAY -> openOverlay(context, url, surface.note)
            BrowserSurface.FULLSCREEN -> openFullscreen(context, url)
        }
    }

    /** 从全屏收回小窗（全屏页调用）。成功返回 true，调用方再 finish。 */
    suspend fun collapseToOverlay(context: Context, url: String): Boolean {
        val loaded = BrowserEngine.load(context, url)
        if (!loaded.ok) return false
        return withContext(Dispatchers.Main) {
            BrowserOverlay.show(
                context,
                onExpand = { u -> if (!u.isNullOrBlank()) launchFullscreen(context, u) },
                onClose = { },
            )
        }
    }

    private suspend fun openOverlay(context: Context, url: String, note: String): BrowserResult {        val loaded = BrowserEngine.load(context, url)
        if (!loaded.ok) return loaded
        val shown = withContext(Dispatchers.Main) {
            BrowserOverlay.show(
                context,
                onExpand = { u -> if (!u.isNullOrBlank()) launchFullscreen(context, u) },
                onClose = { },
            )
        }
        return if (shown) {
            loaded.copy(message = "已在小窗打开「${loaded.title.ifBlank { url }}」（可拖动/最小化/展开全屏）")
        } else {
            // 无悬浮窗权限：退回后台无头（不占屏），如实提示。
            loaded.copy(
                message = loaded.message + "（没有悬浮窗权限，已在后台打开；说“打开网页给我看”可全屏）。" + note,
            )
        }
    }

    private suspend fun openFullscreen(context: Context, url: String): BrowserResult {
        val intent = Intent(context, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .setData(android.net.Uri.parse(url))
        val outcome = VerifiedLauncher.launch(context, intent, context.packageName, 1_500L)
        when (outcome) {
            is LaunchOutcome.Blocked ->
                return BrowserResult(false, "系统拦下了打开 Pilot 浏览器（${outcome.reason.take(60)}），请回到 Pilot 后再试。")
            is LaunchOutcome.Failed ->
                return BrowserResult(false, "无法启动 Pilot 浏览器会话：${outcome.error.javaClass.simpleName}")
            else -> Unit
        }
        withTimeoutOrNull(6_000L) { while (activity == null) delay(50L) }
        val page = activity
            ?: return BrowserResult(false, "浏览器会话没有启动起来（可能被系统的后台限制拦下），这次没有打开网页。")
        // 全屏与会话共享 cookie；把小窗引擎的当前页面对齐过来。
        return page.navigate(url)
    }

    private fun launchFullscreen(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(context, BrowserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .setData(android.net.Uri.parse(url)),
            )
        }
    }

    internal data class SurfaceChoice(val surface: BrowserSurface, val note: String)

    /** 纯决策（便于单测）：给定设置与“是否可画悬浮窗”决定打开方式。 */
    internal fun decideSurface(showToUser: Boolean, setting: String, canOverlay: Boolean): SurfaceChoice {
        if (showToUser) return SurfaceChoice(BrowserSurface.FULLSCREEN, "")
        return when (setting) {
            AgentSettings.BROWSER_MODE_BACKGROUND -> SurfaceChoice(BrowserSurface.BACKGROUND, "")
            AgentSettings.BROWSER_MODE_FULLSCREEN -> SurfaceChoice(BrowserSurface.FULLSCREEN, "")
            else -> {
                // 默认小窗；没有悬浮窗权限就退回无头（不占屏）。
                if (canOverlay) SurfaceChoice(BrowserSurface.OVERLAY, "")
                else SurfaceChoice(BrowserSurface.BACKGROUND, "（未授予悬浮窗权限，已改用后台打开）")
            }
        }
    }

    private fun resolveSurface(context: Context, showToUser: Boolean, setting: String): SurfaceChoice =
        decideSurface(showToUser, setting, BrowserOverlay.canShow(context))

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
