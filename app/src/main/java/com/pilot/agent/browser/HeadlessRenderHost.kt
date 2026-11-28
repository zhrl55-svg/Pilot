package com.pilot.agent.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.pilot.agent.Dbg
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无头 WebView 的 GPU 离屏渲染宿主（移植自 Minis HeadlessRenderHost）。
 *
 * ## 为什么需要它
 * Pilot 的后台浏览器 WebView 不挂到任何窗口（[BrowserEngine] 的「假宿主」只 measure/layout，
 * 没有窗口表面）。Chromium 对软件 draw 会在 CPU 上重新光栅化，拿不到 GPU 表面——
 * WebGL、`<video>` 等 GPU-only 层会渲染成空白； detached View 也没有屏幕像素可读（PixelCopy 无从下手）。
 *
 * ## 做法（与 Minis 一致，普通签名即可，无需任何权限）
 * 仅在截屏期间给 WebView 一块真实表面：一个**私有虚拟屏**（`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`，
 * 只对本 App 可见、用户看不到）背后接 [ImageReader]，屏上放一个 [Presentation] 窗口，
 * 把 WebView 临时挂进去走正常硬件渲染管线，从 ImageReader 读回一帧合成画面，再摘回 WebView。
 *
 * ## 成本控制
 * - 首次截屏才建屏，最后一次截屏 [IDLE_RELEASE_MS] 后释放 display/window/buffer，空闲零开销。
 * - 截屏串行化；尺寸不超过 [MAX_EDGE_PX]，超大整页截屏退回软件路径。
 * - 任何失败返回 null，调用方退回软件 draw；连续建屏失败 [MAX_HOST_FAILURES] 次后本进程禁用。
 * - 窗口 NOT_FOCUSABLE / NOT_TOUCHABLE：页面里的输入框绝不会在真机上弹出输入法。
 *
 * ★ 与 [com.pilot.agent.display.VirtualDisplayHost] 的区别：后者要把**别的 App** 拉上虚拟屏，
 *   需 `ADD_TRUSTED_DISPLAY`（平台签名）；这里只渲染**自己的** WebView，用公开 flag，普通安装可用。
 */
internal object HeadlessRenderHost {
    private const val TAG = "PilotHeadlessRender"

    const val IDLE_RELEASE_MS = 15_000L
    const val MAX_EDGE_PX = 4096
    private const val VISUAL_STATE_TIMEOUT_MS = 1_500L
    private const val SETTLE_TIMEOUT_MS = 700L
    private const val QUIET_MS = 150L
    private const val MIN_FRAMES = 2
    private const val MAX_HOST_FAILURES = 3

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    // 以下状态只在主线程触碰。
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    private var hostW = 0
    private var hostH = 0
    private var latest: Image? = null
    private var frameCount = 0L
    private var lastFrameAt = 0L
    private var hostFailures = 0
    private var visualStateRequest = 0L

    /** 非 null 表示本进程已放弃 GPU 路径（建屏反复失败）。 */
    @Volatile
    var disabled: String? = null
        private set

    private val releaseTask = Runnable { release("idle") }

    /**
     * 通过 GPU 合成器把 [webView] 截成 [w]×[h] 物理像素的 Bitmap；
     * 不适用（WebView 已在屏上、尺寸超限、建屏失败）或失败时返回 null，调用方退回软件 draw。
     */
    suspend fun capture(webView: WebView, w: Int, h: Int): Bitmap? {
        if (disabled != null) return null
        return mutex.withLock { withContext(Dispatchers.Main) { captureOnMain(webView, w, h) } }
    }

