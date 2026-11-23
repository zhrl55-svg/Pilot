package com.pilot.agent.agent

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * P1：云端对话共用的 OkHttpClient。
 *
 * 之前 AgentViewModel / RunReceiver 每次请求都 `OkHttpClient()`，
 * 每个实例自带 Dispatcher 线程池 + ConnectionPool 且从不 close，
 * 高频语音对话会导致线程与 socket 累积；且无 callTimeout，弱网永久挂起。
 */
object CloudHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
