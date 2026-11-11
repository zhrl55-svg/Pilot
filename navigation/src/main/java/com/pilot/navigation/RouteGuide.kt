package com.pilot.navigation

/**
 * 路线引导播报器。
 *
 * ## 为什么自己算而不是用高德 SDK 的
 * 高德 SDK 算路被 Key 的**包名+签名绑定**挡住（我们的包名是
 * `com.pilot.agent.debug`，Key 注册在 `com.navagent` 名下 → errorCode=3，
 * 详见 [WebRoutePlanner] 头注释）。所以路线走 Web API 算，
 * `steps` 里的指令文本自己用。
 *
 * 好处是**播报完全可控**：
 * - 走统一 TTS 队列（导航播报能抢占普通播报）
 * - 占住麦克风闸门（TTS 自己的声音不会被 ASR 听成用户指令）
 * - 文本可以按 Pilot 的口语化风格改写（「向西行驶51米右转」→「前方 50 米右转」）
 *
 * ## 进度怎么算（关键）
 * 不用「累计走过距离 vs 步长估算」——那在定位漂移或车速变化时误差很大。
 * 正确做法是**把当前位置投影到路线 polyline 上**：
 * 1. 路线展开成点序列（每步的 polyline 拼接）
 * 2. 找当前位置最近的线段
 * 3. 投影点到线段上，算出「已走过的累计距离」
 * 4. 当前位置落在第几步 → 当前播报这一步
 *
 * 这样定位跳变、绕路、堵车都不会让播报乱掉。
 */
