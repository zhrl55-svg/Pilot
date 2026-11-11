package com.pilot.agent.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.pilot.agent.Dbg
import com.pilot.navigation.INavigationService
import com.pilot.navigation.NavCallback

/**
 * 主进程侧的导航客户端。
 *
 * 与沙箱的 [com.pilot.agent.sandbox.SandboxClient] 同款结构：
 * bind → 注册回调 → 调用。导航能力放在 `:navigation` 进程，
 * 高德 native 崩了不影响语音 agent 主进程。
 *
 * ## 播报并入统一 TTS 队列
 * 导航进程不自己发声，只通过 [NavCallback] 把播报文本回推，
 * 主进程用**抢占式**播报（导航提示优先级高于普通播报）。
 */
object NavigationClient {

    private const val TAG = "PilotNavClient"

    @Volatile private var service: INavigationService? = null
    @Volatile private var binding = false

    /** 导航播报回调（主进程接了之后按优先级播报）。 */
    @Volatile var onAnnounce: ((String, Int) -> Unit)? = null

    private val callback = object : NavCallback.Stub() {
        override fun onEvent(jobId: Long, data: Bundle?) {
            val event = data?.getString("event").orEmpty()
            val text = data?.getString("text").orEmpty()
            val priority = data?.getInt("priority") ?: 0
            Dbg.i(TAG, "nav 事件 $event priority=$priority text=$text")
            if (event == "announce" && text.isNotBlank()) {
                onAnnounce?.invoke(text, priority)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = INavigationService.Stub.asInterface(binder).also { svc ->
                runCatching { svc.registerCallback(callback) }
            }
            binding = false
            Dbg.i(TAG, "已连接 :navigation")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            Dbg.w(TAG, ":navigation 断开")
        }
    }

    /** 开始导航。返回 jobId，0 = 失败（服务不可用）。 */
    suspend fun startNavi(context: Context, destination: String, strategy: Int = 0): Long {
        val svc = ensureBound(context) ?: return 0L
        return try {
            svc.startNavi(destination, strategy)
        } catch (t: Throwable) {
            Dbg.w(TAG, "startNavi 异常 ${t.message}")
            0L
        }
    }

    /** 设备诊断入口：启动与正式流程相同的路线，但使用模拟定位。 */
    suspend fun startSimulatedNavi(context: Context, destination: String): Long {
        val svc = ensureBound(context) ?: return 0L
        return try {
            svc.startSimulatedNavi(destination)
        } catch (t: Throwable) {
            Dbg.w(TAG, "startSimulatedNavi 异常 ${t.message}")
            0L
        }
    }

    fun stopNavi(jobId: Long): Boolean {
        val svc = service ?: return false
        return runCatching { svc.stopNavi(jobId) }.getOrDefault(false)
    }

    fun stateJson(): String = runCatching { service?.navState }.getOrNull() ?: "{\"state\":\"IDLE\"}"

    private suspend fun ensureBound(context: Context): INavigationService? {
        service?.let { return it }
        if (!binding) {
            binding = true
            val intent = Intent("com.pilot.navigation.BIND")
                .setComponent(ComponentName(context.packageName, "com.pilot.navigation.NavigationService"))
            val ok = context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                binding = false
                Dbg.w(TAG, "bindService :navigation 失败")
                return null
            }
        }
        val deadline = System.currentTimeMillis() + 4_000L
        while (service == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        return service
    }
}
