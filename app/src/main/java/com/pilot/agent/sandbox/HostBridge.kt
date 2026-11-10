package com.pilot.agent.sandbox

import android.os.Bundle
import com.pilot.agent.Dbg
import com.pilot.agent.runtime.AgentRuntime

/**
 * 主进程反向通道实现（挂在 ISandboxService.attachHost 上传给 :sandbox）。
 *
 * binder 回调线程 = :sandbox 进程发起、主进程 binder 池执行。
 * openApp 是挂起函数但内部回查最长 ~1.2s，用 runBlocking 等它没有问题
 * （binder 池线程不是主线程，不会卡 UI）。
 */
object HostBridge {

    val impl = object : IHostBridge.Stub() {
        override fun call(req: String?): Bundle {
            val r = req.orEmpty()
            Dbg.i("PilotSandbox", "host call: $r")
            return when {
                r.startsWith("open:") -> {
                    val app = r.removePrefix("open:")
                    val actions = AgentRuntime.actions
                    val ok = if (actions == null) false
                    else runCatching {
                        kotlinx.coroutines.runBlocking { actions.openApp(app) }
                    }.getOrDefault(false)
                    Bundle().apply {
                        putBoolean("ok", ok)
                        if (!ok) putString("err", if (actions == null) "无障碍未连接" else "打不开 $app")
                    }
                }
                else -> Bundle().apply { putBoolean("ok", false); putString("err", "unknown req: $r") }
            }
        }
    }
}
