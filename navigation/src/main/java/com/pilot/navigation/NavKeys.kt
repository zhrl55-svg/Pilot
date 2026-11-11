package com.pilot.navigation

import android.content.Context
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

    /** 检查 Web Key 和 Manifest 注入的 Android SDK Key；绝不记录密钥本身。 */
    fun check(context: Context): String {
        val web = webKey()
        val sdk = runCatching {
            context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            ).metaData?.getString("com.amap.api.v2.apikey").orEmpty()
        }.getOrDefault("")
        val status = "sdkKey=${if (sdk.isBlank()) "missing" else "injected"}, webKey=${if (web.isBlank()) "missing" else "injected"}"
        if (sdk.isBlank() || web.isBlank()) {
            NLog.w(TAG, "高德配置未就绪：$status（不输出 Key 内容）")
            return status
        }
        NLog.i(TAG, "高德配置已注入：$status；服务端有效性以算路错误码为准")
        return status
    }
}
