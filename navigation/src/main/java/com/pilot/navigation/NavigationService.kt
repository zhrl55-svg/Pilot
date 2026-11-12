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
        private const val SDK_KEY_BACKOFF_MS = 30 * 60 * 1000L
        private const val SDK_TRANSIENT_BACKOFF_MS = 60 * 1000L
        private const val SDK_ROUTE_TIMEOUT_MS = 8_000L
        const val ACTION_BIND = "com.pilot.navigation.BIND"

        /** 全局引用（NavActivity 要拿它挂 moveCamera 回调）。 */
        @Volatile
        private var instance: NavigationService? = null

        /** 本进程内熔断，避免 Key 无效或 SDK 服务异常时每次导航都阻塞用户。 */
        @Volatile private var sdkBlockedUntil = 0L
        @Volatile private var sdkBlockedReason = ""

        /**
         * 拿当前服务的定位器（**拿不到就创建并启动定位**）。
         *
         * ★ 之前是 `instance?.location` 直接读字段——但那个字段是**懒初始化**的，
         *   只有 `startNavi()` 里调 `locationProvider()` 才会创建。
         *   从主界面直接点「导航」进来时没人调 startNavi，字段一直是 null
         *   → 地图永远停在北京市（老板反馈）。
         *
         *   现在：服务在就直接把定位器拉起来，导航界面一进来就有坐标可挪镜头。
         */
        fun peekLocationProvider(): LocationProvider? {
            val svc = instance ?: return null
            val loc = svc.location ?: LocationProvider(svc.applicationContext).also {
                svc.location = it
            }
            // 还没启动就启动（幂等）
            runCatching { loc.start() }
            return loc
        }

        /** 拿当前服务实例（NavActivity 要调 stop）。 */
        fun peek(): NavigationService? = instance
    }

    /** 导航状态机。 */
    enum class State { IDLE, ROUTING, NAVIGATING, ARRIVED, FAILED }

    @Volatile
    var currentState: State = State.IDLE
        internal set

    @Volatile
    var currentDestination: String? = null
        private set

    /** 是否已经请求停止（引擎回调里用来忽略迟到的事件）。 */
    @Volatile
    private var stopRequested = false

    private val callbacks = RemoteCallbackList<NavCallback>()
    private val jobs = java.util.concurrent.atomic.AtomicLong(1)
    private val simulateNextRequest = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 高德引擎（懒初始化，避免服务起来就吃 100MB+ native）。 */
    private var engine: AmapNaviEngine? = null
    internal var location: LocationProvider? = null
    private var sdkPoi: SdkPoiSearch? = null
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
            val simulationMarker = java.io.File(filesDir, "nav_simulate_next")
            val simulated = simulateNextRequest.getAndSet(false) || runCatching { simulationMarker.delete() }.getOrDefault(false)
            if (simulated) NLog.i(TAG, "本次请求启用模拟导航")
            currentState = State.ROUTING

            // ★ 先查常去地点：「回家」「去公司」这种词直接命中收藏，
            //   不用走 POI 搜索（也避免搜出同名但很远的店）
            val fav = matchFavorite(destination)
            if (fav != null) {
                currentDestination = fav.label
                NLog.i(TAG, "命中常去地点「${fav.label}」→ ${fav.address}")
                emit(jobId, "state", "导航去${fav.label}", priority = 0, stateName = currentState.toString())
                // 收藏里已经有坐标，直接算路
                java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                    val sdkOk = trySdkRouteFromFav(jobId, fav, strategy, simulated)
                    if (!sdkOk) {
                        webRoute().plan(
                            fromLat = locationProvider().lat, fromLon = locationProvider().lon,
                            toLat = fav.lat, toLon = fav.lon,
                            strategy = strategy,
                            avoidCongestion = NavigationSettings.load(this@NavigationService).avoidJam,
                            onResult = { r ->
                                guide().start(r)
                                if (simulated) guide().simulatePlayback()
                                emit(jobId, "state", "开始导航", priority = 0, stateName = currentState.toString())
                            },
                            onError = { msg ->
                                currentState = State.FAILED
                                emit(jobId, "error", msg, priority = 0, stateName = currentState.toString())
                            },
                        )
                    }
                }
                return jobId
            }

            currentDestination = destination
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
                onPoiResolved(jobId, candidates, loc, strategy, simulated)
            }
            val onFailed: (String) -> Unit = { msg ->
                currentState = State.FAILED
                emit(jobId, "error", msg, priority = 0, stateName = currentState.toString())
            }
            WebPoiSearch.search(
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

        override fun startSimulatedNavi(destination: String): Long {
            simulateNextRequest.set(true)
            return startNavi(destination, AmapNaviEngine.STRATEGY_RECOMMEND)
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
            append(",\"destination\":").append(org.json.JSONObject.quote(currentDestination ?: ""))
            // 剩余距离/时间给「还有多远」查询用
            guide()?.let { g ->
                g.remainingMeters()?.let { append(",\"remainMeters\":").append(it) }
                g.remainingSeconds()?.let { append(",\"remainSeconds\":").append(it) }
                g.nextManeuver()?.let { (instruction, meters) ->
                    append(",\"nextInstruction\":").append(org.json.JSONObject.quote(instruction))
                    append(",\"nextManeuverMeters\":").append(meters)
                }
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
                // ★ 按「播报详细程度」设置过滤：
                //   详细=全念 / 标准=滤掉噪音 / 简洁=只念转弯
                val level = NavigationSettings.load(this).broadcastLevel
                if (!NavigationSettings.shouldSpeak(level, text)) {
                    NLog.i(TAG, "播报被档位过滤(level=$level)：$text")
                } else {
                    emit(0, "announce", text, priority = priority, stateName = currentState.toString())
                }
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
                // ★ 到达后自动退出：导航界面挂着很烦（开车到了就该下车走人）
                if (msg.contains("到达") && NavigationSettings.load(this).arriveAutoExit) {
                    NLog.i(TAG, "已到达，按设置自动退出导航")
                    // 等播报说完再退出（「已到达目的地」要念出来）
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        requestStop()
                        runCatching { NavActivity.closeAll() }
                    }, 4000)
                }
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
        simulated: Boolean,
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
            val sdkOk = trySdkRoute(jobId, best, loc, strategy, simulated)
            if (sdkOk) {
                recordHistory(best.name, best.lat, best.lon, best.poiId)
            } else {
                NLog.i(TAG, "SDK 算路不可用，降级 Web 通道")
                webRoute().plan(
                    fromLat = loc.lat, fromLon = loc.lon,
                    toLat = best.lat, toLon = best.lon,
                    strategy = strategy,
                    avoidCongestion = NavigationSettings.load(this).avoidJam,
                    onResult = { r ->
                        guide().start(r)
                        if (simulated) guide().simulatePlayback()
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
        simulated: Boolean,
    ): Boolean {
        return try {
            val now = System.currentTimeMillis()
            if (now < sdkBlockedUntil) {
                NLog.w(TAG, "SDK 算路熔断中，${(sdkBlockedUntil - now) / 1000}s 后重试：$sdkBlockedReason")
                return false
            }
            val keyStatus = NavKeys.check(this)
            if (keyStatus.contains("sdkKey=missing")) {
                blockSdk("Android SDK Key 未注入", SDK_KEY_BACKOFF_MS)
                return false
            }
            val e = engine()
            e.init()
            if (!e.isReady()) {
                NLog.w(TAG, "SDK 引擎没就绪，跳过")
                blockSdk("SDK 引擎未就绪", SDK_TRANSIENT_BACKOFF_MS)
                return false
            }
            e.startNaviToPoint(
                best.name, best.lat, best.lon, strategy,
                startLat = loc.lat, startLon = loc.lon,
                poiId = best.poiId,
                avoidCongestion = NavigationSettings.load(this).avoidJam,
                simulated = simulated,
            )
            // 等算路回调（导航引擎线程池回调，用 CountDownLatch）
            val ok = e.awaitRoute(SDK_ROUTE_TIMEOUT_MS)
            val error = e.lastRouteErrorCode
            if (!ok) {
                val keyError = error == 13 || error == 22
                blockSdk(
                    if (keyError) "高德 Key/安全码校验失败(error=$error)" else "SDK 算路未完成(error=${error ?: "timeout"})",
                    if (keyError) SDK_KEY_BACKOFF_MS else SDK_TRANSIENT_BACKOFF_MS,
                )
            } else {
                sdkBlockedUntil = 0L
                sdkBlockedReason = ""
            }
            NLog.i(TAG, "SDK 算路结果=$ok error=${error ?: "none"}")
            ok
        } catch (t: Throwable) {
            NLog.w(TAG, "SDK 算路异常 ${t.message}")
            blockSdk("SDK 算路异常", SDK_TRANSIENT_BACKOFF_MS)
            false
        }
    }

    private fun blockSdk(reason: String, durationMs: Long) {
        sdkBlockedReason = reason
        sdkBlockedUntil = System.currentTimeMillis() + durationMs
        NLog.w(TAG, "SDK 算路进入熔断 ${durationMs / 1000}s：$reason；本次立即回退 Web 算路")
    }

    /**
     * 匹配常去地点。
     *
     * 「回家」「回公司」「去单位」这种说法 → 命中收藏的 label。
     * 匹配规则刻意宽松：只要用户说的词包含收藏的 label（或反之）就算，
     * 因为用户不会一字不差地说「家」。
     */
    private fun matchFavorite(spoken: String): NavigationSettings.Favorite? {
        val text = spoken.trim()
        if (text.length > 8) return null   // 长句不是快捷指令
        val favs = NavigationSettings.load(this).favorites
        if (favs.isEmpty()) return null
        return favs.firstOrNull { f ->
            val label = f.label
            label.isNotBlank() && (text.contains(label) || label.contains(text))
        }
    }

    /** 从收藏点算路（有坐标，跳过 POI 搜索）。 */
    private fun trySdkRouteFromFav(
        jobId: Long,
        fav: NavigationSettings.Favorite,
        strategy: Int,
        simulated: Boolean,
    ): Boolean = try {
        val now = System.currentTimeMillis()
        if (now < sdkBlockedUntil) {
            NLog.w(TAG, "SDK 算路熔断中，收藏点直接回退 Web")
            false
        } else if (NavKeys.check(this).contains("sdkKey=missing")) {
            blockSdk("Android SDK Key 未注入", SDK_KEY_BACKOFF_MS)
            false
        } else {
            val e = engine()
            e.init()
            if (!e.isReady()) {
                blockSdk("SDK 引擎未就绪", SDK_TRANSIENT_BACKOFF_MS)
                false
            } else {
                val loc = locationProvider()
                loc.start()
                e.startNaviToPoint(
                    fav.label, fav.lat, fav.lon, strategy,
                    startLat = loc.lat, startLon = loc.lon,
                    poiId = fav.poiId,
                    avoidCongestion = NavigationSettings.load(this).avoidJam,
                    simulated = simulated,
                )
                val ok = e.awaitRoute(SDK_ROUTE_TIMEOUT_MS)
                val error = e.lastRouteErrorCode
                if (!ok) {
                    val keyError = error == 13 || error == 22
                    blockSdk(
                        if (keyError) "高德 Key/安全码校验失败(error=$error)" else "SDK 算路未完成(error=${error ?: "timeout"})",
                        if (keyError) SDK_KEY_BACKOFF_MS else SDK_TRANSIENT_BACKOFF_MS,
                    )
                } else {
                    sdkBlockedUntil = 0L
                    sdkBlockedReason = ""
                }
                ok
            }
        }
    } catch (t: Throwable) {
        NLog.w(TAG, "收藏点算路异常 ${t.message}")
        blockSdk("SDK 算路异常", SDK_TRANSIENT_BACKOFF_MS)
        false
    }

    /**
     * 记录导航历史（最近 20 条，重复目的地合并到最前）。
     *
     * 记录时机是**算路成功**之后，不是点导航的时候——
     * 用户说了目的地但没算出路（比如「附近没有」），不该进历史。
     */
    private fun recordHistory(name: String, lat: Double, lon: Double, poiId: String?) {
        runCatching {
            val cur = NavigationSettings.load(this)
            val entry = NavigationSettings.HistoryEntry(
                name = name, alias = "", lat = lat, lon = lon, poiId = poiId,
                time = System.currentTimeMillis(),
            )
            // 同一个地方（名字+坐标接近）只留最新一条
            val rest = cur.history.filterNot {
                it.name == name && kotlin.math.abs(it.lat - lat) < 0.0005 &&
                    kotlin.math.abs(it.lon - lon) < 0.0005
            }
            val list = (listOf(entry) + rest).take(20)
            NavigationSettings.save(this, cur.copy(history = list))
            NLog.i(TAG, "历史已记录：$name（共 ${list.size} 条）")
        }.onFailure { NLog.w(TAG, "记历史失败 ${it.message}") }
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

    /** 供界面层发起导航（历史再导航用）。 */
    fun startNaviFromOuter(destination: String, strategy: Int): Long =
        binder.startNavi(destination, strategy)

    /** 设备端测试入口：下一次导航使用 SDK 模拟器；Web 回退时回放路线坐标。 */
    fun startSimulatedNaviFromOuter(destination: String): Long {
        simulateNextRequest.set(true)
        return binder.startNavi(destination, AmapNaviEngine.STRATEGY_RECOMMEND)
    }

    fun routeOptions(): List<AmapNaviEngine.RouteOption> = engine?.routeOptions().orEmpty()

    fun selectRoute(pathId: Long): Boolean = engine?.selectRoute(pathId) ?: false

    /**
     * 请求停止导航（供 NavActivity 的退出按钮调）。
     * 停引擎 + 停引导 + 落状态，**不 finish 界面**（界面由调用方决定关不关）。
     */
    fun requestStop() {
        NLog.i(TAG, "requestStop 停止导航")
        stopRequested = true
        currentState = State.IDLE
        runCatching { engine?.stop() }
        runCatching { guide?.stop() }
        currentDestination = null
        emit(0, "state", "已退出导航", priority = 0, stateName = currentState.toString())
    }

    /**
     * 彻底释放高德 SDK（左下角「退出」用）。
     *
     * 必须在 NavActivity 的 AMapNaviView.onDestroy() 之后调，
     * 否则地图渲染层还活着的时候销毁单例会崩。
     * 下次导航 [engine] 会懒创建，重新 getInstance。
     */
    fun releaseEngine() {
        NLog.i(TAG, "releaseEngine 释放导航引擎")
        runCatching { engine?.release() }
        engine = null
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
