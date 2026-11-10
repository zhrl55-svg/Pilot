package com.pilot.agent.sandbox.shim.network

import java.io.File

/**
 * minis 的代理变量注入桩。
 *
 * minis 在设置里给用户配 HTTP 代理并注入 guest 环境变量；
 * Pilot 的网络请求都走 App 侧 OkHttp，guest 里暂无代理需求 → 返回空。
 * 以后要做「容器内 curl 走代理」时，把 minis 原实现
 * (network/NetworkShellEnvironment.kt) 搬过来替换这里即可。
 */
object NetworkShellEnvironment {
    fun proxyVariables(): Map<String, String> = emptyMap()
}

/**
 * minis 透明网络劫持桩（proot 参数注入）。返回空参数 = 不劫持，
 * guest 直接用 Android 内核网络栈出网。
 */
object ShellTransparentNet {
    fun prootArgs(proot: File): List<String> = emptyList()
}
