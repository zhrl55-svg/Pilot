package com.pilot.agent.tool

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

/** Read-only device status probes. No screen access is required. */
class DeviceTools(private val context: Context) {

    fun battery(): ToolResult {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        val charging = manager.isCharging
        if (percent == null) return ToolResult.unavailable("系统没有提供电量百分比。")
        return ToolResult.success(
            "电量 $percent%${if (charging) "，正在充电" else "，未在充电"}。",
        )
    }

    fun network(): ToolResult {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
            ?: return ToolResult.success("当前没有可用网络。")
        val caps = manager.getNetworkCapabilities(network)
            ?: return ToolResult.success("当前网络状态未知。")
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙网络"
            else -> "其他网络"
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val state = when {
            validated -> "可正常访问互联网"
            internet -> "已连接，但外网连通性尚未验证"
            else -> "已连接，但没有互联网能力"
        }
        return ToolResult.success("当前使用$kind，$state。")
    }

    fun storage(): ToolResult {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val available = stat.availableBlocksLong * stat.blockSizeLong
        val used = (total - available).coerceAtLeast(0L)
        val ratio = if (total > 0) used.toDouble() / total * 100.0 else 0.0
        return ToolResult.success(
            "内置存储共 ${gb(total)}，已用 ${gb(used)}，可用 ${gb(available)}，" +
                String.format(Locale.US, "占用 %.1f%%。", ratio),
            "存储已用 ${gb(used)}，可用 ${gb(available)}。",
        )
    }

    fun location(): ToolResult {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = runCatching { manager.isLocationEnabled }.getOrDefault(false)
        return ToolResult.success(
            if (enabled) "系统定位服务已开启。" else "系统定位服务已关闭。",
        )
    }

    fun bluetooth(): ToolResult {
        val enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 0)
        }.getOrDefault(0) == 1
        return ToolResult.success(if (enabled) "蓝牙已开启。" else "蓝牙已关闭。")
    }

    fun usageStats(): ToolResult {
        if (!usageAccessGranted()) {
            return ToolResult.unavailable(
                "还没有“使用情况访问”权限。请在 Pilot 设置里点“开启使用统计”，授权后再问我。"
            )
        }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .sortedByDescending { it.totalTimeInForeground }
            .take(8)
        if (stats.isEmpty()) {
            return ToolResult.success("今天还没有可统计的前台使用记录。")
        }
        val labels = runCatching { context.packageManager }.getOrNull()
        val details = stats.joinToString("\n") { stat ->
            val label = runCatching {
                labels?.getApplicationInfo(stat.packageName, 0)?.let {
                    labels.getApplicationLabel(it).toString()
                }
            }.getOrNull() ?: stat.packageName.substringAfterLast('.')
            "• $label：${duration(stat.totalTimeInForeground)}"
        }
        val total = stats.sumOf { it.totalTimeInForeground }
        return ToolResult.success(
            "今天前台使用时间（统计到的应用）：\n$details",
            "今天统计到的应用前台时长共 ${duration(total)}。",
        )
    }

    fun usageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = runCatching {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    private fun duration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分钟"
        }
    }
}
