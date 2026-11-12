package com.pilot.navigation

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.enums.MapStyle
import com.pilot.navigation.NavigationSettings.Companion.LEVEL_BRIEF
import com.pilot.navigation.NavigationSettings.Companion.LEVEL_DETAILED
import com.pilot.navigation.NavigationSettings.Companion.LEVEL_NORMAL

/**
 * 导航界面。
 *
 * ## 两套 UI 的关系
 * - **高德内置 UI**（转向图、车道线、路况条、限速牌、红绿灯倒计时）
 *   —— 这些自己做成本极高，直接用官方的
 * - **Pilot 自己的按钮**（语音 / 返回主界面 / 退出 App）
 *   —— 浮在高德 UI 之上，用 [setCrossLocation] 把高德 HUD 挤到上半屏
 *
 * ## 高德底部按钮为什么之前"点了没作用"
 * 高德的「退出 / 设置」按钮不是自己弹菜单的，而是**回调给宿主 App**：
 * - 「退出」→ [AMapNaviViewListener.onNaviCancel]
 * - 「设置」→ [AMapNaviViewListener.onNaviSetting]
 * 不实现这两个回调，点了就什么都不会发生（实测踩过）。
 */
class NavActivity : androidx.activity.ComponentActivity(), AMapNaviViewListener {

    companion object {
        private const val TAG = "PilotNav"

        /**
         * 初始缩放级别（数字越大越近）。
         *
         *   17f = 车道级（导航跟随时）
         *   15f = 街道级（能看清路口和小区名）
         *   14f = 街区级（周边几个街区）
         *   13f = 街道片区（能看到相邻几条路）
         *   **12f = 城区级（周边多个街区和备选道路，老板要的「初始页」）**
         *   11f = 城区级
         *
         * 老板反馈「初始页显示比例过大，只能看到一条路」：
         * 14f 在城区只能看到当前街道，周边没有参照物。降到 12f 后
         * 一次能看到几个街区，用户更容易判断朝向和周边路网。
         */
        private const val INITIAL_ZOOM = 12f

        const val EXTRA_DEST = "dest"
        const val EXTRA_SIMULATE = "simulate"

        /** 当前活着的界面（到达自动退出时用）。 */
        @Volatile private var current: NavActivity? = null

        /** 关闭导航界面（到达自动退出时调）。 */
        fun closeAll() {
            runCatching { current?.finish() }
        }

        /** 拉起导航界面（已在前台就不重复拉）。 */
        fun show(context: Context, destination: String) {
            val intent = Intent(context, NavActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_DEST, destination)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { NLog.w(TAG, "拉起导航界面失败 ${it.message}") }
        }
    }

    private var naviView: AMapNaviView? = null

    /** 是否已经把镜头挪到当前位置（只挪一次，之后交给导航引擎跟随）。 */
    private var cameraMoved = false

    /** Pilot 自己的按钮栏。 */
    private var pilotBar: LinearLayout? = null

    /** 转弯提示框下方的搜索面板。 */
    private var searchPanel: SearchPanel? = null

    /** 高德退出导航时会紧接着发 onAMapNaviViewExit；等用户确认前不能 finish Activity。 */
    @Volatile private var exitConfirmationPending = false

    /** 根容器（Compose 设置面板要挂到这上面，不能挂 Dialog）。 */
    private var contentRoot: FrameLayout? = null

    /** 设置面板的 ComposeView（关闭时移除）。 */
    private var settingsComposeView: androidx.compose.ui.platform.ComposeView? = null

    /** 当前已应用的地图日夜模式（null = 还没设置过）。 */
    private var appliedNightMode: Boolean? = null

    /** 空闲地图上的当前位置蓝色箭头。 */
    private var locationMarker: Marker? = null

    /** 蓝色箭头图标（只画一次，之后复用）。 */
    private val locationArrowIcon by lazy { buildLocationArrowIcon() }

    /** 日夜模式定时检查（一分钟一次，跨过日出/日落时自动切换）。 */
    private val dayNightHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dayNightTick = object : Runnable {
        override fun run() {
            applyDayNightMode()
            dayNightHandler.postDelayed(this, 60_000L)
        }
    }

    /** 上一次看到的导航状态（用来在开始/结束导航时切换搜索面板）。 */
    private var lastNavState: NavigationService.State? = null