    private suspend fun captureOnMain(webView: WebView, w: Int, h: Int): Bitmap? {
        // 已挂在某个窗口（悬浮小窗/全屏）：不是我们能动的，截屏期间拿走会让那个窗口空白。
        if (webView.isAttachedToWindow) return null
        if (w <= 0 || h <= 0 || w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null

        main.removeCallbacks(releaseTask)
        val t0 = SystemClock.elapsedRealtime()
        var image: Image? = null
        var scrollbars: Pair<Boolean, Boolean>? = null
        try {
            val host = ensureHost(webView.context, w, h) ?: return null
            scrollbars = webView.isVerticalScrollBarEnabled to webView.isHorizontalScrollBarEnabled
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
            // ★ Pilot 的无头 WebView 挂在 BrowserEngine 的「假宿主」上（parent 非 null 但未附窗），
            //   必须先摘离旧父容器再挂进 Presentation，否则 addView 抛 "child already has a parent"。
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            host.addView(webView, FrameLayout.LayoutParams(w, h))

            val ready = awaitVisualState(webView)
            val start = frameCount
            webView.invalidate()
            withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                while (frameCount < start + MIN_FRAMES ||
                    SystemClock.elapsedRealtime() - lastFrameAt < QUIET_MS
                ) delay(8)
                true
            }
            val gotFrames = frameCount >= start + 1
            // 截屏途中 WebView 可能被悬浮窗重新挂载：那帧就是空窗口，作废。
            if (webView.parent !== host) {
                Dbg.w(TAG, "截屏期间 WebView 离开宿主，退回软件路径")
                return null
            }
            if (!gotFrames || latest == null) {
                Dbg.w(TAG, "没有合成帧（ready=$ready frames=${frameCount - start}），退回软件路径")
                return null
            }
            image = latest
            latest = null
            host.removeView(webView)
            val owned = image!!
            val bitmap = withContext(Dispatchers.Default) { toBitmap(owned, w, h) }
            Dbg.i(TAG, "GPU 截屏 ${w}x$h 耗时=${SystemClock.elapsedRealtime() - t0}ms frames=${frameCount - start}")
            return bitmap
        } catch (e: Exception) {
            Dbg.w(TAG, "GPU 截屏失败：${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            image?.close()
            scrollbars?.let { (v, hz) ->
                webView.isVerticalScrollBarEnabled = v
                webView.isHorizontalScrollBarEnabled = hz
            }
            val host = container
            if (host != null && webView.parent === host) host.removeView(webView)
            // 归还无头假宿主：BrowserEngine 期望 WebView 始终挂在 holder 上（未附窗），
            // 截屏临时借走后再放回，避免后续 read/click 的父容器状态错乱。
            if (webView.parent == null) {
                runCatching { BrowserEngine.releaseToHolder() }
            }
            main.postDelayed(releaseTask, IDLE_RELEASE_MS)
        }
    }

    /** 当前内容会进入下一帧时 resolve；有界，卡死的渲染器不能拖死截屏。 */
    private suspend fun awaitVisualState(webView: WebView): Boolean {
        val done = CompletableDeferred<Unit>()
        val id = ++visualStateRequest
        webView.postVisualStateCallback(
            id,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId == id) done.complete(Unit)
                }
            },
        )
        return withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) { done.await() } != null
    }

    private fun ensureHost(context: Context, w: Int, h: Int): FrameLayout? {
        val existing = container
        if (existing != null && presentation != null && hostW == w && hostH == h) return existing
        release(if (existing == null) "create" else "resize")

        val app = context.applicationContext
        var r: ImageReader? = null
        var vd: VirtualDisplay? = null
        try {
            val dm = app.getSystemService(DisplayManager::class.java) ?: return fail("no DisplayManager")
            r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            // 只保留最新帧：ImageReader 的 image 不被 acquire 会在队列满后停止接收。
            r.setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
                frameCount++
                lastFrameAt = SystemClock.elapsedRealtime()
            }, main)
            // 不用 PUBLIC flag：私有屏只本 App 能放内容，无需任何权限，用户不可见。
            vd = dm.createVirtualDisplay(
                "pilot-headless-browser", w, h, app.resources.displayMetrics.densityDpi,
                r.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: return fail("createVirtualDisplay returned null").also { r.close() }

            val p = Presentation(app, vd.display)
            p.window?.apply {
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
            }
            val c = FrameLayout(p.context)
            p.setContentView(c)
            p.show()

            reader = r
            display = vd
            presentation = p
            container = c
            hostW = w
            hostH = h
            hostFailures = 0
            Dbg.i(TAG, "离屏宿主已建 ${w}x$h dpi=${app.resources.displayMetrics.densityDpi}")
            return c
        } catch (e: Exception) {
            runCatching { vd?.release() }
            runCatching { r?.close() }
            return fail("host setup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fail(reason: String): FrameLayout? {
        hostFailures++
        Dbg.w(TAG, "$reason (failure $hostFailures/$MAX_HOST_FAILURES)")
        if (hostFailures >= MAX_HOST_FAILURES) {
            disabled = reason
            Dbg.w(TAG, "本进程禁用 GPU 截屏：$reason")
        }
        return null
    }

    /** 全部拆掉，下次截屏按需重建。主线程。 */
    private fun release(why: String) {
        if (presentation == null && display == null && reader == null) return
        main.removeCallbacks(releaseTask)
        container?.removeAllViews()
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        latest?.close()
        latest = null
        runCatching { reader?.close() }
        presentation = null
        display = null
        reader = null
        container = null
        Dbg.i(TAG, "离屏宿主已释放（$why）${hostW}x$hostH")
        hostW = 0
        hostH = 0
    }

    /**
     * 把 RGBA_8888 的 [image] 拷成 [w]×[h] ARGB_8888 Bitmap。
     * plane 的行可能有 padding（rowStride > w*4），末行不 pad，所以逐行重排而非整块拷贝。
     */
    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = w * pixelStride
        val packed = ByteBuffer.allocateDirect(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (y in 0 until h) {
            src.position(y * rowStride)
            src.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(packed) }
    }
}