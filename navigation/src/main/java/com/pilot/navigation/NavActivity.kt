package com.pilot.navigation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AmapPageType
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
class NavActivity : Activity(), AMapNaviViewListener {

    companion object {
        private const val TAG = "PilotNav"

        /**
         * 初始缩放级别。
         *
         * 高德级别含义（数字越小范围越大）：
         *   17f = 车道级（导航跟随时）
         *   **14f = 街区级（能看到周边几个街区，适合刚打开地图认路）**
         *   11f = 城区级
         *    8f = 城市级
         */
        private const val INITIAL_ZOOM = 14f

        const val EXTRA_DEST = "dest"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NLog.attach(filesDir)
        current = this
        NLog.i(TAG, "NavActivity onCreate")

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

        runCatching {
            val v = AMapNaviView(this)
            v.onCreate(savedInstanceState)
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
            }.onFailure { NLog.w(TAG, "viewOptions 设置失败 ${it.message}") }

            NLog.i(TAG, "AMapNaviView 创建完成")
        }.onFailure {
            NLog.e(TAG, "AMapNaviView 创建失败", it)
        }

        // Pilot 自己的按钮栏
        root.addView(buildPilotBar())

        // ★ 地图默认停在北京市——必须在拿到第一次定位时手动 moveCamera
        val loc = NavigationService.peekLocationProvider()
        if (loc != null) {
            loc.onLocation = { lat, lon ->
                if (!cameraMoved && lat != 0.0 && lon != 0.0) {
                    cameraMoved = true
                    runCatching {
                        naviView?.map?.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                com.amap.api.maps.model.LatLng(lat, lon), INITIAL_ZOOM,
                            ),
                        )
                        NLog.i(TAG, "地图挪到当前位置 $lat,$lon zoom=$INITIAL_ZOOM")
                    }.onFailure { NLog.w(TAG, "moveCamera 失败 ${it.message}") }
                }
            }
        } else {
            NLog.w(TAG, "拿不到 LocationProvider，地图可能停在北京市")
        }
    }

    // ------------------------------------------------------------ Pilot 按钮栏

    /**
     * Pilot 自定义按钮栏（浮在高德 UI 上方，避开底部导航信息区）。
     *
     * 三个按钮：
     * - **语音**：回到主界面开语音识别（开车时手不能离开方向盘，语音是主交互）
     * - **主页**：回到 Pilot 主界面（导航继续在后台跑）
     * - **退出**：退出整个 Pilot（含导航）
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
     * - 主页 / 退出：**右侧竖排**（低频操作，放边上）
     */
    private fun buildPilotBar(): View {
        val wrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // 按钮容器本身不拦截触摸，只有按钮自己响应
            isClickable = false
        }

        // ---- 左下：语音（最常用，拇指区）----
        val voice = pillButton("🎤 语音", width = dp(104)) { onVoiceButton() }
        wrap.addView(voice, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(44),
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(16)
            bottomMargin = dp(96)   // 避开高德底部三栏
        })

        // ---- 右侧竖排：主页 / 退出（低频）----
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        // 设置：**不依赖高德那个「设置」按钮**。
        //   实测高德的底部「设置」第一次点只是进入「浏览模式」
        //   （底部变成「退出/继续导航/设置」），并不会回调 onNaviSetting；
        //   再点一次才回调，用户很容易以为按钮坏了。
        side.addView(pillButton("⚙", width = dp(52)) { showSettingsDialog() }.apply {
            textSize = 18f
        })
        side.addView(pillButton("🏠", width = dp(52)) { onHomeButton() }.apply {
            textSize = 18f
        })
        side.addView(pillButton("✕", width = dp(52)) { onExitApp() }.apply {
            textSize = 18f
        })
        wrap.addView(side, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            rightMargin = dp(10)
        })

        pilotBar = side
        return wrap
    }

    private fun pillButton(text: String, width: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A1A"))
                cornerRadius = dp(22).toFloat()
            }
            val lp = LinearLayout.LayoutParams(width, dp(44)).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    /** 语音按钮：回到主界面开语音识别（导航继续在后台跑）。 */
    private fun onVoiceButton() {
        NLog.i(TAG, "点击语音按钮")
        // 拉起主进程的主界面并带「立刻开语音」标记
        runCatching {
            val i = Intent().apply {
                setClassName(packageName, "com.pilot.agent.ui.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("start_voice", true)
            }
            startActivity(i)
        }.onFailure { NLog.w(TAG, "拉起主界面失败 ${it.message}") }
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

    /** 退出 App：停导航 + 关界面 + 退整个进程（其他进程也会跟着退）。 */
    private fun onExitApp() {
        NLog.i(TAG, "点击退出 App")
        runCatching { NavigationService.peek()?.requestStop() }
        finishAffinity()
    }

    // ------------------------------------------------------------ 高德界面回调

    /**
     * 「退出导航」按钮（高德底部的那个）。
     *
     * ★ 之前点了没作用——因为没实现这个回调。
     *   高德的按钮只负责「通知宿主」，具体做什么由我们自己决定。
     */
    override fun onNaviCancel() {
        NLog.i(TAG, "高德界面：退出导航")
        stopNaviAndFinish()
    }

    override fun onAMapNaviViewExit() {
        NLog.i(TAG, "高德界面：视图退出")
        stopNaviAndFinish()
    }

    /**
     * 「设置」按钮。
     *
     * ★ 之前点了也没作用——同样是因为没实现回调。
     *   现在弹 Pilot 自己的导航设置面板（播报详细度、到达自动退出等）。
     */
    override fun onNaviSetting() {
        NLog.i(TAG, "高德界面：设置")
        showSettingsDialog()
    }

    override fun onStopSpeaking() {
        NLog.i(TAG, "高德界面：停止播报")
        runCatching { AgentBridge.stopSpeaking(this) }
    }

    override fun onNaviBackClick(): Boolean {
        // 返回键：退出导航（车机场景没有「返回上一页」的概念）
        stopNaviAndFinish()
        return true
    }

    override fun onNaviMapMode(mode: Int) = Unit
    override fun onNaviTurnClick() = Unit
    override fun onNextRoadClick() = Unit
    override fun onScanViewButtonClick() = Unit
    override fun onLockMap(isLock: Boolean) = Unit
    override fun onNaviViewLoaded() {
        NLog.i(TAG, "AMapNaviView 加载完成")
    }
    override fun onMapTypeChanged(type: Int) = Unit
    override fun onNaviViewShowMode(mode: Int) = Unit
    override fun onViewTypeChanged(type: AmapPageType?) = Unit
    override fun onStrategyChanged(strategy: Int) {
        NLog.i(TAG, "路线策略变更 $strategy")
    }
    override fun onBroadcastModeChanged(mode: Int) = Unit
    override fun onDayAndNightModeChanged(mode: Int) = Unit
    override fun onScaleAutoChanged(auto: Boolean) = Unit
    override fun onListenToVoiceDuringCallChanged(listen: Boolean) = Unit
    override fun onControlMusicVolumeModeChanged(mode: Int) = Unit
    override fun onEagleChanged(enabled: Boolean) = Unit
    override fun onNaviRouteHighlightChange(routeId: Long, mode: Int) = Unit

    // ------------------------------------------------------------ 设置面板

    /**
     * 导航设置面板。
     *
     * 只放**和驾驶体验直接相关**的项，不放一堆开关凑数：
     * - 播报详细度（详细/标准/简洁）—— 开车时最常调的就是这个
     * - 到达后自动退出 —— 到地方了还挂着导航界面很烦
     * - 屏幕常亮 —— 导航时不能灭屏
     * - 躲避拥堵 / 自动缩放
     */
    private fun showSettingsDialog() {
        val s = NavigationSettings.load(this)
        val pad = dp(20)

        // 内容可能很长（常去地点 + 历史），用 ScrollView 包一层
        val scroll = android.widget.ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, dp(8))
        }
        scroll.addView(box)

        // ================= 播报 =================
        box.addView(sectionTitle("播报"))
        val levels = arrayOf("详细（全部念）", "标准（默认）", "简洁（只念转弯）")
        val levelText = TextView(this).apply {
            text = levels[s.broadcastLevel.coerceIn(0, 2)]
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener {
                AlertDialog.Builder(this@NavActivity)
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
        box.addView(pillButton("＋ 把当前位置设为常去地点", width = ViewGroup.LayoutParams.MATCH_PARENT) {
            addFavoriteFromCurrent(favBox)
        })

        // ================= 导航历史 =================
        box.addView(sectionTitle("导航历史（可删除、可改别名）"))
        val histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(histBox)
        renderHistory(histBox)

        AlertDialog.Builder(this)
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
            container.addView(rowWithDelete("${f.label}  ·  ${f.address.take(20)}") {
                val cur = NavigationSettings.load(this)
                NavigationSettings.save(this, cur.copy(favorites = cur.favorites - f))
                NLog.i(TAG, "删除常去地点：${f.label}")
                renderFavorites(container)
            })
        }
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
                AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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

    /** 把当前位置存为常去地点（让用户起个名）。 */
    private fun addFavoriteFromCurrent(container: LinearLayout) {
        val loc = NavigationService.peekLocationProvider()
        if (loc == null || loc.lat == 0.0) {
            toast("还没拿到定位，稍后再试")
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "「家」「公司」…"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("这个名字叫什么？")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val label = input.text.toString().trim().ifBlank { "常去地点" }
                val cur = NavigationSettings.load(this)
                val fav = NavigationSettings.Favorite(
                    label = label,
                    address = "当前位置（${String.format("%.5f", loc.lat)}, ${String.format("%.5f", loc.lon)}）",
                    lat = loc.lat,
                    lon = loc.lon,
                )
                NavigationSettings.save(this, cur.copy(favorites = cur.favorites + fav))
                NLog.i(TAG, "新增常去地点：$label (${loc.lat},${loc.lon})")
                renderFavorites(container)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#212121"))
        textSize = 14f
        setPadding(0, dp(16), 0, dp(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#999999"))
        textSize = 13f
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
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            this.text = "删除"
            setTextColor(Color.parseColor("#E53935"))
            textSize = 13f
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

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return row
    }

    // ------------------------------------------------------------ 生命周期

    private fun stopNaviAndFinish() {
        runCatching { NavigationService.peek()?.requestStop() }
        finish()
    }

    override fun onResume() {
        super.onResume()
        runCatching { naviView?.onResume() }
    }

    override fun onPause() {
        super.onPause()
        runCatching { naviView?.onPause() }
    }

    override fun onDestroy() {
        runCatching { naviView?.onDestroy() }
        naviView = null
        if (current === this) current = null
        super.onDestroy()
        NLog.i(TAG, "NavActivity onDestroy")
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
