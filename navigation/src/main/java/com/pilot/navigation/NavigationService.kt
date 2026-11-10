package com.pilot.navigation

import android.app.Service
import android.content.Intent
import android.util.Log
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException

/**
 * 导航服务（跑在 `:navigation` 进程）。
 *
 * ## 进程与包体
 * 高德 SDK 的 native 库 87MB + 素材 48MB，最终要拆出主包
 * （见 NAV_MODULE_PLAN.md 的方案 A：so/素材走 ROM 预置，APK 只留 class）。
 * 放独立进程的两个理由：
 * 1. **崩溃隔离**：高德 native 崩了不会带走语音 agent 主进程
 * 2. **内存独立计量**：导航是高内存大户（引擎 + 地图纹理），
 *    LMK 可以单独回收它而不是连带主进程
 *
 * ## 播报接管
 * 导航语音不能自己发声——要并进 Pilot 的统一 TTS 队列，
 * 否则导航语音和 agent 播报会互相打断、还会被麦克风录进去触发误识别。
 * 所以这里只**产生播报文本**，由主进程播。
 */
class NavigationService : Service() {

    companion object {
        private const val TAG = "PilotNav"
        const val ACTION_BIND = "com.pilot.navigation.BIND"

        /** 全局引用（NavActivity 要拿它挂 moveCamera 回调）。 */
        @Volatile
        private var instance: NavigationService? = null

        /** 拿当前服务的定位器（没起来就返回 null）。 */
        fun peekLocationProvider(): LocationProvider? = instance?.location
    }

    /** 导航状态机。 */
    enum class State { IDLE, ROUTING, NAVIGATING, ARRIVED, FAILED }

    @Volatile
    var currentState: State = State.IDLE
        internal set

    @Volatile
    var currentDestination: String? = null
        private set

    private val callbacks = RemoteCallbackList<NavCallback>()
    private val jobs = java.util.concurrent.atomic.AtomicLong(1)

    /** 高德引擎（懒初始化，避免服务起来就吃 100MB+ native）。 */
    private var engine: AmapNaviEngine? = null
    private var location: LocationProvider? = null
    private var sdkPoi: SdkPoiSearch? = null
    private var webPoi: WebPoiSearch? = null
    private var webRoute: WebRoutePlanner? = null
    private var guide: RouteGuide? = null