class RouteGuide(
    /** 播报一条导航提示（文本 + 优先级） */
    private val onAnnounce: (String, Int) -> Unit,
) {

    companion object {
        private const val TAG = "PilotNavGuide"

        /** 播报优先级：1=普通导航提示，2=紧急（到达/急转弯） */
        const val PRIORITY_NAV = 1
        const val PRIORITY_URGENT = 2

        /** 提前多少米播「即将转入下一条路」 */
        const val TURN_AHEAD_METERS = 200

        /** 同一段路的最短重复播报间隔（米），防止定位抖动导致反复播同一条 */
        const val MIN_REPEAT_DISTANCE = 30
    }

    // ------------------------------------------------------------ 状态

    private var route: WebRoutePlanner.Route? = null

    /** 展平后的路线点（经度,纬度），带每个点所属的步骤索引 */
    private var points: List<DoubleArray> = emptyList()
    /** 每个点所属的步骤索引 */
    private var pointStep: IntArray = IntArray(0)
    /** 累积距离（米），points[i] 之前走过的距离 */
    private var cumDistance: DoubleArray = DoubleArray(0)

    /** 已播报到第几步 */
    private var announcedStep = -1
    /** 上一次播报时的路线进度（米） */
    private var lastAnnounceAt = -1.0
    /** 是否已播「即将转入」 */
    private var turnAnnounced = false
    /** 是否已播到达 */
    private var arrivedAnnounced = false

    private var lastLat = 0.0
    private var lastLon = 0.0
    private val simulationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var simulationRunnable: Runnable? = null

    // ------------------------------------------------------------ 对外

    /** 设置路线并播报起步提示。 */
    fun start(r: WebRoutePlanner.Route) {
        route = r
        buildIndex(r)
        announcedStep = -1
        lastAnnounceAt = -1.0
        turnAnnounced = false
        arrivedAnnounced = false
        lastLat = 0.0
        lastLon = 0.0

        val km = (r.totalDistance / 1000.0).toInt()
        val minutes = (r.totalDuration / 60.0).toInt().coerceAtLeast(1)
        onAnnounce("开始导航，全程 $km 公里，大约 $minutes 分钟", PRIORITY_NAV)
        // 第一步的指令立刻播
        r.steps.firstOrNull()?.let {
            announcedStep = 0
            onAnnounce(speakable(it.instruction), PRIORITY_NAV)
        }
    }

    /**
     * 位置更新，推进路线并决定要不要播报。
     * @param lat/lon 当前定位
     */
    fun onLocation(lat: Double, lon: Double) {
        val r = route ?: return
        if (lat == 0.0 || lon == 0.0) return
        if (points.isEmpty()) return

        val progress = projectOntoRoute(lat, lon)
        if (progress == null) return
        val (stepIndex, travelled) = progress

        // 到了最后一步之后 → 到达
        if (stepIndex >= r.steps.size) {
            if (!arrivedAnnounced) {
                arrivedAnnounced = true
                onAnnounce("已到达目的地附近", PRIORITY_URGENT)
            }
            return
        }

        // 换了新的一步 → 播这一步的指令
        if (stepIndex != announcedStep && stepIndex > announcedStep) {
            val step = r.steps.getOrNull(stepIndex) ?: return
            // 防抖：位置抖动导致来回跳时不反复播
            if (travelled - lastAnnounceAt >= MIN_REPEAT_DISTANCE) {
                announcedStep = stepIndex
                lastAnnounceAt = travelled
                turnAnnounced = false
                onAnnounce(speakable(step.instruction), PRIORITY_NAV)
            }
            return
        }

        // 同一��：距离下一个转折点不到 200 米 → 提前提醒
        if (!turnAnnounced && stepIndex + 1 < r.steps.size) {
            val next = r.steps[stepIndex + 1]
            val nextRoad = next.road?.takeIf { it.isNotBlank() }
            val remain = r.steps[stepIndex].distance - (travelled - stepStartDistance(stepIndex))
            if (remain <= TURN_AHEAD_METERS && remain > 0) {
                turnAnnounced = true
                val text = if (nextRoad != null) "前方即将进入$nextRoad" else "前方即将转弯"
                onAnnounce(text, PRIORITY_NAV)
            }
        }
    }

    /** 仅用于显式测试模式：沿 Web 路线 polyline 回放定位，验证逐步引导和 TTS。 */
    fun simulatePlayback() {
        simulationRunnable?.let(simulationHandler::removeCallbacks)
        val snapshot = points
        if (snapshot.isEmpty()) {
            NLog.w(TAG, "模拟回放没有路线坐标点")
            return
        }
        val stride = kotlin.math.ceil(snapshot.size / 70.0).toInt().coerceAtLeast(1)
        val indices = (snapshot.indices step stride).toMutableList().apply {
            if (lastOrNull() != snapshot.lastIndex) add(snapshot.lastIndex)
        }
        NLog.i(TAG, "Web 路线模拟回放开始 points=${snapshot.size} samples=${indices.size}")
        var cursor = 0
        val task = object : Runnable {
            override fun run() {
                if (route == null) return
                val p = snapshot[indices[cursor]]
                onLocation(p[1], p[0])
                cursor++
                if (cursor < indices.size) {
                    simulationHandler.postDelayed(this, 500L)
                } else {
                    NLog.i(TAG, "Web 路线模拟回放到达终点")
                    onAnnounce("模拟导航已到达目的地附近", PRIORITY_URGENT)
                    simulationRunnable = null
                }
            }
        }
        simulationRunnable = task
        simulationHandler.post(task)
    }

    /** 停止引导。 */
    fun stop() {
        simulationRunnable?.let(simulationHandler::removeCallbacks)
        simulationRunnable = null
        route = null
        points = emptyList()
        pointStep = IntArray(0)
        cumDistance = DoubleArray(0)
        announcedStep = -1
        lastAnnounceAt = -1.0
        turnAnnounced = false
        arrivedAnnounced = false
        lastLat = 0.0
        lastLon = 0.0
    }

    /** 当前进度（米），给 UI 显示用。 */
    fun travelledMeters(): Double = lastAnnounceAt.coerceAtLeast(0.0)

    /** 当前在第几步（0 起），给 UI 显示用。 */
    fun currentStep(): Int = announcedStep.coerceAtLeast(0)

    /** 剩余距离（米），「还有多远」查询用。 */
    fun remainingMeters(): Int? {
        val r = route ?: return null
        if (points.isEmpty()) return null
        val total = cumDistance.lastOrNull() ?: return null
        return (total - lastAnnounceAt.coerceAtLeast(0.0)).toInt().coerceAtLeast(0)
    }

    /** 剩余时间（秒）：按路线平均速度折算。 */
    fun remainingSeconds(): Int? {
        val r = route ?: return null
        val remain = remainingMeters() ?: return null
        if (r.totalDistance <= 0 || r.totalDuration <= 0) return null
        val speed = r.totalDistance.toDouble() / r.totalDuration   // 米/秒
        if (speed <= 0) return null
        return (remain / speed).toInt().coerceAtLeast(0)
    }

    // ------------------------------------------------------------ 内部

    /** 把 steps 展平成点序列 + 累积距离。 */
    private fun buildIndex(r: WebRoutePlanner.Route) {
        val pts = ArrayList<DoubleArray>()
        val steps = ArrayList<Int>()
        var dist = 0.0
        for ((i, step) in r.steps.withIndex()) {
            for (p in step.polyline) {
                val parts = p.split(",")
                if (parts.size < 2) continue
                val lo = parts[0].toDoubleOrNull() ?: continue
                val la = parts[1].toDoubleOrNull() ?: continue
                if (pts.isNotEmpty()) {
                    val prev = pts.last()
                    dist += distanceMeters(prev[1], prev[0], la, lo)
                }
                pts.add(doubleArrayOf(lo, la))
                steps.add(i)
            }
        }
        points = pts
        pointStep = steps.toIntArray()
        cumDistance = DoubleArray(pts.size)
        var acc = 0.0
        for (i in pts.indices) {
            if (i > 0) {
                val prev = pts[i - 1]
                acc += distanceMeters(prev[1], prev[0], pts[i][1], pts[i][0])
            }
            cumDistance[i] = acc
        }
        NLog.i(TAG, "路线索引：${pts.size} 点 / ${r.steps.size} 步 / 总长 ${acc.toInt()}m")
    }

    /**
     * 把当前位置投影到路线上。
     * @return (步骤索引, 已走过的累计距离)；不在路线附近返回 null
     */
    private fun projectOntoRoute(lat: Double, lon: Double): Pair<Int, Double>? {
        var bestIdx = -1
        var bestDist = Double.MAX_VALUE
        var bestProgress = 0.0
        for (i in points.indices) {
            val p = points[i]
            val d = distanceMeters(lat, lon, p[1], p[0])
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
                bestProgress = cumDistance[i]
            }
        }
        if (bestIdx < 0) return null
        // 离路线太远（>500 米）说明定位漂移或走错路，忽略这次更新
        if (bestDist > 500) return null
        val stepIdx = pointStep.getOrElse(bestIdx) { 0 }
        return stepIdx to bestProgress
    }

    /** 某一步的起始累积距离。 */
    private fun stepStartDistance(stepIndex: Int): Double {
        for (i in pointStep.indices) {
            if (pointStep[i] == stepIndex) return cumDistance[i]
        }
        return 0.0
    }

    /**
     * 把高德的 instruction 改口语化。
     * 「向西行驶51米右转」→「前方 50 米右转」
     */
    private fun speakable(instruction: String): String {
        var s = instruction.trim()
        // 「行驶51米」→ 「50 米」
        s = s.replace(Regex("行驶(\\d+)米"), "$1 米")
        // 去掉「，」换成「，」保持可读
        return s
    }

    /** 距离（米），Haversine。 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
