package com.pilot.agent.agent

import okhttp3.OkHttpClient

/**
 * P1：云端对话共用的 OkHttpClient。
 *
 * 之前 AgentViewModel / RunReceiver 每次请求都 `OkHttpClient()`，
 * 每个实例自带 Dispatcher 线程池 + ConnectionPool 且从不 close，
 * 高频语音对话会导致线程与 socket 累积；且无 callTimeout，弱网永久挂起。
 *
 * 2026-11-27：统一委托给 [com.pilot.agent.assistant.llm.LlmGateway] 的根 client，
 * 进程内只保留一套连接池 / Dispatcher 线程池。请求改用
 * [com.pilot.agent.runtime.await]（enqueue + 协程取消），点「停止」会立刻断开。
 */
object CloudHttp {
    val client: OkHttpClient by lazy {
        com.pilot.agent.assistant.llm.LlmGateway.derive(
            connectSeconds = 10,
            readSeconds = 20,
            callSeconds = 25,
        )
    }
}
