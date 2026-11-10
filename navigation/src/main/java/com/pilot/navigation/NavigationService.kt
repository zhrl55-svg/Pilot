package com.pilot.navigation

import android.app.Service
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, ":navigation 进程启动 pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : INavigationService.Stub() {

        override fun startNavi(destination: String, strategy: Int): Long {
            val jobId = jobs.getAndIncrement()
            currentDestination = destination
            currentState = State.ROUTING
            emit(jobId, "state", "正在规划去${destination}的路线", priority = 0, stateName = currentState.toString())

            // TODO Phase 1：接高德引擎
            //   1. AMapServices.initialize(context, key)
            //   2. AMapNavi 实例化 + AMapNaviView（导航界面要 Activity 承载）
            //   3. 目的地解析：AMapSearch 的 POI 搜索（参考 nav-agent 的 AmapSdkGeocoder）
            //   4. 计算路线 → startNavi
            //   5. AMapNaviListener 收 onUpdateNaviInfo → 生成播报文本 → emit(priority=1)
            // 详见 nav-agent 的 AmapNavController.kt（1435 行，可复用骨架）
            Thread {
                try {
                    Thread.sleep(300)
                    currentState = State.NAVIGATING
                    emit(jobId, "state", "开始导航", priority = 0, stateName = currentState.toString())
                } catch (_: InterruptedException) {
                }
            }.apply { name = "nav-stub"; isDaemon = true }.start()
            return jobId
        }

        override fun stopNavi(jobId: Long): Boolean {
            if (currentState == State.IDLE) return false
            currentState = State.IDLE
            currentDestination = null
            emit(jobId, "state", "已退出导航", priority = 0, stateName = currentState.toString())
            return true
        }

        override fun getNavState(): String = buildString {
            append("{\"state\":\"").append(currentState.toString()).append("\"")
            append(",\"destination\":\"").append(currentDestination ?: "").append("\"")
            append("}")
        }

        override fun registerCallback(cb: NavCallback?) {
            cb?.let { callbacks.register(it) }
        }
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
