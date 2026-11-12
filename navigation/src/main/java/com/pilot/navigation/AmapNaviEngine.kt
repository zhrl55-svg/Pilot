package com.pilot.navigation

import android.content.Context
import android.util.Log
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.enums.NaviTTSType
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng

/**
 * 高德导航引擎封装（移植自 nav-agent 的 AmapNavController）。
 *
 * ## 与 nav-agent 的差别
 * nav-agent 是「车机 App」，导航界面占满屏、有 HUD、有自己的语音优先级体系。
 * Pilot 是「手机助手」，导航只是它的一个能力，所以：
 * - 砍掉 nav-agent 的 HUD/车机逻辑，只留引擎 + 播报文本产出
 * - 播报**不自己发声**：文本经 AIDL 回推主进程，走统一 TTS 队列
 *
 * ## 三个必踩的坑（nav-agent 已经踩过，直接继承它的做法）
 * 1. **必须关内置语音** `setUseInnerVoice(false)`——否则和我们的 TTS 双重播报
 * 2. **必须先同意隐私协议**——新版 SDK 不调 updatePrivacyAgree 就不给定位/算路
 * 3. **算路是异步的**——真正 startNavi 在 onCalculateRouteSuccess 里，
 *    先 startNavi 再算路会返回 false
 *
 * ## API 签名（javap 查过 SDK 11.3.100，别猜）
 * - `calculateDriveRoute(List<NaviLatLng>, List<NaviLatLng>, int)`
 * - `startNavi(int)` — int 是 NaviType（GPS / EMULATOR）
 * - `onCalculateRouteSuccess(int[])` — 不是 AMapCalcRouteResult
 */
