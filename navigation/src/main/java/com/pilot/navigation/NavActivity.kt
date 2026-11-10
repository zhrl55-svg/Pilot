package com.pilot.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNaviView

/**
 * 导航界面（承载高德的 [AMapNaviView]）。
 *
 * ## 为什么必须有这个 Activity
 * 高德导航引擎不是纯后台服务——**必须有 AMapNaviView 承载渲染**，
 * 否则 `AMapNavi.getInstance()` 之后引擎不回调（实测：卡在 getInstance，
 * onCalculateRouteSuccess 永远不来，logcat 也没报错）。
 * nav-agent 的做法也是 NavActivity 承载 NaviView。
 *
 * ## 交互形态
 * 语音喊「导航去XX」→ 引擎算路 → **本界面自动弹出**显示转向图和路线，
 * 播报走主进程的统一 TTS 队列（导航播报抢占式）。
 * 用户说话不需要看屏幕，但界面在后台时看不到路线——所以自动弹出来是对的。
 *
 * ## 生命周期要点
 * - `naviView.onCreate()` 必须在 `setContentView` 之后
 * - `naviView.onDestroy()` 要和 Activity 生命周期对齐，否则 native 泄漏
 */
class NavActivity : Activity() {

    companion object {
        private const val TAG = "PilotNav"

        /** 拉起导航界面（已在前台就不重复拉）。 */
        fun show(context: Context, destination: String) {
            val intent = Intent(context, NavActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_DEST, destination)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { NLog.w(TAG, "拉起导航界面失败 ${it.message}") }
        }

        const val EXTRA_DEST = "dest"
    }

    private var naviView: AMapNaviView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NLog.attach(filesDir)
        NLog.i(TAG, "NavActivity onCreate")

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
            root.addView(v)
            naviView = v
            NLog.i(TAG, "AMapNaviView 创建完成")
        }.onFailure {
            NLog.e(TAG, "AMapNaviView 创建失败", it)
        }

        // ★★ 地图默认停在**北京市**——AMapNaviView 不会自动把镜头挪到当前位置。
        //   nav-agent 的注释写得很明确：「只打开地图不导航时，
        //   AMapNaviView 不会自动把镜头挪到当前位置，实测停在北京市 ——
        //   必须自己 moveCamera 一次」。
        //   所以这里挂上定位回调，第一次拿到坐标就挪镜头。
        val loc = NavigationService.peekLocationProvider()
        if (loc != null) {
            loc.onLocation = { lat, lon ->
                if (!cameraMoved && lat != 0.0 && lon != 0.0) {
                    cameraMoved = true
                    runCatching {
                        naviView?.map?.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                com.amap.api.maps.model.LatLng(lat, lon), 16f,
                            ),
                        )
                        NLog.i(TAG, "地图挪到当前位置 $lat,$lon")
                    }.onFailure { NLog.w(TAG, "moveCamera 失败 \${it.message}") }
                }
            }
        } else {
            NLog.w(TAG, "拿不到 LocationProvider，地图可能停在北京市")
        }
    }

    /** 是否已经把镜头挪到当前位置（只挪一次，之后交给导航引擎跟随）。 */
    private var cameraMoved = false

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
        super.onDestroy()
        NLog.i(TAG, "NavActivity onDestroy")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching { naviView?.onSaveInstanceState(outState) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // naviView 的 onRestoreInstanceState 是 View 的 protected 方法，
        // 这里不能直接调（编译期就报），跳过——引擎状态可重新初始化。
    }
}
