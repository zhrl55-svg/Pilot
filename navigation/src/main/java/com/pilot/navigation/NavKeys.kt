package com.pilot.navigation

import android.util.Log

/**
 * 高德 Key 管理。
 *
 * 两个 Key 用途不同：
 * - **AMAP_KEY**（SDK Key）：给 AMapNavi/AMapLocation 用，绑定包名+签名，
 *   走 manifest meta-data 注入。
 * - **AMAP_WEB_KEY**（Web Key）：给 Web API（地点搜索）用，不绑包名。
 *
 * 两个都是密钥，**不入 git**：放在 local.properties → BuildConfig。
 */
object NavKeys {

    private const val TAG = "PilotNavKeys"

    /** Web API Key（地点搜索用）。 */
    fun webKey(): String = BuildConfig.AMAP_WEB_KEY

    /** 检查 Key 是否就绪（缺 Key 时导航不可用，要能提前发现）。 */
    fun check(): String {
        val web = webKey()
        if (web.isBlank()) {
            NLog.w(TAG, "AMAP_WEB_KEY 未配置，地点搜索不可用")
            return "缺少高德 Web Key（local.properties 里加 AMAP_WEB_KEY=xxx）"
        }
        return "ok"
    }
}