class AmapNaviEngine(
    private val context: Context,
    /** 播报文本产出（回推给主进程） */
    private val onAnnounce: (String, Int) -> Unit,
    /** 状态变化 */
    private val onStateChange: (String) -> Unit,
) : AMapNaviListener {

    data class RouteOption(val pathId: Long, val distanceMeters: Int, val durationSeconds: Int)

    companion object {
        private const val TAG = "PilotNavEngine"

        /** 导航策略：0=推荐 1=躲避拥堵 2=最短 3=高速优先 */
        const val STRATEGY_RECOMMEND = 0
        const val STRATEGY_AVOID_JAM = 1
        const val STRATEGY_SHORTEST = 2
        const val STRATEGY_HIGHWAY = 3

        /** 播报优先级：导航提示要抢占普通播报 */
        const val PRIORITY_NAV = 1
        const val PRIORITY_URGENT = 2

        /** 同意隐私协议（新版 SDK 必需，不调就没法用）。 */
        fun agreePrivacy(ctx: Context) {
            runCatching {
                com.amap.api.maps.MapsInitializer.updatePrivacyShow(ctx, true, true)
                com.amap.api.maps.MapsInitializer.updatePrivacyAgree(ctx, true)
                com.amap.api.location.AMapLocationClient.updatePrivacyShow(ctx, true, true)
                com.amap.api.location.AMapLocationClient.updatePrivacyAgree(ctx, true)
                com.amap.api.services.core.ServiceSettings.updatePrivacyShow(ctx, true, true)
                com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(ctx, true)
                NLog.i(TAG, "高德隐私协议已同意")
            }.onFailure { NLog.w(TAG, "隐私协议设置失败 ${it.message}") }
        }
    }

    private var navi: AMapNavi? = null
    private var started = false
    private var stopRequested = false
    private var pendingDestination: String? = null

    /** 算路结果闩：算路是异步回调，算路线程要用它等结果。 */
    @Volatile private var routeLatch: java.util.concurrent.CountDownLatch? = null

    /** 算路是否成功（awaitRoute 用）。 */
    @Volatile private var routeOk = false
    @Volatile private var routePending = false
    @Volatile private var routeNaviType = NaviType.GPS

    /** 最近一次 SDK 算路错误码，供服务层区分配置错误和临时网络错误。 */
    @Volatile var lastRouteErrorCode: Int? = null
        private set

    /** SDK 引擎是否可用（初始化成功）。 */
    fun isReady(): Boolean = started && navi != null

    /** 当前 SDK 返回的备选路线摘要，供导航界面提供手动切换。 */
    fun routeOptions(): List<RouteOption> = runCatching {
        navi?.naviPaths?.values.orEmpty().mapNotNull { path ->
            val id = path.pathid
            if (id < 0) null else RouteOption(id, path.allLength, path.allTime)
        }.distinctBy { it.pathId }
    }.onFailure { NLog.w(TAG, "读取备选路线失败 ${it.message}") }.getOrDefault(emptyList())

    fun selectRoute(pathId: Long): Boolean = runCatching {
        val n = navi
        if (n == null) false else {
            n.selectMainPathID(pathId)
            NLog.i(TAG, "切换备选路线 pathId=$pathId 已提交")
            true
        }
    }.onFailure { NLog.w(TAG, "切换备选路线失败 ${it.message}") }.getOrDefault(false)

    /** 等算路结果，超时返回 false。 */
    fun awaitRoute(timeoutMs: Long): Boolean {
        val latch = routeLatch ?: return false
        val done = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            // SDK 偶发迟到回调不能在 Web 回退已经开始后又启动第二条导航。
            routePending = false
            NLog.w(TAG, "SDK 算路等待超时，忽略迟到的路线回调")
        }
        return done && routeOk
    }

    /**
     * 初始化引擎（幂等）。
     *
     * ★ 必须在 **Activity**（有 AMapNaviView 的那个）里调：
     *   在纯 Service 进程里调 `AMapNavi.getInstance()` 会**卡住**——
     *   引擎没有渲染载体就不回调（实测：getInstance 之后没有任何日志，
     *   onCalculateRouteSuccess 永远不来，logcat 也没报错）。
     *   nav-agent 也是 NavActivity 承载 NaviView 的。
     */
    fun init() {
        if (started) return
        agreePrivacy(context)
        // 先把导航界面拉起来（它内部会创建 AMapNaviView）
        runCatching { NavActivity.show(context, pendingDestination ?: "") }
        runCatching {
            val n = AMapNavi.getInstance(context)
            n.addAMapNaviListener(this)
            runCatching { n.setTrafficSignalEnable(true) }
                .onFailure { NLog.w(TAG, "开启红绿灯信号数据失败 ${it.message}") }
            runCatching { n.setMultipleRouteNaviMode(true) }
                .onFailure { NLog.w(TAG, "开启多路线导航模式失败 ${it.message}") }
            // ★ 关内置语音：我们要用统一 TTS 队列（导航播报要能抢占普通播报，
            //   而且要占麦克风闸门防自问自答），SDK 内置语音做不到这些。
            runCatching { n.setUseInnerVoice(false) }
                .onFailure { NLog.w(TAG, "关内置语音失败 ${it.message}") }
            navi = n
            started = true
            NLog.i(TAG, "AMapNavi 初始化成功 ver=${AMapNavi.getVersion()}")
        }.onFailure {
            NLog.e(TAG, "AMapNavi 初始化失败", it)
            onStateChange("导航引擎初始化失败：${it.message}")
        }
    }

    /**
     * 开始导航（目的地是文本）。
     * 真正的算路要先经 POI 解析出坐标，见 [startNaviToPoint]。
     */
    fun startNavi(destination: String, strategy: Int = STRATEGY_RECOMMEND) {
        init()
        if (navi == null) {
            onStateChange("导航引擎没起来")
            return
        }
        stopRequested = false
        pendingDestination = destination
        onStateChange("正在搜索目的地「$destination」")
    }

    /**
     * POI 解析完成后调用：设起点终点并算路。
     *
     * ★ NaviLatLng 用**无参构造 + setter**——那个 (lat, lon) 有参构造
     *   参数顺序有歧义，nav-agent 踩过（写成 lat/lon 实际是 lon/lat）。
     */
    fun startNaviToPoint(
        name: String,
        lat: Double,
        lon: Double,
        strategy: Int = STRATEGY_RECOMMEND,
        startLat: Double = 0.0,
        startLon: Double = 0.0,
        /** POI ID（有就用 NaviPoi 重载，SDK 会找正确入口）。 */
        poiId: String? = null,
        /** 驾车或步行。步行走 SDK 的 calculateWalkRoute。 */
        travelMode: TravelMode = TravelMode.DRIVE,
        /** 按导航设置应用拥堵偏好。 */
        avoidCongestion: Boolean = true,
        /** 显式测试模式：使用 SDK 模拟定位推进路线。 */
        simulated: Boolean = false,
    ) {
        val n = navi ?: return
        NavKeys.check(context)
        // ★★ 起点/终点用 **String 重载**（"经度,纬度"），这是关键。
        //
        //   踩过的坑：用 `List<NaviLatLng>` 重载时算路稳定报
        //   **errorCode=3 = ERROR_STARTPOINT（起点错误）**，
        //   因为系统定位给的坐标可能落在**无法通行的区域**（小区内部/建筑里），
        //   SDK 内部不会纠偏就报起点错误。
        //
        //   nav-agent 的定稿做法（它的注释写明）：
        //   「起点用"经度,纬度"字符串传给 calculateDriveRoute 的 String 重载，
        //     由 SDK 内部处理，省掉 GeocodeSearch 依赖」
        //   String 重载会走服务端纠偏，起点会吸附到最近道路 → 算路成功。
        //
        //   注：errorCode 的对照表（反射 PathPlanningErrCode 打出来的）：
        //     2 = ERROR_CONNECTION   网络问题
        //     3 = ERROR_STARTPOINT   起点错误
        //     6 = ERROR_ENDPOINT     终点错误
        //     13 = INVALID_USER_KEY  Key 无效
        //     22 = INVALID_USER_SCODE 安全码格式/值不对
        //         ★ 高德控制台要求**带冒号**的 SHA1：
        //           26:F6:15:CD:A0:6C:08:F4:7A:4B:BA:70:A3:20:DD:07:47:D7:91:7A
        //           填不带冒号的会被判无效（2026-11-10 实测）。
        routeOk = false
        routePending = true
        routeNaviType = if (simulated) NaviType.EMULATOR else NaviType.GPS
        lastRouteErrorCode = null
        routeLatch = java.util.concurrent.CountDownLatch(1)
        // 多路线策略由 SDK 官方转换器生成，确保“躲避拥堵”开关实际参与算路。
        val pathStrategy = when (strategy) {
            STRATEGY_SHORTEST -> PathPlanningStrategy.DRIVING_SHORTEST_DISTANCE
            STRATEGY_HIGHWAY -> n.strategyConvert(avoidCongestion, false, false, true, true)
            STRATEGY_AVOID_JAM -> n.strategyConvert(true, false, false, false, true)
            else -> n.strategyConvert(avoidCongestion, false, false, false, true)
        }
        if (travelMode == TravelMode.DRIVE) {
            // 从步行切回驾车时，清掉上一次设置的步行出行信息。
            runCatching {
                n.setTravelInfo(
                    com.amap.api.navi.model.AMapTravelInfo(
                        com.amap.api.navi.enums.TransportType.Drive,
                    ),
                )
            }
            // ★ 算路**之前**设车辆信息（nav-agent 同款）：
            //   官方文档说 setCarInfo 影响「路径规划时会躲避车辆限行区域和路线」，
            //   所以必须在 calculateDriveRoute 之前生效，不能只在 init 里设一次。
            //   不设的话算路可能直接失败（errorCode=3）。
            applyCarInfo(n)
        }
        runCatching {
            val ok = if (travelMode == TravelMode.WALK) {
                runCatching {
                    n.setTravelInfo(
                        com.amap.api.navi.model.AMapTravelInfo(
                            com.amap.api.navi.enums.TransportType.Walk,
                        ),
                    )
                }
                val startPoi = com.amap.api.navi.model.NaviPoi(
                    "", com.amap.api.maps.model.LatLng(round6(startLat), round6(startLon)), "起点",
                )
                val endPoi = com.amap.api.navi.model.NaviPoi(
                    poiId.orEmpty(),
                    com.amap.api.maps.model.LatLng(round6(lat), round6(lon)),
                    name,
                )
                n.calculateWalkRoute(
                    startPoi,
                    endPoi,
                    com.amap.api.navi.enums.TravelStrategy.SINGLE,
                )
            } else {
                // ★ 有 POI ID 就用 **NaviPoi 重载**——这是解决 errorCode=6
                //   （ERROR_ENDPOINT 终点错误）的关键。
                //
                //   原因：「张掖西站停车场」这类 POI 的经纬度是**停车场中心**，
                //   不在可通行道路上，SDK 拿纯坐标算路会报终点错误。
                //   传 POI ID 后 SDK 会查 POI 的**道路入口坐标**再算路。
                if (poiId.isNullOrBlank()) {
                    // 没 POI ID：退回 String 重载（服务端会做纠偏）
                    n.calculateDriveRoute(
                        "${round6(startLon)},${round6(startLat)}",
                        "${round6(lon)},${round6(lat)}",
                        null, pathStrategy,
                    )
                } else {
                    val startPoi = com.amap.api.navi.model.NaviPoi(
                        "", com.amap.api.maps.model.LatLng(round6(startLat), round6(startLon)), "起点",
                    )
                    val endPoi = com.amap.api.navi.model.NaviPoi(
                        poiId, com.amap.api.maps.model.LatLng(round6(lat), round6(lon)), name,
                    )
                    n.calculateDriveRoute(startPoi, endPoi, null, pathStrategy)
                }
            }
            NLog.i(
                TAG,
                "算路 mode=${travelMode.name} 起点($startLat,$startLon) → $name($lat,$lon) " +
                    "poiId=${poiId?.take(12)} simulated=$simulated ok=$ok",
            )
            if (!ok) {
                routePending = false
                lastRouteErrorCode = -1
                routeLatch?.countDown()
                NLog.w(TAG, "SDK 未接受算路请求（同步返回 false）")
            }
            onStateChange("正在规划去${name}的${travelMode.label}路线")
        }.onFailure {
            NLog.e(TAG, "算路失败", it)
            onStateChange("算路失败：${it.message}")
        }
    }

    /**
     * 车辆信息（算路前必须设）。
     * 0 = 轿车，restriction=true = 规避限行路段。
     */
    private fun applyCarInfo(navi: AMapNavi) {
        runCatching {
            val carInfo = com.amap.api.navi.model.AMapCarInfo().apply {
                setCarType("0")
                setRestriction(true)
            }
            navi.setCarInfo(carInfo)
            NLog.i(TAG, "车辆信息已设 carType=${carInfo.carType} restriction=${carInfo.isRestriction}")
        }.onFailure { NLog.w(TAG, "设置车辆信息失败：${it.message}") }
    }

    /** 坐标保留 6 位小数。 */
    private fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    /** 停止导航。 */
    fun stop() {
        stopRequested = true
        val n = navi ?: return
        runCatching {
            n.stopNavi()
            n.removeAMapNaviListener(this)
        }.onFailure { NLog.w(TAG, "停止导航失败 ${it.message}") }
        onStateChange("已退出导航")
    }

    fun release() {
        runCatching {
            navi?.stopNavi()
            navi?.removeAMapNaviListener(this)
        }.onFailure { NLog.w(TAG, "释放导航失败 ${it.message}") }
        navi = null
        started = false
        // ★ 彻底退出：连 AMapNavi 单例一起销毁，下次导航 getInstance 会重建。
        //   调用方必须保证在 AMapNaviView.onDestroy() 之后调（NavActivity 负责顺序）。
        runCatching { AMapNavi.destroy() }
            .onFailure { NLog.w(TAG, "销毁 AMapNavi 失败 ${it.message}") }
        NLog.i(TAG, "高德导航 SDK 已释放")
    }

    // ------------------------------------------------------------ AMapNaviListener

    /** 算路成功回调（SDK 有两个重载，两个都必须实现，否则类不是抽象的编译不过）。 */
    override fun onCalculateRouteSuccess(paths: IntArray?) {
        onRouteCalculated()
    }

    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        NLog.i(TAG, "算路成功(errorCode=${result?.errorCode})")
        onRouteCalculated()
    }

    private fun onRouteCalculated() {
        routeOk = true
        routeLatch?.countDown()
        if (!routePending) {
            NLog.w(TAG, "忽略已超时/取消的 SDK 算路成功回调")
            return
        }
        routePending = false
        if (stopRequested) return
        // ★ startNavi 必须在算路成功之后调——先 startNavi 再算路会返回 false。
        val n = navi ?: return
        runCatching {
            // startNavi 要传定位类型（GPS = 真机定位）
            val ok = n.startNavi(routeNaviType)
            NLog.i(TAG, "startNavi type=$routeNaviType ok=$ok")
            if (ok) onStateChange("开始导航")
        }.onFailure {
            NLog.e(TAG, "startNavi 异常", it)
            onStateChange("启动导航失败：${it.message}")
        }
    }

    override fun onCalculateRouteFailure(error: Int) {
        routeOk = false
        routePending = false
        lastRouteErrorCode = error
        routeLatch?.countDown()
        if (stopRequested) return
        NLog.w(TAG, "算路失败 error=$error")
        onStateChange("路线规划失败（$error）")
    }

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        routeOk = false
        routePending = false
        lastRouteErrorCode = result?.errorCode
        routeLatch?.countDown()
        if (stopRequested) return
        NLog.w(TAG, "算路失败 errorCode=${result?.errorCode}")
        onStateChange("路线规划失败（${result?.errorCode}）")
    }

    override fun onInitNaviSuccess() {
        NLog.i(TAG, "引擎初始化成功")
    }

    override fun onInitNaviFailure() {
        NLog.e(TAG, "引擎初始化失败")
        onStateChange("导航引擎初始化失败")
    }

    override fun onStartNavi(type: Int) {
        NLog.i(TAG, "开始导航 type=$type")
    }

    /**
     * ★ 高德 SDK 直接给播报文本——这是导航播报的主来源。
     *   我们只转文本，不自己发声。
     */
    override fun onGetNavigationText(type: Int, text: String?) {
        announceOnce(text, priorityOf(type))
    }

    /**
     * 旧版 SDK 的单参数回调。
     *
     * ★ 两个回调**会同时到达**（实测同一句话播了两遍）。
     *   nav-agent 的注释也写了「两种 onGetNavigationText 在各版本 SDK 中
     *   可能只来一种，也可能同时到达」。所以必须去重。
     */
    override fun onGetNavigationText(text: String?) {
        announceOnce(text, PRIORITY_NAV)
    }

    /** 最近一次播报的文本 + 时间，用来去掉重复回调。 */
    @Volatile private var lastAnnounceText: String = ""
    @Volatile private var lastAnnounceAt: Long = 0L

    /**
     * 播报去重。
     *
     * 同一句话 1.5 秒内只播一次——两个 onGetNavigationText 重载会
     * 同时触发（时间差几毫秒），不去重就重复播两遍。
     */
    private fun announceOnce(raw: String?, priority: Int) {
        if (stopRequested) return
        val t = cleanText(raw) ?: return
        val now = System.currentTimeMillis()
        if (t == lastAnnounceText && now - lastAnnounceAt < 1500) {
            NLog.i(TAG, "播报去重（重复回调）：$t")
            return
        }
        lastAnnounceText = t
        lastAnnounceAt = now
        NLog.i(TAG, "导航播报 p=$priority text=$t")
        onAnnounce(t, priority)
    }

    /**
     * 清理高德给的播报文本。
     *
     * 高德会用**半角逗号**分词，而且常有连续标点：
     *   「准备出发,,,全程二点八公里,大约需要九分钟」
     * TTS 念出来会一顿一顿的。统一换成中文标点，去掉连续重复。
     */
    private fun cleanText(raw: String?): String? {
        var t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        // 连续逗号/顿号 → 一个中文逗号
        t = t.replace(Regex("[,，、]{2,}"), "，")
        // 半角逗号 → 中文逗号（TTS 停顿更自然）
        t = t.replace(",", "，")
        // 连续句号 → 一个
        t = t.replace(Regex("[。.]{2,}"), "。")
        // 首尾标点
        t = t.trim(',', '，', '、', '。', '.', ' ')
        return t.ifEmpty { null }
    }

    override fun onArriveDestination() {
        if (stopRequested) return
        onStateChange("已到达目的地")
        onAnnounce("已到达目的地", PRIORITY_NAV)
    }

    override fun onReCalculateRouteForYaw() {
        if (stopRequested) return
        onAnnounce("路线已重新规划", PRIORITY_NAV)
    }

    override fun onReCalculateRouteForTrafficJam() {
        if (stopRequested) return
        onAnnounce("前方拥堵，已为你换路", PRIORITY_NAV)
    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (stopRequested) return
        val i = info ?: return
        Log.i(
            TAG,
            "NaviInfo step=${i.curStepRetainDistance} road=${i.nextRoadName} " +
                "remain=${i.pathRetainDistance}m",
        )
    }

    override fun onTrafficStatusUpdate() {
        NLog.i(TAG, "路况状态更新")
    }

    override fun onEndEmulatorNavi() {
        NLog.i(TAG, "模拟器导航结束")
    }

    override fun onArrivedWayPoint(index: Int) {
        NLog.i(TAG, "到达途经点 $index")
    }

    override fun onGpsSignalWeak(weak: Boolean) {
        NLog.i(TAG, "GPS 信号弱=$weak")
    }

    override fun onGpsOpenStatus(open: Boolean) {
        NLog.i(TAG, "GPS 开关=$open")
    }

    /** 不同事件类型的优先级：到达/偏航这种要立刻说，其余普通。 */
    private fun priorityOf(type: Int): Int = when (type) {
        NaviTTSType.NAVI_END_TEXT, NaviTTSType.NAVI_YAW -> PRIORITY_URGENT
        else -> PRIORITY_NAV
    }

    // ---------------------------------------------------- 其余抽象方法（SDK 要求全实现）

    override fun onLocationChange(location: com.amap.api.navi.model.AMapNaviLocation?) {
        // 导航引擎自己的定位（比 AMapLocationClient 更准，含方向/速度）
    }

    override fun onNaviRouteNotify(data: com.amap.api.navi.model.AMapNaviRouteNotifyData?) {
        NLog.i(TAG, "路线通知 road=${data?.roadName} reason=${data?.reason}")
    }

    override fun onPlayRing(ringType: Int) {
        NLog.i(TAG, "导航提示音 type=$ringType")
    }

    override fun notifyParallelRoad(index: Int) {
        NLog.i(TAG, "平行路提示 $index")
    }

    override fun updateAimlessModeCongestionInfo(
        congestionInfo: com.amap.api.navi.model.AimLessModeCongestionInfo?,
    ) {
    }

    override fun updateAimlessModeStatistics(
        statistics: com.amap.api.navi.model.AimLessModeStat?,
    ) {
    }

    override fun onServiceAreaUpdate(serviceAreas: Array<out com.amap.api.navi.model.AMapServiceAreaInfo>?) {
    }

    override fun showCross(cross: com.amap.api.navi.model.AMapNaviCross?) {
        NLog.i(TAG, "路口放大图回调 available=${cross != null}")
    }

    override fun hideCross() {
        NLog.i(TAG, "路口放大图隐藏")
    }

    override fun showModeCross(cross: com.amap.api.navi.model.AMapModelCross?) {
        NLog.i(TAG, "模型路口图回调 available=${cross != null}")
    }

    override fun hideModeCross() {
        NLog.i(TAG, "模型路口图隐藏")
    }

    override fun showLaneInfo(laneInfo: Array<out com.amap.api.navi.model.AMapLaneInfo>?, laneBitmap: ByteArray?, laneBitmap2: ByteArray?) {
        NLog.i(TAG, "车道信息回调 lanes=${laneInfo?.size ?: 0} bitmap=${laneBitmap?.size ?: 0}/${laneBitmap2?.size ?: 0}")
    }

    override fun showLaneInfo(laneInfo: com.amap.api.navi.model.AMapLaneInfo?) {
        NLog.i(TAG, "单车道信息回调 available=${laneInfo != null}")
    }

    override fun hideLaneInfo() {
        NLog.i(TAG, "车道信息隐藏")
    }

    override fun updateCameraInfo(cameraInfo: Array<out com.amap.api.navi.model.AMapNaviCameraInfo>?) {
        NLog.i(TAG, "摄像头信息更新 count=${cameraInfo?.size ?: 0}")
    }

    override fun updateIntervalCameraInfo(
        cameraInfo: com.amap.api.navi.model.AMapNaviCameraInfo?,
        intervalCameraInfo: com.amap.api.navi.model.AMapNaviCameraInfo?,
        index: Int,
    ) {
        NLog.i(TAG, "区间摄像头信息更新 index=$index current=${cameraInfo != null} next=${intervalCameraInfo != null}")
    }

    override fun OnUpdateTrafficFacility(
        trafficFacilityInfo: Array<out com.amap.api.navi.model.AMapNaviTrafficFacilityInfo>?,
    ) {
        NLog.i(TAG, "交通设施更新 count=${trafficFacilityInfo?.size ?: 0}")
    }

    override fun OnUpdateTrafficFacility(
        trafficFacilityInfo: com.amap.api.navi.model.AMapNaviTrafficFacilityInfo?,
    ) {
        NLog.i(TAG, "交通设施更新 available=${trafficFacilityInfo != null}")
    }
}
