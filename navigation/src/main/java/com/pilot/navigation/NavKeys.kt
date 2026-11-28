package com.pilot.navigation

import android.content.Context

/**
 * 高德 Key 管理（**全部来自用户自己的配置，公开版本不内置任何 Key**）。
 *
 * 两个 Key 用途不同：
 * - **Android 平台 Key**（[sdkKey]）：给 AMapNavi/AMapLocation 用，绑定包名 + 签名，
 *   通过运行时 `AMapNavi.setApiKey` / `MapsInitializer.setApiKey` /
 *   `AMapLocationClient.setApiKey` / `ServiceSettings.setApiKey` 注入。
 * - **Web 服务 Key**（[webKey]）：给 REST API（天气、地点搜索、路线规划、逆地理编码）用，不绑包名。
 *
 * ## 为什么不再从 BuildConfig / Manifest 注入
 * 旧做法把作者个人的 Key 打进 APK，公开分发时别人会消耗作者的配额。
 * 现在 Key 一律由使用者在「设置 → 导航与地图」里填自己的，存进 [NavigationSettings]
 * （跨进程文件存储），未配置时相关能力明确提示不可用。
 *
 * 注意：这里只读取/注入 Key，**绝不把 Key 内容写日志**。
 */
object NavKeys {

    private const val TAG = "PilotNavKeys"

    /** 用户自己配置的高德 Android 平台 Key（导航/定位 SDK 用）；空串 = 未配置。 */
    fun sdkKey(context: Context): String =
        NavigationSettings.load(context).amapSdkKey.trim()

    /** 用户自己配置的高德 Web 服务 Key（天气/地点搜索/路线规划用）；空串 = 未配置。 */
    fun webKey(context: Context): String =
        NavigationSettings.load(context).amapWebKey.trim()

    fun hasSdkKey(context: Context): Boolean = sdkKey(context).isNotBlank()

    fun hasWebKey(context: Context): Boolean = webKey(context).isNotBlank()

    /** 未配置高德 Key 时给用户看的统一提示（不暴露技术细节）。 */
    const val NOT_CONFIGURED_HINT: String =
        "高德服务还没配置。请到「设置 → 导航与地图」填入你自己的高德 Key" +
            "（可免费在 lbs.amap.com 申请）；未配置时导航、天气和地点搜索不可用。"

    /**
     * 在创建任何高德 SDK 组件**之前**把用户的 Android Key 注入各 SDK。
     *
     * 各 SDK 的 Key 入口是分开的，必须逐个设置；Key 为空时什么都不做，
     * 让调用方走「未配置」分支。全部用 runCatching 包住：某个旧版本 SDK
     * 没有对应方法时不影响其它 SDK 初始化。
     */
    fun applySdkKey(context: Context) {
        val key = sdkKey(context)
        if (key.isBlank()) return
        runCatching { com.amap.api.navi.AMapNavi.setApiKey(context, key) }
            .onFailure { NLog.w(TAG, "AMapNavi.setApiKey 失败 ${it.message}") }
        runCatching { com.amap.api.maps.MapsInitializer.setApiKey(key) }
            .onFailure { NLog.w(TAG, "MapsInitializer.setApiKey 失败 ${it.message}") }
        runCatching { com.amap.api.location.AMapLocationClient.setApiKey(key) }
            .onFailure { NLog.w(TAG, "AMapLocationClient.setApiKey 失败 ${it.message}") }
        runCatching { com.amap.api.services.core.ServiceSettings.getInstance().setApiKey(key) }
            .onFailure { NLog.w(TAG, "ServiceSettings.setApiKey 失败 ${it.message}") }
    }

    /**
     * 当前 Key 配置状态（**绝不输出 Key 内容**）。
     *
     * 返回形如 `sdkKey=set, webKey=missing`，供服务层决定走 SDK / Web / 提示未配置。
     */
    fun check(context: Context): String {
        val sdk = hasSdkKey(context)
        val web = hasWebKey(context)
        val status = "sdkKey=${if (sdk) "set" else "missing"}, webKey=${if (web) "set" else "missing"}"
        if (!sdk || !web) {
            NLog.w(TAG, "高德配置未就绪：$status（不输出 Key 内容）")
        } else {
            NLog.i(TAG, "高德配置已就绪：$status；服务端有效性以算路错误码为准")
        }
        return status
    }
}