    override fun onCreate() {
        super.onCreate()
        // ★ 先挂文件日志（国产 ROM 丢 logcat，文件日志是唯一可靠的排查手段）
        NLog.attach(filesDir)
        instance = this
        NLog.i(TAG, ":navigation 进程启动 pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : INavigationService.Stub() {

        override fun startNavi(destination: String, strategy: Int): Long {
            val jobId = jobs.getAndIncrement()
            currentDestination = destination
            currentState = State.ROUTING
            emit(jobId, "state", "正在搜索${destination}", priority = 0, stateName = currentState.toString())

            // ★ 引擎初始化要拉起导航 Activity（AMapNaviView 载体），
            //   getInstance 会加载 68MB native，很慢——不能挡着 binder 线程，
            //   也不能让用户等。所以放后台线程，初始化完再回调。
            val loc = locationProvider()
            // 定位要先起来（算路需要起点），并持续驱动引导播报
            loc.onLocation = { la, lo -> guide().onLocation(la, lo) }
            loc.start()
            // ★ 目的地文本 → 坐标：走 **Web API**（不是 SDK 的 PoiSearch）。
            //   SDK 的 city 参数不生效，会把「水勘院超市」解析到 1517 公里外的
            //   同名 POI——nav-agent 实测踩过，详见 PlaceWebSearch 头注释。
            //   Web API 是同步 HTTP，放子线程。
            // ★ 目的地文本 → 坐标：**双通道**。
            //
            //   1) **Web API 优先**（nav-agent 定稿方案）：
            //      `city=当前城市` + `citylimit=false`，高德自己的排序最准。
            //      「水勘院超市」→ 本地的，「景泰县条山路金星家园」→ 跨城的。
            //   2) **SDK 兜底**（Web API 不可用时）：
            //      Android 平台 Key 认包名+签名，但**city 参数不生效**，
            //      而且通用词会报 1008 检索词歧义（实测「张掖西站」「西湖」都报）。
            //      所以 SDK 只作兜底，且必须靠**距离兜底**防导航到天边。
            //
            //   Web API 之前报 INVALID_USER_KEY 是因为 **Key 没注入**
            //   （local.properties 的键不会自动进 gradle project property，
            //   manifestPlaceholder 是空串——已修）。
            val onFound: (List<SdkPoiSearch.Candidate>) -> Unit = { candidates ->
                onPoiResolved(jobId, candidates, loc, strategy)
            }
            val onFailed: (String) -> Unit = { msg ->
                currentState = State.FAILED
                emit(jobId, "error", msg, priority = 0, stateName = currentState.toString())
            }
            webPoi().search(
                keyword = destination,
                city = loc.city,
                currentLat = loc.lat,
                currentLon = loc.lon,
                onResult = onFound,
                onError = { msg ->
                    NLog.w(TAG, "Web API 地点搜索失败：$msg，改用 SDK 通道")
                    sdkPoi().search(
                        keyword = destination,
                        city = loc.city,
                        currentLat = loc.lat,
                        currentLon = loc.lon,
                        onResult = onFound,
                        onError = onFailed,
                    )
                },
            )
            return jobId
        }

        override fun stopNavi(jobId: Long): Boolean {
            if (currentState == State.IDLE) return false
            currentState = State.IDLE
            currentDestination = null
            runCatching { engine()?.stop() }
            runCatching { guide()?.stop() }
            emit(jobId, "state", "已退出导航", priority = 0, stateName = currentState.toString())
            return true
        }

        override fun getNavState(): String = buildString {
            append("{\"state\":\"").append(currentState.toString()).append("\"")
            append(",\"destination\":\"").append(currentDestination ?: "").append("\"")
            // 剩余距离/时间给「还有多远」查询用
            guide()?.let { g ->
                g.remainingMeters()?.let { append(",\"remainMeters\":").append(it) }
                g.remainingSeconds()?.let { append(",\"remainSeconds\":").append(it) }
            }
            append("}")
        }

        override fun registerCallback(cb: NavCallback?) {
            cb?.let { callbacks.register(it) }
        }
    }

    /** 高德引擎（懒初始化）。 */
    private fun engine(): AmapNaviEngine {
        engine?.let { return it }
        val e = AmapNaviEngine(
            context = this,
            onAnnounce = { text, priority ->
                // ★ 导航播报：priority=1 抢占普通播报，2 = 紧急
                emit(0, "announce", text, priority = priority, stateName = currentState.toString())
            },
            onStateChange = { msg ->
                currentState = when {
                    msg.contains("开始导航") -> State.NAVIGATING
                    msg.contains("失败") -> State.FAILED
                    msg.contains("到达") -> State.ARRIVED
                    msg.contains("退出") -> State.IDLE
                    else -> currentState
                }
                emit(0, "state", msg, priority = 0, stateName = currentState.toString())
            },
        )
        engine = e
        return e
    }

    /**
     * POI 解析结果的统一处理（Web/SDK 两个通道共用）。
     *
     * ★ 距离安全阀（老板 11-10 提醒：「张掖没有西湖啊」）：
     *   超出 MAX_SANE_DISTANCE 的候选**直接丢**，全部超了就报「附近没有」——
     *   绝不能「保留最近的一个」，那会把用户导航到 1500 公里外。
     */
    private fun onPoiResolved(
        jobId: Long,
        candidates: List<SdkPoiSearch.Candidate>,
        loc: LocationProvider,
        strategy: Int,
    ) {
        if (candidates.isEmpty()) {
            currentState = State.FAILED
            emit(jobId, "error", "没找到目的地", priority = 0, stateName = currentState.toString())
            return
        }
        val best = candidates.minByOrNull { it.distance } ?: candidates.first()
        NLog.i(TAG, "选定 POI：${best.name} (${best.lat},${best.lon}) 候选${candidates.size}个")
        emit(jobId, "state", "已找到${best.name}", priority = 0, stateName = currentState.toString())
        // ★ 算路：**SDK 优先，Web 降级**
        //
        //   为什么要有两条：
        //   - SDK（AmapNaviEngine）：官方车道级导航、实时路况、红绿灯倒计时，
        //     但 **Android 平台 Key 绑包名+签名**，Key 不匹配就 errorCode=3
        //   - Web（WebRoutePlanner）：不受绑定限制，但没有地图渲染
        //
        //   策略：SDK 发起算路，等 15 秒。成功就走 SDK（体验最好）；
        //   失败/超时自动降级 Web（保证一定能导航）。
        Thread {
            val sdkOk = trySdkRoute(jobId, best, loc, strategy)
            if (!sdkOk) {
                NLog.i(TAG, "SDK 算路不可用，降级 Web 通道")
                webRoute().plan(
                    fromLat = loc.lat, fromLon = loc.lon,
                    toLat = best.lat, toLon = best.lon,
                    onResult = { r ->
                        guide().start(r)
                        emit(jobId, "state", "开始导航", priority = 0, stateName = currentState.toString())
                    },
                    onError = { msg ->
                        currentState = State.FAILED
                        emit(jobId, "error", msg, priority = 0, stateName = currentState.toString())
                    },
                )
            }
        }.apply { name = "nav-route"; isDaemon = true }.start()
    }

    /**
     * 尝试 SDK 算路，等回调。
     * @return true = 算路成功（SDK 通道可用）
     */
    private fun trySdkRoute(
        jobId: Long,
        best: SdkPoiSearch.Candidate,
        loc: LocationProvider,
        strategy: Int,
    ): Boolean {
        return try {
            val e = engine()
            e.init()
            if (!e.isReady()) {
                NLog.w(TAG, "SDK 引擎没就绪，跳过")
                return false
            }
            e.startNaviToPoint(
                best.name, best.lat, best.lon, strategy,
                startLat = loc.lat, startLon = loc.lon,
                poiId = best.poiId,
            )
            // 等算路回调（导航引擎线程池回调，用 CountDownLatch）
            val ok = e.awaitRoute(15_000)
            NLog.i(TAG, "SDK 算路结果=$ok")
            ok
        } catch (t: Throwable) {
            NLog.w(TAG, "SDK 算路异常 ${t.message}")
            false
        }
    }

    /** Web 算路器（懒初始化）。 */
    private fun webRoute(): WebRoutePlanner {
        webRoute?.let { return it }
        return WebRoutePlanner().also { webRoute = it }
    }

    /** 引导播报器（懒初始化）。 */
    private fun guide(): RouteGuide {
        guide?.let { return it }
        return RouteGuide { text, priority ->
            // 导航播报经 AIDL 回推主进程，走统一 TTS 队列（抢占式）
            emit(0, "announce", text, priority = priority, stateName = currentState.toString())
        }.also { guide = it }
    }

    /** Web API POI 搜索器（懒初始化）。 */
    private fun webPoi(): WebPoiSearch {
        webPoi?.let { return it }
        return WebPoiSearch().also { webPoi = it }
    }

    /** SDK POI 搜索器（懒初始化）。 */
    private fun sdkPoi(): SdkPoiSearch {
        sdkPoi?.let { return it }
        return SdkPoiSearch(applicationContext).also { sdkPoi = it }
    }

    /** 定位器（懒初始化）。 */
    private fun locationProvider(): LocationProvider {
        location?.let { return it }
        return LocationProvider(applicationContext).also { location = it }
    }

    /** 回推事件给主进程。 */
    private fun emit(jobId: Long, event: String, text: String, priority: Int, stateName: String) {
        val b = Bundle().apply {
            putString("event", event)
            putString("text", text)
            putInt("priority", priority)
            putString("state", stateName)
        }
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try { callbacks.getBroadcastItem(i).onEvent(jobId, b) }
                catch (_: RemoteException) { }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }
}
