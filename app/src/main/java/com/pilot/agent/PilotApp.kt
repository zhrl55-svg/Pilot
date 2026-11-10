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
