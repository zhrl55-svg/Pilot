package com.pilot.agent

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pilot.agent.voice.ModelStore

class PilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 副进程（:sandbox 等）跳过重初始化：搬模型/建 App 入口索引是主进程的活，
        // 每个进程各跑一遍纯浪费（索引 2.7s + 文件 IO）。
        // getProcessName() 在 Application.onCreate 里对副进程返回 "包名:进程名"，
        // 但个别 ROM 返回 null —— 两种都按副进程处理（宁可漏初始化也不能重复搬 500MB）。
        val proc = runCatching { getProcessName() }.getOrNull()
        if (proc != packageName) {
            Dbg.i("PilotApp", "副进程初始化跳过：${proc ?: "unknown"}")
            return
        }
        // 模型导入放后台：外部私有目录里的模型搬到私有目录，554MB 大约 5~10 秒。
        // ASR/LLM 加载前会通过 ModelStore.awaitReady() 等它结束，
        // 避免读到半个 onnx 触发 native abort。
        Thread {
            ModelStore.ensure(this)
            // 模型搬完再建 App 入口索引：扫一遍全部已安装 App 的 exported Activity，
            // 之后「打开微信扫一扫」这类请求就能直接 startActivity，不用点屏幕。
            runCatching { com.pilot.agent.runtime.AgentRuntime.appIndex.build() }
        }.apply {
            name = "model-import"
            isDaemon = true
            start()
        }.also { Log.i("PilotVoice", "启动初始化线程") }
    }

    companion object {
        lateinit var instance: PilotApp
            private set

        val prefs: SharedPreferences
            get() = instance.getSharedPreferences("pilot", Context.MODE_PRIVATE)
    }
}