    /** 导航状态轮询（搜索框只在没有路线时显示）。 */
    private val navStateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val navStateTick = object : Runnable {
        override fun run() {
            syncSearchPanelWithNavState()
            navStateHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NLog.attach(filesDir)
        current = this
        NLog.i(TAG, "NavActivity onCreate")

        // ★ 保证 NavigationService 在跑。
        //   从主界面「导航」进来时，主进程可能还没 bind 过导航服务，
        //   这时 NavigationService.instance 是 null → 搜索面板选定地点后
        //   `peek()?.startNaviFromOuter()` 静默失败（**选定不导航的 bug 就是它**）。
        //   startService 会触发 onCreate，instance 立刻有值。
        runCatching {
            startService(Intent(this, NavigationService::class.java))
            NLog.i(TAG, "已确保 NavigationService 运行")
        }.onFailure { NLog.w(TAG, "启动 NavigationService 失败 ${it.message}") }

        // 导航时屏幕常亮（开车看路线，不能灭屏）
        if (NavigationSettings.load(this).keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // ★ 高德合规接口：必须在任何 SDK 调用之前（不调会崩/不给数据）
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            MapsInitializer.initialize(this)
        }.onFailure { NLog.w(TAG, "Maps 初始化失败 ${it.message}") }

        val root = FrameLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setContentView(root)
        contentRoot = root

        runCatching {
            val v = AMapNaviView(this)
            v.onCreate(savedInstanceState)
            // 默认关闭倒计时气泡会导致路口只显示信号灯图标，没有秒数。
            runCatching {
                v.setTrafficLightsVisible(true)
                v.setShowTrafficLightView(true)
                NLog.i(TAG, "路线红绿灯与倒计时气泡已开启")
            }.onFailure { NLog.w(TAG, "开启红绿灯倒计时显示失败 ${it.message}") }
            // ★ 必须注册 listener——否则「退出/设置」按钮点了没反应
            v.setAMapNaviViewListener(this)
            root.addView(v)
            naviView = v

            // 让出底部空间给 Pilot 的按钮栏（高德 HUD 只画在黄线上方）
            runCatching {
                val opts = v.viewOptions
                opts.isLayoutVisible = true       // 用高德内置转向图/路况条
                opts.isAutoLockCar = true         // 车标锁在中间，地图随车平移
                opts.isAutoChangeZoom = true
                opts.isTrafficBarEnabled = true   // 右侧路况条
                opts.isSettingMenuEnabled = true  // 高德自己的设置菜单
                opts.zoom = INITIAL_ZOOM.toInt()  // 初始页先给城区视角

                // ★ 地图日夜：不用 SDK 的 auto 模式。
                //   auto 在真机上不可靠：刚进界面定位没就绪时先判白天，
                //   之后不纠正（20:10 实测还是白底）。改由 [applyDayNightMode]
                //   按定位的日出日落自己切，见 [NavDayNight]。
                opts.isAutoNaviViewNightMode = false
            }.onFailure { NLog.w(TAG, "viewOptions 设置失败 ${it.message}") }

            // 地图渲染完成后再套一次日夜（创建时 map 对象可能还没准备好）
            runCatching {
                v.setOnMapLoadedListener { applyDayNightMode(force = true) }
            }.onFailure { NLog.w(TAG, "设置地图加载回调失败 ${it.message}") }
            applyDayNightMode(force = true)

            NLog.i(TAG, "AMapNaviView 创建完成")
        }.onFailure {
            NLog.e(TAG, "AMapNaviView 创建失败", it)
        }

        // ★ 转弯提示框下方的搜索面板（老板要求）
        //   语音识别到地址会自动填进来，也可以手打；
        //   停下 600ms 自动搜，列 5 条候选，说「第2个」或点一下就开始导航。
        runCatching {
            val panel = SearchPanel(
                activity = this,
                onPick = { c ->
                    NLog.i(TAG, "搜索面板选定：${c.name} (${c.lat},${c.lon})")
                    startNaviWithRetry(c.name, attempt = 0)
                },
                onSpeak = { text -> announceLocal(text) },
                onEndNavi = {
                    // 语音说「结束导航」= 停导航保留地图（和点确认框「结束导航」一致）
                    NLog.i(TAG, "语音结束导航，保留地图界面")
                    endNaviKeepMap()
                    finish()
                },
            )
            searchPanel = panel
            root.addView(panel.root)
            // 有路线时先把搜索框收起来，等导航结束或用户点语音再显示
            syncSearchPanelWithNavState(force = true)
            navStateHandler.post(navStateTick)
            // 界面上屏后很快开始轮询语音输入（点麦克风就能用，不用等 5 秒）
            root.postDelayed({ startAsrPolling() }, 500)
        }.onFailure { NLog.e(TAG, "创建搜索面板失败", it) }

        // Pilot 自己的按钮栏
        root.addView(buildPilotBar())

        // 日夜模式：先立即判一次，之后每分钟检查是否跨过日出/日落
        dayNightHandler.post(dayNightTick)

        // 显式设备测试入口：adb am start --es dest ... --ez simulate true
        val testDestination = intent.getStringExtra(EXTRA_DEST).orEmpty().trim()
        if (intent.getBooleanExtra(EXTRA_SIMULATE, false) && testDestination.isNotBlank()) {
            NLog.i(TAG, "收到模拟导航测试请求 destination=$testDestination")
            root.postDelayed({ startNaviWithRetry(testDestination, attempt = 0, simulated = true) }, 1800L)
        }

        // ★ 地图默认停在北京市——必须手动 moveCamera 到当前位置。
        //
        //   **踩坑**：只挂 `loc.onLocation` 回调不够，原因有两个：
        //   1. `startService()` 是**异步**的——服务 onCreate 比这行代码晚几十毫秒，
        //      这时 `peekLocationProvider()` 还是 null
        //   2. 就算服务起来了，定位器也是**懒创建**的，没人调 startNavi 就不启动
        //   结果：地图永远停在北京市（老板反馈）。
        //
        //   所以：立刻试一次 + 起轮询兜底（最多 10 秒）。
        val loc = NavigationService.peekLocationProvider()
        if (loc != null) {
            loc.onLocation = { lat, lon -> centerOnMe(lat, lon) }
            centerOnMe(loc.lat, loc.lon)   // 位置可能早就在了，立刻挪
        } else {
            NLog.i(TAG, "导航服务还没就绪，等它起来再挪镜头")
        }
        if (!cameraMoved) {
            pollCenterOnMe(attempt = 0)
        }
    }

    /** 把镜头挪到指定坐标（只挪一次）。 */
    private fun centerOnMe(lat: Double, lon: Double) {
        if (lat == 0.0 || lon == 0.0) return
        // 每次定位都更新蓝色箭头；镜头只挪第一次
        updateLocationMarker(lat, lon, NavigationService.peekLocationProvider()?.bearing ?: 0f)
        // 定位一到位就按当地日出日落重新判日夜（之前可能用的是 18:00 兜底）
        applyDayNightMode()
        if (cameraMoved) return
        cameraMoved = true
        runCatching {
            naviView?.map?.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    com.amap.api.maps.model.LatLng(lat, lon), INITIAL_ZOOM,
                ),
            )
            NLog.i(TAG, "地图挪到当前位置 $lat,$lon zoom=$INITIAL_ZOOM")
        }.onFailure {
            cameraMoved = false   // 失败允许重试
            NLog.w(TAG, "moveCamera 失败 ${it.message}")
        }
    }

    /**
     * 轮询等定位就绪（最多 10 秒，每 500ms 一次）。
     *
     * 服务冷启动 + 定位首帧都要时间。用户不能对着北京地图干等，
     * 所以这边主动轮询，一拿到坐标就挪镜头。
     */
    private fun pollCenterOnMe(attempt: Int) {
        if (cameraMoved || attempt >= 20) {
            if (!cameraMoved) NLog.w(TAG, "等定位超时，地图仍在默认位置")
            return
        }
        window.decorView.postDelayed({
            if (cameraMoved) return@postDelayed
            val loc = NavigationService.peekLocationProvider()
            if (loc != null && loc.lat != 0.0 && loc.lon != 0.0) {
                centerOnMe(loc.lat, loc.lon)
            } else {
                pollCenterOnMe(attempt + 1)
            }
        }, 500)
    }

    // ------------------------------------------------------------ 当前位置箭头

    /**
     * 在没有路线的空闲地图上，用蓝色箭头标出当前位置。
     *
     * 高德只有进入导航后才会画车标；初始页之前只有地图本身，
     * 老板不知道车/人在哪。这里自己放一个 Marker，按定位朝向旋转。
     * 进入导航后隐藏，交给高德自己的车标。
     */
    private fun updateLocationMarker(lat: Double, lon: Double, bearing: Float) {
        if (lat == 0.0 || lon == 0.0) return
        val map = naviView?.map ?: return
        runCatching {
            val pos = LatLng(lat, lon)
            val marker = locationMarker ?: map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.fromBitmap(locationArrowIcon))
                    .anchor(0.5f, 0.55f)
                    .setFlat(true)
                    .zIndex(100f),
            )?.also { locationMarker = it } ?: return
            marker.setPosition(pos)
            marker.setRotateAngle(bearing)
            marker.setVisible(!isRouteActive())
        }.onFailure { NLog.w(TAG, "更新当前位置箭头失败 ${it.message}") }
    }

    /** 是否正在算路/导航（此时用高德自己的车标）。 */
    private fun isRouteActive(): Boolean {
        val state = NavigationService.peek()?.currentState ?: return false
        return state == NavigationService.State.ROUTING ||
            state == NavigationService.State.NAVIGATING
    }

    /** 画一个带白边的蓝色箭头（朝上，正北）。 */
    private fun buildLocationArrowIcon(): Bitmap {
        val density = resources.displayMetrics.density
        val size = (30 * density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = size.toFloat()
        val h = size.toFloat()
        val arrow = Path().apply {
            moveTo(w * 0.50f, h * 0.06f)
            lineTo(w * 0.90f, h * 0.93f)
            lineTo(w * 0.50f, h * 0.72f)
            lineTo(w * 0.10f, h * 0.93f)
            close()
        }
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#2F7BFF")
            },
        )
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                strokeJoin = Paint.Join.ROUND
                color = Color.WHITE
            },
        )
        return bitmap
    }

    // ------------------------------------------------------------ 日夜 / 搜索框

    /**
     * 按定位所在地的日出日落切换底图和导航引导层。
     *
     * @param force true = 不管缓存，强制重新套一遍（地图加载完成时用）
     */
    private fun applyDayNightMode(force: Boolean = false) {
        val night = runCatching {
            val loc = NavigationService.peekLocationProvider()
            NavDayNight.isNight(loc?.lat ?: 0.0, loc?.lon ?: 0.0)
        }.getOrDefault(false)
        val v = naviView ?: return
        val target = if (night) MapStyle.NIGHT else MapStyle.DAY
        // 高德在开始导航时会自己重建渲染层，可能把样式改回白天；
        // 所以不仅看缓存，还看 SDK 当前的实际样式。
        val alreadyApplied = v.viewOptions.mapStyle == target && v.viewOptions.isNaviNight == night
        if (!force && appliedNightMode == night && alreadyApplied) return
        runCatching {
            val opts = v.viewOptions
            // 手动模式：auto 会和 setMapStyle 打架，必须关掉
            opts.isAutoNaviViewNightMode = false
            opts.isNaviNight = night
            opts.setMapStyle(target, "")
            v.setViewOptions(opts)
            appliedNightMode = night
            NLog.i(TAG, "地图日夜模式 → ${if (night) "夜间" else "白天"}")
        }.onFailure { NLog.w(TAG, "切换日夜模式失败 ${it.message}") }
    }

    /**
     * 搜索框只在「没有路线」时显示。
     *
     * 老板反馈「开始导航后还显示搜索框」——它会挡住路线和转向提示。
     * 导航中用户点右下角语音按钮时，[onVoiceButton] 会单独把它叫出来。
     */
    private fun syncSearchPanelWithNavState(force: Boolean = false) {
        val state = NavigationService.peek()?.currentState ?: return
        if (!force && state == lastNavState) return
        lastNavState = state
        // 开始导航时 SDK 会重建渲染层，顺手把日夜样式再套一次
        applyDayNightMode(force = true)
        val show = state == NavigationService.State.IDLE ||
            state == NavigationService.State.FAILED ||
            state == NavigationService.State.ARRIVED
        searchPanel?.setVisible(show)
        // 空闲时显示自己的蓝色箭头；导航中隐藏，用高德车标
        locationMarker?.setVisible(show)
        if (show) {
            NavigationService.peekLocationProvider()?.let { loc ->
                updateLocationMarker(loc.lat, loc.lon, loc.bearing)
            }
        }
        NLog.i(TAG, "导航状态 $state → 搜索框${if (show) "显示" else "隐藏"}")
    }

    // ------------------------------------------------------------ Pilot 按钮栏

    /**
     * Pilot 自定义按钮栏（浮在高德 UI 上方，避开底部导航信息区）。
     *
     * 五个按钮：
     * - **语音**：回到主界面开语音识别（开车时手不能离开方向盘，语音是主交互）
     * - **路线 / 历史**：切换候选路线、查看历史
     * - **主页**：回到 Pilot 主界面（导航继续在后台跑）
     * - **收起**：退到后台（导航继续跑），不关闭 App
     */
    /**
     * Pilot 自定义按钮。
     *
     * ## 位置很关键（踩过）
     * 第一版放在**顶部中间**——结果把高德的「左转 51米 进入白塔街」
     * 整个挡住了。高德的 UI 分区是：
     * - 顶部：转向提示 + 路口放大图（**绝对不能挡**）
     * - 右侧：路况条 + 全览按钮
     * - 底部：「退出 / 剩余距离时间 / 设置」三栏
     *
     * 所以我们的按钮只能放在**中间偏下的空白区**：
     * - 语音按钮：**左下**（单手拇指最容易够到，开车时盲按也不容易错）
     * - 主页 / 收起：**右侧竖排**（低频操作，放边上）
     */
    private fun buildPilotBar(): View {
        val wrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isClickable = false
        }

        // ★ 右侧竖排四个按钮（老板要求：语音移到最上，四个统一底色和圆边框）
        //   顺序：语音 / 路线 / 历史 / 主页 / 收起
        //   全部用同一个 FLOATING_BTN 底色 + 同一个圆角，视觉是一组。
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        side.addView(buildVoiceButton())
        side.addView(roundButton("🛣") { showRouteChoices() })
        side.addView(roundButton("🕘") { showHistoryDialog() })
        side.addView(roundButton("🏠") { onHomeButton() })
        side.addView(roundButton("✕") { onMinimizeApp() })

        wrap.addView(side, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            rightMargin = dp(8)
        })

        // 覆盖高德底栏左侧退出热区：部分 SDK 页面直接触发 onAMapNaviViewExit，
        // 不经过 onNaviCancel，宿主无法显示“结束导航/取消”。保留原样式，只接管点击。
        wrap.addView(View(this).apply {
            contentDescription = "退出导航"
            isClickable = true
            setOnClickListener {
                val active = NavigationService.peek()?.currentState in
                    setOf(NavigationService.State.ROUTING, NavigationService.State.NAVIGATING)
                if (active) requestEndNaviConfirmation() else onAMapNaviViewExit()
            }
        }, FrameLayout.LayoutParams(dp(74), dp(58)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(4)
            bottomMargin = dp(9)
        })

        pilotBar = side
        return wrap
    }

    /**
     * 统一的圆形按钮（四个都用它）。
     *
     * 老板要求「四个按钮统一底色和圆边框」：
     * 同底色 [NavColors.FLOATING_BTN]、同圆角半径、同尺寸、同间距。
     */
    private fun roundButton(icon: String, onClick: () -> Unit): Button {
        val size = dp(NavColors.FLOATING_BTN_SIZE_DP)
        val radius = dp(NavColors.FLOATING_BTN_RADIUS_DP).toFloat()
        return Button(this).apply {
            text = icon
            // 底色换成中灰后，浅色字看不清；图标改深色
            setTextColor(Color.parseColor("#212121"))
            textSize = 18f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(NavColors.FLOATING_BTN)
                cornerRadius = radius
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = dp(10)
            }
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
    }

    /** 导航界面上的悬浮按钮（深底浅字）。 */
    /**
     * 悬浮按钮（灰色 + 高透明）。
     *
     * 老板要求「底色全部灰色并提高透明度」：
     * 之前用 #CC1A1A1A（近黑 80% 不透明），压在地图上很重。
     * 改成 #8C9E9E9E（中灰 55% 透明）——能看清图标，又不抢地图。
     */
    private fun pillButton(text: String, width: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#8C9E9E9E"))
                cornerRadius = dp(22).toFloat()
            }
            val lp = LinearLayout.LayoutParams(width, dp(44)).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
            stateListAnimator = null   // 去掉按钮默认的阴影/抬升，免得跟地图打架
            setOnClickListener { onClick() }
        }

    /**
     * 语音按钮（状态图标版）。
     *
     * 老板要求「取消文字，播报时显示小喇叭动态、录音时麦克风动态、平时显示语音」。
     *
     * 三种状态：
     * - **平时** 🎤 静态（提示可以说话）
     * - **录音中** 🎤 呼吸动画（缩放到 1.15 再回来）
     * - **播报中** 🔈 跳动动画（提示正在出声，别说话）
     *
     * 状态从主进程的 `files/voice_state.json` 轮询（跨进程，见 VoiceState）。
     */
    private fun buildVoiceButton(): Button {
        // 用统一的 roundButton，保证和另外三个长得一样
        val btn = roundButton("🎤") { onVoiceButton() }
        // 呼吸/跳动动画：只改 scale，不动 layout（避免触发重新布局）
        voiceAnim = android.animation.ObjectAnimator.ofFloat(btn, "scaleX", 1f, 1.18f).apply {
            duration = 600
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        voiceBtn = btn
        startVoiceStatePolling()
        return btn
    }

    /** 语音按钮 + 动画（状态轮询里用）。 */
    private var voiceBtn: Button? = null
    private var voiceAnim: android.animation.ObjectAnimator? = null
    private var lastVoiceState: String = ""

    /**
     * 轮询语音状态（播报中 / 录音中 / 空闲）。
     *
     * ## 为什么轮询而不是推送
     * 状态产生在主进程（TTS/ASR），导航界面在 `:navigation` 进程。
     * 推的话要额外做一套 AIDL 回调 + 生命周期管理；
     * 而这是**每秒一次读一个小文件**的事，轮询简单得多，开销可忽略
     * （文件几百字节，readText 不到 1ms）。
     */
    private fun startVoiceStatePolling() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val st = VoiceState.read(this@NavActivity)
                if (st != lastVoiceState) {
                    lastVoiceState = st
                    applyVoiceState(st)
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable)
    }

    private fun applyVoiceState(state: String) {
        val btn = voiceBtn ?: return
        when (state) {
            VoiceState.SPEAKING -> {
                btn.text = "🔈"
                voiceAnim?.start()
            }
            VoiceState.RECORDING -> {
                btn.text = "🎤"
                voiceAnim?.start()
            }
            else -> {
                btn.text = "🎤"
                voiceAnim?.cancel()
                btn.scaleX = 1f
                btn.scaleY = 1f   // 恢复原大小，动画只动 scaleX
            }
        }
        NLog.i(TAG, "语音按钮状态：$state")
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    /**
     * 发起导航（带重试）。
     *
     * ★ 为什么需要重试：从主界面直接点「导航」进来时，
     *   NavigationService 可能刚被 startService 拉起来，`instance` 还没赋值
     *   （onCreate 在另一个线程跑）。这时 `peek()` 是 null，
     *   直接调就是静默失败——**老板报的「选定后没有规划路线」就是这个**。
     *   重试 3 次、每次 300ms，覆盖服务冷启动。
     */
    private fun startNaviWithRetry(destination: String, attempt: Int, simulated: Boolean = false) {
        // 一开始规划路线就把搜索框收起，别挡住转向提示
        searchPanel?.setVisible(false)
        val svc = NavigationService.peek()
        if (svc != null) {
            val jobId = runCatching {
                if (simulated) svc.startSimulatedNaviFromOuter(destination)
                else svc.startNaviFromOuter(destination, 0)
            }.getOrDefault(0L)
            NLog.i(TAG, "发起${if (simulated) "模拟" else "真实"}导航「$destination」jobId=$jobId（第 ${attempt + 1} 次尝试）")
            if (jobId != 0L) {
                toast("正在规划去$destination 的路线")
                return
            }
        } else {
            NLog.w(TAG, "NavigationService 还没就绪（第 ${attempt + 1} 次）")
        }
        if (attempt < 3) {
            window.decorView.postDelayed({
                startNaviWithRetry(destination, attempt + 1, simulated)
            }, 300)
        } else {
            NLog.e(TAG, "发起导航失败：服务一直没就绪")
            toast("导航服务没起来，请重开导航界面")
        }
    }

    /** 让主进程播一句话（搜索候选播报、选定确认都用它）。 */
    private fun announceLocal(text: String) {
        runCatching { AgentBridge.speak(this, text) }
    }

    /**
     * 轮询语音识别结果 → 喂给搜索面板。
     *
     * 为什么单独一个轮询而不是塞进 VoiceState 那个：
     * 那个只管按钮图标（1 秒一次够了）；
     * 识别结果要**及时**（用户说完马上就要填进搜索框并搜），
     * 所以这个 400ms 一次。反正只是读一个小文件。
     */
    private fun startAsrPolling() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                runCatching { searchPanel?.pollVoiceInput() }
                handler.postDelayed(this, 400)
            }
        }
        handler.post(runnable)
    }

    /**
     * 语音按钮：**在导航界面里直接开始语音识别**（不跳走）。
     *
     * 老板要求按钮要显示麦克风/喇叭动画，说明语音交互就发生在这个界面——
     * 所以不能让用户切回主界面（切走就看不到路线了）。
     * 主进程识别完把结果写进 VoiceState，这里轮询到就填进搜索框。
     */
    private fun onVoiceButton() {
        NLog.i(TAG, "点击语音按钮，开始识别")
        // 导航中搜索框默认收起；点语音时临时叫出来，方便看识别结果和候选
        val active = NavigationService.peek()?.currentState in
            setOf(NavigationService.State.ROUTING, NavigationService.State.NAVIGATING)
        if (active) searchPanel?.setVisible(true)
        runCatching { AgentBridge.startVoice(this) }
        toast("请说地点名，或说「第几个」")
    }

    /** 主页按钮：回主界面，导航继续。 */
    private fun onHomeButton() {
        NLog.i(TAG, "点击主页按钮")
        runCatching {
            val i = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        }.onFailure { NLog.w(TAG, "回桌面失败 ${it.message}") }
    }

    /** 收起 App：退到后台，导航继续跑（老板要求 ✕ 不再是「杀进程」）。 */
    private fun onMinimizeApp() {
        NLog.i(TAG, "点击 ✕ → 退到后台（导航继续）")
        // 不调 requestStop：用户只是想把界面收起来，
        // 后台服务还在导航，转向播报不能断。
        runCatching { moveTaskToBack(true) }
            .onFailure { NLog.w(TAG, "退到后台失败 ${it.message}") }
    }

    // ------------------------------------------------------------ 高德界面回调

    /**
     * 「退出导航」按钮（高德底部的那个）。
     *
     * ★ 之前点了没作用——因为没实现这个回调。
     *   高德的按钮只负责「通知宿主」，具体做什么由我们自己决定。
     */
    override fun onNaviCancel() {
        // 高德自己的「退出」回调：导航中弹确认（结束导航、保留地图），
        // 没在导航时直接彻底退出（关界面 + 释放 SDK）
        if (isNaviActive()) requestEndNaviConfirmation() else onAMapNaviViewExit()
    }

    /** 当前是否真的在导航/算路（决定「退出」是结束导航还是彻底退出）。 */
    private fun isNaviActive(): Boolean {
        val st = NavigationService.peek()?.currentState
        return st == NavigationService.State.ROUTING || st == NavigationService.State.NAVIGATING
    }

    private fun requestEndNaviConfirmation() {
        if (exitConfirmationPending || isFinishing || isDestroyed) return
        exitConfirmationPending = true
        NLog.i(TAG, "导航退出请求 → 宿主显示结束/取消确认")
        showEndNaviConfirm()
    }

    /**
     * 「结束导航」确认框。
     *
     * 用系统 AlertDialog（不用 Compose）——这里要**赶在高德销毁 Activity 之前**
     * 弹出来，Compose 首次初始化要几十毫秒，可能来不及。
     */
    private fun showEndNaviConfirm() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("结束导航")
            .setMessage("要结束当前导航吗？")
            .setPositiveButton("结束导航") { _, _ ->
                NLog.i(TAG, "用户确认结束导航")
                exitConfirmationPending = false
                // 导航中的退出 = 结束导航、地图保留（重建空地图界面）
                endNaviKeepMap()
                finish()
            }
            .setNegativeButton("取消") { _, _ ->
                NLog.i(TAG, "用户取消结束导航，继续当前路线")
                exitConfirmationPending = false
            }
            .setOnCancelListener {
                NLog.i(TAG, "用户关闭结束导航确认框，继续当前路线")
                exitConfirmationPending = false
            }
            .show()
    }

    override fun onAMapNaviViewExit() {
        if (exitConfirmationPending) {
            NLog.i(TAG, "忽略高德退出回调，等待用户选择结束或取消")
            return
        }
        NLog.i(TAG, "高德界面：视图退出")
        stopNaviAndFinish()
    }

    /**
     * 高德底部「设置」按钮 → 弹 Pilot 的导航设置面板。
     *
     * ## ★ 为什么要延迟 350ms
     * 高德点「设置」后会**先把自己的界面切到浏览模式**
     * （底部三栏变成「退出 / 继续导航 / 设置」，右侧弹出路况/刷新/缩放）。
     * 这个切换会重建 AMapNaviView 的渲染层，
     * 我们立刻弹 AlertDialog 会被它顶掉——**dialog 一闪就没**（实测）。
     *
     * 所以等它切完再弹。350ms 是实测能稳定弹出来的值
     * （200ms 偶尔还会被吞）。
     */
    override fun onNaviSetting() {
        NLog.i(TAG, "高德界面：设置（延迟 350ms 弹面板，避开高的 UI 切换）")
        window.decorView.postDelayed({
            runCatching { showSettingsDialog() }
                .onFailure { NLog.e(TAG, "弹设置面板失败", it) }
        }, 350)
    }

    override fun onStopSpeaking() {
        NLog.i(TAG, "高德界面：停止播报")
        runCatching { AgentBridge.stopSpeaking(this) }
    }

    override fun onNaviBackClick(): Boolean {
        // 返回键：结束导航但保留地图（和语音「结束导航」一致）
        endNaviKeepMap()
        finish()
        return true
    }

    override fun onNaviMapMode(mode: Int) = Unit
    override fun onNaviTurnClick() = Unit
    override fun onNextRoadClick() = Unit
    override fun onScanViewButtonClick() = Unit
    override fun onLockMap(isLock: Boolean) = Unit
    override fun onNaviViewLoaded() {
        NLog.i(TAG, "AMapNaviView 加载完成")
        applyDayNightMode(force = true)
    }
    override fun onMapTypeChanged(type: Int) = Unit
    override fun onNaviViewShowMode(mode: Int) = Unit
    override fun onViewTypeChanged(type: AmapPageType?) = Unit
    override fun onStrategyChanged(strategy: Int) {
        NLog.i(TAG, "路线策略变更 $strategy")
    }
    override fun onBroadcastModeChanged(mode: Int) = Unit
    override fun onDayAndNightModeChanged(mode: Int) {
        // 0=白天 1=夜间（高德按定位所在地的日出日落自动切）
        NLog.i(TAG, "地图日夜模式切换 mode=$mode")
    }
    override fun onScaleAutoChanged(auto: Boolean) = Unit
    override fun onListenToVoiceDuringCallChanged(listen: Boolean) = Unit
    override fun onControlMusicVolumeModeChanged(mode: Int) = Unit
    override fun onEagleChanged(enabled: Boolean) = Unit
    override fun onNaviRouteHighlightChange(routeId: Long, mode: Int) = Unit

    /**
     * 导航历史面板（右侧 🕘 按钮）。
     *
     * 从这里能：点一条直接再去、改别名、删掉。
     * 和设置面板里的历史列表是同一份数据（NavigationSettings.history）。
     */
    private fun showHistoryDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NavColors.SURFACE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(NavColors.SURFACE)
        }
        scroll.addView(box)

        /**
         * 整块列表重画。
         *
         * 之前删除只改了设置文件，弹窗里的 view 是建的时候就定死的，
         * 点「删除」会 toast「已删除」但那一行还在。每次操作后按最新设置重建。
         */
        fun renderBox() {
            box.removeAllViews()
            val hist = NavigationSettings.load(this@NavActivity).history
            if (hist.isEmpty()) {
                box.addView(TextView(this@NavActivity).apply {
                    text = "还没有导航记录"
                    setTextColor(NavColors.ON_SURFACE)
                    textSize = 14f
                    setPadding(dp(12), dp(16), dp(12), dp(16))
                })
                return
            }
            for (h in hist.take(15)) {
                val title = if (h.alias.isNotBlank()) "${h.alias}（${h.name}）" else h.name
                val row = LinearLayout(this@NavActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        setColor(NavColors.SURFACE_VARIANT)
                        cornerRadius = dp(10).toFloat()
                    }
                    isClickable = true
                    setOnClickListener {
                        NLog.i(TAG, "历史点选：$title")
                        startNaviWithRetry(h.alias.ifBlank { h.name }, 0)
                    }
                }
                row.addView(TextView(this@NavActivity).apply {
                    text = title
                    setTextColor(NavColors.ON_SURFACE)
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@NavActivity).apply {
                    text = "删除"
                    setTextColor(NavColors.ERROR)
                    textSize = 13f
                    setPadding(dp(10), 0, dp(4), 0)
                    isClickable = true
                    setOnClickListener {
                        // 只处理删除，别让点击冒泡到整行（那会直接开始导航）
                        val cur = NavigationSettings.load(this@NavActivity)
                        NavigationSettings.save(this@NavActivity, cur.copy(history = cur.history - h))
                        NLog.i(TAG, "删除历史：${h.name}")
                        toast("已删除")
                        renderBox()
                    }
                })
                box.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) })
            }
        }

        renderBox()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("导航历史")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 展示高德 SDK 当前可用的备选路线并切换主路线。 */
    private fun showRouteChoices() {
        val routes = NavigationService.peek()?.routeOptions().orEmpty()
        if (routes.isEmpty()) {
            toast("当前没有可选备选路线")
            return
        }
        val labels = routes.mapIndexed { index, route ->
            val km = "%.1f 公里".format(route.distanceMeters / 1000.0)
            val minutes = ((route.durationSeconds + 59) / 60).coerceAtLeast(1)
            "路线 ${index + 1}　$km　约 $minutes 分钟"
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择路线")
            .setItems(labels) { dialog, which ->
                val selected = routes[which]
                if (NavigationService.peek()?.selectRoute(selected.pathId) == true) {
                    toast("已切换到路线 ${which + 1}")
                } else {
                    toast("路线切换失败")
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 设置面板（Compose）

    /**
     * 导航设置（Compose 版）。
     *
     * ★ 用 Compose 而不是传统 View 的原因：
     *   老板要求「用主界面菜单的开关样式」——主界面是 Compose + Material3，
     *   只有用同一个 `Switch` 组件才能完全一致。
     *   传统 View 的 android.widget.Switch 在这个 ROM 的深色主题下看不见。
     */
    private fun showSettingsDialog() {
        val state = androidx.compose.runtime.mutableStateOf(NavigationSettings.load(this))
        var dialog: android.app.Dialog? = null

        fun reload() {
            state.value = NavigationSettings.load(this)
        }

        val cv = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                NavSettingsDialog(
                    settings = state.value,
                    onDismiss = { closeSettings() },
                    onLevelChange = { lvl ->
                        val cur = NavigationSettings.load(this@NavActivity)
                        NavigationSettings.save(this@NavActivity, cur.copy(broadcastLevel = lvl))
                        NLog.i(TAG, "播报档位 → $lvl")
                        reload()
                    },
                    onToggle = { key, v ->
                        val cur = NavigationSettings.load(this@NavActivity)
                        val next = when (key) {
                            "arriveAutoExit" -> cur.copy(arriveAutoExit = v)
                            "keepScreenOn" -> cur.copy(keepScreenOn = v)
                            "avoidJam" -> cur.copy(avoidJam = v)
                            "autoZoom" -> cur.copy(autoZoom = v)
                            else -> cur
                        }
                        NavigationSettings.save(this@NavActivity, next)
                        if (key == "autoZoom") {
                            runCatching { naviView?.viewOptions?.isAutoChangeZoom = v }
                        }
                        if (key == "keepScreenOn") {
                            if (v) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        reload()
                    },
                    onAddFavorite = {
                        // 添加流程还是用原来的 View 对话框（要输入框，Compose 里再写一套不值）
                        // ★ this@NavActivity：lambda 里的 this 是 ComposeView
                        val box = LinearLayout(this@NavActivity)
                        addFavoriteDialog(box)
                    },
                    onDeleteFavorite = { f ->
                        val cur = NavigationSettings.load(this@NavActivity)
                        NavigationSettings.save(
                            this@NavActivity,
                            cur.copy(favorites = cur.favorites - f),
                        )
                        NLog.i(TAG, "删除常去地点：${f.label}")
                        reload()
                    },
                    onFavoriteClick = { f ->
                        closeSettings()
                        startNaviWithRetry(f.label, 0)
                    },
                    onDeleteHistory = { h ->
                        val cur = NavigationSettings.load(this@NavActivity)
                        NavigationSettings.save(this@NavActivity, cur.copy(history = cur.history - h))
                        NLog.i(TAG, "删除历史：${h.name}")
                        reload()
                    },
                    onHistoryClick = { h ->
                        closeSettings()
                        startNaviWithRetry(h.alias.ifBlank { h.name }, 0)
                    },
                )
            }
        }

        // ★ 不用 Dialog 承载 ComposeView！
        //   Dialog 的 window 不在 Activity 的 View 树上，
        //   ComposeView 找不到 ViewTreeLifecycleOwner，直接崩：
        //   `IllegalStateException: ViewTreeLifecycleOwner not found from ComposeView`（实测）。
        //   改成直接挂到 Activity 的根容器——LifecycleOwner 自然能继承，
        //   而且 Compose 的 AlertDialog 会自己画遮罩层。
        closeSettings()
        contentRoot?.addView(
            cv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        settingsComposeView = cv
        // 关闭回调要能移除这个 View
        dialog = null
        NLog.i(TAG, "弹出 Compose 设置面板")
    }

    /** 关闭设置面板（移除 ComposeView）。 */
    private fun closeSettings() {
        settingsComposeView?.let { cv ->
            runCatching { contentRoot?.removeView(cv) }
        }
        settingsComposeView = null
    }

    // ------------------------------------------------------------ 设置面板（旧 View 版，降级保留）

    /**
     * 导航设置面板。
     *
     * 只放**和驾驶体验直接相关**的项，不放一堆开关凑数：
     * - 播报详细度（详细/标准/简洁）—— 开车时最常调的就是这个
     * - 到达后自动退出 —— 到地方了还挂着导航界面很烦
     * - 屏幕常亮 —— 导航时不能灭屏
     * - 躲避拥堵 / 自动缩放
     */
    private fun showSettingsDialogLegacy() {
        val s = NavigationSettings.load(this)
        val pad = dp(20)

        // ★ 必须用**浅色主题**的 Dialog。
        //   导航界面是全屏黑（Theme.Black.NoTitleBar.Fullscreen），
        //   默认 AlertDialog 会继承黑底 → 深色文字完全看不清（老板反馈「一片黑」）。
        //   所以显式指定 Light 主题，并给内容区白底圆角卡片。
        // 统一成主界面的深色主题（老板要求「统一配色」）：
        // 深底 NavColors.SURFACE + 浅色文字 NavColors.ON_SURFACE
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(NavColors.SURFACE)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NavColors.SURFACE)
            setPadding(pad, dp(4), pad, dp(4))
        }
        scroll.addView(box)

        // ================= 播报 =================
        box.addView(sectionTitle("播报"))
        // 老板要求：只显示三档名字，不要括号里的解释
        val levels = arrayOf("详细", "标准", "简洁")
        val levelText = TextView(this).apply {
            text = "▸ " + levels[s.broadcastLevel.coerceIn(0, 2)]
            setTextColor(NavColors.PRIMARY)
            textSize = 17f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(NavColors.SURFACE_VARIANT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), NavColors.OUTLINE)
            }
            setOnClickListener {
                AlertDialog.Builder(this@NavActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("播报详细程度")
                    .setSingleChoiceItems(levels, NavigationSettings.load(this@NavActivity).broadcastLevel) { d, which ->
                        val cur = NavigationSettings.load(this@NavActivity)
                        NavigationSettings.save(this@NavActivity, cur.copy(broadcastLevel = which))
                        text = levels[which]
                        d.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        box.addView(levelText)

        // ================= 导航行为 =================
        box.addView(sectionTitle("导航行为"))
        box.addView(switchRow("到达后自动退出", s.arriveAutoExit) { checked ->
            NavigationSettings.save(this, NavigationSettings.load(this).copy(arriveAutoExit = checked))
        })
        box.addView(switchRow("导航时屏幕常亮", s.keepScreenOn) { checked ->
            NavigationSettings.save(this, NavigationSettings.load(this).copy(keepScreenOn = checked))
            if (checked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        })
        box.addView(switchRow("优先躲避拥堵", s.avoidJam) { checked ->
            NavigationSettings.save(this, NavigationSettings.load(this).copy(avoidJam = checked))
        })
        box.addView(switchRow("自动缩放地图", s.autoZoom) { checked ->
            NavigationSettings.save(this, NavigationSettings.load(this).copy(autoZoom = checked))
            runCatching { naviView?.viewOptions?.isAutoChangeZoom = checked }
        })

        // ================= 常去地点 =================
        box.addView(sectionTitle("常去地点（说「导航回家」直接走）"))
        val favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(favBox)
        renderFavorites(favBox)
        // 添加：两种方式（输入地址搜索 / 用当前位置）——老板要求
        box.addView(flatButton("＋ 添加常去地点") {
            addFavoriteDialog(favBox)
        })

        // ================= 导航历史 =================
        box.addView(sectionTitle("导航历史（可删除、可改别名）"))
        val histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(histBox)
        renderHistory(histBox)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("导航设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .show()
    }

    /** 渲染常去地点列表。 */
    private fun renderFavorites(container: LinearLayout) {
        container.removeAllViews()
        val favs = NavigationSettings.load(this).favorites
        if (favs.isEmpty()) {
            container.addView(hintText("还没有常去地点。导航到家时点下面的按钮添加。"))
            return
        }
        for (f in favs) {
            // 地址太长会把「删除」挤掉：只取前 12 个字
            val addr = f.address.take(12).let { if (f.address.length > 12) "$it…" else it }
            val row = rowWithDelete("${f.label}  ·  $addr") {
                val cur = NavigationSettings.load(this)
                NavigationSettings.save(this, cur.copy(favorites = cur.favorites - f))
                NLog.i(TAG, "删除常去地点：${f.label}")
                renderFavorites(container)
            }
            // 点整行 → 改名字 / 直接导航（老板要求「常去地点设置可编辑」）
            row.setOnClickListener {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(f.label)
                    .setItems(arrayOf("改名字", "导航去这里")) { _, which ->
                        if (which == 0) renameFavorite(f, container)
                        else NavigationService.peek()?.startNaviFromOuter(f.label, 0)
                    }
                    .show()
            }
            container.addView(row)
        }
    }

    /** 改常去地点的名字。 */
    private fun renameFavorite(f: NavigationSettings.Favorite, container: LinearLayout) {
        val input = android.widget.EditText(this).apply {
            setText(f.label)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("改名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotBlank()) {
                    val cur = NavigationSettings.load(this)
                    val newList = cur.favorites.map {
                        if (it.lat == f.lat && it.lon == f.lon) it.copy(label = label) else it
                    }
                    NavigationSettings.save(this, cur.copy(favorites = newList))
                    NLog.i(TAG, "常去地点改名：${f.label} → $label")
                    renderFavorites(container)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 渲染导航历史（点击直接再导航一次）。 */
    private fun renderHistory(container: LinearLayout) {
        container.removeAllViews()
        val hist = NavigationSettings.load(this).history
        if (hist.isEmpty()) {
            container.addView(hintText("还没有导航记录。"))
            return
        }
        for (h in hist.take(10)) {
            val title = if (h.alias.isNotBlank()) "${h.alias}（${h.name}）" else h.name
            val row = rowWithDelete(title) {
                val cur = NavigationSettings.load(this)
                NavigationSettings.save(this, cur.copy(history = cur.history - h))
                renderHistory(container)
            }
            // 点文字 → 改别名 / 直接再导航
            row.setOnClickListener {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(h.name)
                    .setItems(arrayOf("改个名字", "再去这里")) { _, which ->
                        if (which == 0) renameHistory(h)
                        else naviAgain(h)
                    }
                    .show()
            }
            container.addView(row)
        }
    }

    /** 给历史记录改别名。 */
    private fun renameHistory(h: NavigationSettings.HistoryEntry) {
        val input = android.widget.EditText(this).apply {
            setText(h.alias)
            hint = "比如「公司」「丈母娘家」"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("给「${h.name}」改个名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val alias = input.text.toString().trim()
                val cur = NavigationSettings.load(this)
                val newList = cur.history.map { if (it.time == h.time) it.copy(alias = alias) else it }
                NavigationSettings.save(this, cur.copy(history = newList))
                NLog.i(TAG, "历史别名改为：$alias")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 再导航一次（历史里有坐标，直接算路）。 */
    private fun naviAgain(h: NavigationSettings.HistoryEntry) {
        NLog.i(TAG, "历史再导航：${h.name}")
        val svc = NavigationService.peek()
        if (svc == null) {
            NLog.w(TAG, "导航服务不在")
            return
        }
        // 复用 startNavi（会重新走 POI 搜索，保证拿到最新路况）
        svc.startNaviFromOuter(h.alias.ifBlank { h.name }, 0)
    }

    /**
     * 添加常去地点。两种方式（老板要求）：
     * 1. **直接输入地址**——搜出来选一条（支持跨城，比如「丈母娘家」在外地）
     * 2. **用当前位置**——最快，导航到哪就在哪加
     */
    private fun addFavoriteDialog(container: LinearLayout) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("添加常去地点")
            .setItems(arrayOf("输入地址搜索", "用当前位置")) { _, which ->
                if (which == 0) addFavoriteBySearch(container) else addFavoriteByCurrent(container)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 方式 1：输入地址 → 搜 → 选一条 → 起名。 */
    private fun addFavoriteBySearch(container: LinearLayout) {
        val input = android.widget.EditText(this).apply {
            hint = "比如「张掖西站」「人民公园」"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("搜索地点")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.length < 2) {
                    toast("至少输入两个字")
                    return@setPositiveButton
                }
                toast("搜索中…")
                Thread {
                    val loc = NavigationService.peekLocationProvider()
                    val list = runCatching {
                        WebPoiSearch.searchBlocking(q, loc?.city, loc?.lat ?: 0.0, loc?.lon ?: 0.0, 10)
                    }.getOrDefault(emptyList())
                        .filter { it.distance <= WebPoiSearch.MAX_SANE_DISTANCE_M || it.distance == 0 }
                        .take(8)
                    runOnUiThread {
                        if (list.isEmpty()) {
                            toast("没找到「$q」")
                        } else {
                            pickFavoriteTarget(list, container)
                        }
                    }
                }.apply { name = "fav-search"; isDaemon = true }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 搜到多条 → 让用户选一条，再起名。 */
    private fun pickFavoriteTarget(list: List<SdkPoiSearch.Candidate>, container: LinearLayout) {
        val names = list.map { c ->
            if (c.distance > 0) "${c.name} · ${c.distance}米" else c.name
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选一个地点")
            .setItems(names) { _, which ->
                val c = list[which]
                askFavoriteLabel(c.name, c.address, c.lat, c.lon, c.poiId, container)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 方式 2：用当前位置（**反查成真实地址**，不显示经纬度）。
     *
     * 老板要求「当前位置不要显示经纬度，要解析为最近的地址」——
     * 存成 `38.94478, 100.4` 用户根本不知道是哪。
     * 反查要一次网络请求，所以先弹个「正在解析地址…」再回填。
     */
    private fun addFavoriteByCurrent(container: LinearLayout) {
        val loc = NavigationService.peekLocationProvider()
        if (loc == null || loc.lat == 0.0) {
            toast("还没拿到定位，稍后再试")
            return
        }
        val lat = loc.lat
        val lon = loc.lon
        toast("正在解析地址…")
        Thread {
            // 反查人类可读地址（失败退回附近 POI 名，绝不显示经纬度）
            val addr = GeoCoder.describe(lat, lon, fallback = "当前位置附近")
            NLog.i(TAG, "当前位置解析为：$addr")
            runOnUiThread {
                askFavoriteLabel(
                    address = addr,
                    address2 = addr,
                    lat = lat,
                    lon = lon,
                    poiId = null,
                    container = container,
                )
            }
        }.apply { name = "fav-regeo"; isDaemon = true }.start()
    }

    /** 问名字并存下来。
     * @param address 显示用的地址串
     * @param address2 备用（当前位置时传"当前位置"）
     */
    private fun askFavoriteLabel(
        address: String,
        address2: String,
        lat: Double,
        lon: Double,
        poiId: String?,
        container: LinearLayout,
    ) {
        val input = android.widget.EditText(this).apply {
            hint = "叫它什么？比如「家」「公司」"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("起个名字")
            .setMessage(address.take(40))
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val label = input.text.toString().trim().ifBlank { "常去地点" }
                val cur = NavigationSettings.load(this)
                val fav = NavigationSettings.Favorite(
                    label = label,
                    address = address,
                    lat = lat,
                    lon = lon,
                    poiId = poiId,
                )
                NavigationSettings.save(this, cur.copy(favorites = cur.favorites + fav))
                NLog.i(TAG, "新增常去地点：$label → $address ($lat,$lon)")
                renderFavorites(container)
                toast("已添加「$label」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 平板风格按钮（浅底、描边），用在设置面板里。 */
    private fun flatButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(NavColors.PRIMARY)
            textSize = 16f
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(NavColors.SURFACE_VARIANT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), NavColors.OUTLINE)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44),
            ).apply { topMargin = dp(6); bottomMargin = dp(4) }
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    /**
     * 分区标题。
     *
     * 老板反馈「二级菜单字体太大，导航行为后面的选项字体太小看不见」——
     * 之前标题 13sp 加粗 + 选项 15sp，但在系统大字体下标题被放大得很显眼、
     * 选项反而因为 Switch 占位被压窄显得小。
     * 现在：标题 **12sp**（收小）、选项 **16sp**（放大），层级拉开。
     */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(NavColors.PRIMARY)
        textSize = 12f
        setPadding(0, dp(16), 0, dp(6))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.04f
    }

    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(NavColors.ON_SURFACE_VARIANT)
        textSize = 14f
        setPadding(0, dp(4), 0, dp(8))
    }

    /** 一行文字 + 删除按钮。 */
    private fun rowWithDelete(text: String, onDelete: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(NavColors.ON_SURFACE)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            this.text = "删除"
            setTextColor(NavColors.ERROR)
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { onDelete() }
        })
        return row
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#666666"))
        textSize = 13f
        setPadding(0, dp(10), 0, 0)
    }

    /**
     * 设置行（**不用系统 Switch**）。
     *
     * ## 为什么不用 Switch
     * 深色主题下 `android.widget.Switch` 的轨道默认色和面板背景都是深灰，
     * 实测**完全看不见**（截图里右侧只有一个淡淡的小点）。
     * 试过 `thumbTintList` / `trackTintList` 显式着色，在这个 ROM 上还是不生效
     * （华为对 Switch 有自己的主题覆盖）。
     *
     * 所以改成**文字状态按钮**：直接显示「已开启 / 已关闭」，
     * 开是青绿、关是灰，一眼能看清，点一下切换。
     */
    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
        }
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(NavColors.ON_SURFACE)
            textSize = 17f
            setPadding(0, dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val stateBtn = TextView(this).apply {
            var on = checked
            fun render() {
                // this.text：外层函数参数也叫 text（val），不写 this 会指到参数上
                this.text = if (on) "已开启" else "已关闭"
                setTextColor(if (on) NavColors.SURFACE else NavColors.ON_SURFACE_VARIANT)
                background = GradientDrawable().apply {
                    setColor(if (on) NavColors.PRIMARY else NavColors.SURFACE_VARIANT)
                    cornerRadius = dp(14).toFloat()
                    if (!on) setStroke(dp(1), NavColors.OUTLINE)
                }
            }
            render()
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            setOnClickListener {
                on = !on
                render()
                onChange(on)
                NLog.i(TAG, "设置「$text」→ $on")
            }
        }
        row.addView(stateBtn)
        // 点整行也能切换
        row.setOnClickListener { stateBtn.performClick() }
        return row
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 结束导航但**保留地图界面**（老板要求）。
     *
     * 停引擎、清路线、播一句「已结束导航」，界面留在原地——
     * 用户可以接着搜下一个地点，不用重新进导航。
     */
    private fun endNaviKeepMap() {
        // ★ 关键：高德的「退出」按钮点下去，它内部会**自己 finish 掉 NavActivity**
        //   （日志实测：onNaviCancel → 1ms 后 onAMapNaviViewExit → onDestroy）。
        //   我们拦不住它的 finish（接口返回 void），所以只能**重新拉起**一个空的
        //   导航界面——视觉上地图一直在，只是路线没了。
        keepMapAfterExit = true
        runCatching { NavigationService.peek()?.requestStop() }
        runCatching { searchPanel?.reset() }
        toast("已结束导航")
        NLog.i(TAG, "导航已结束，稍后重建地图界面")
    }

    /** 标记：用户是按「结束导航」（而不是真的要退出），需要重建界面。 */
    @Volatile
    private var keepMapAfterExit = false

    /** 标记：本次结束要彻底退出界面并释放 SDK，onDestroy 里执行。 */
    @Volatile
    private var releaseSdkOnExit = false

    /** finish() 可能被高德回调重复触发，只处理一次。 */
    @Volatile
    private var exiting = false

    private fun stopNaviAndFinish() {
        // keepMapAfterExit = 已经决定走「结束导航保留地图」，别被高德的退出回调截胡
        if (exiting || keepMapAfterExit) return
        exiting = true
        runCatching { NavigationService.peek()?.requestStop() }
        releaseSdkOnExit = true
        finish()
    }

    override fun onResume() {
        super.onResume()
        runCatching { naviView?.onResume() }
        // 从后台回来可能已经跨过日落/日出
        applyDayNightMode(force = true)
    }

    override fun onPause() {
        super.onPause()
        runCatching { naviView?.onPause() }
    }

    override fun onDestroy() {
        dayNightHandler.removeCallbacks(dayNightTick)
        navStateHandler.removeCallbacks(navStateTick)
        runCatching { locationMarker?.remove() }
        locationMarker = null
        runCatching { naviView?.onDestroy() }
        naviView = null
        if (current === this) current = null
        super.onDestroy()
        NLog.i(TAG, "NavActivity onDestroy（keepMap=$keepMapAfterExit releaseSdk=$releaseSdkOnExit）")
        // ★ 必须在 AMapNaviView.onDestroy() 之后释放，顺序反了会崩
        if (releaseSdkOnExit) {
            releaseSdkOnExit = false
            runCatching { NavigationService.peek()?.releaseEngine() }
                .onFailure { NLog.w(TAG, "释放导航引擎失败 ${it.message}") }
        }
        // 用户是「结束导航」而不是退出界面 → 立刻重建一个空的地图界面。
        // 用 applicationContext 启动（this 已经不合法了）。
        if (keepMapAfterExit) {
            keepMapAfterExit = false
            runCatching {
                applicationContext.startActivity(
                    Intent(applicationContext, NavActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                NLog.i(TAG, "已重建地图界面（导航已结束）")
            }.onFailure { NLog.w(TAG, "重建地图界面失败 \${it.message}") }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching { naviView?.onSaveInstanceState(outState) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // naviView 的 onRestoreInstanceState 是 View 的 protected 方法，不能直接调
    }
}
