package com.pilot.agent.action

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.pilot.agent.Dbg

/**
 * App 内入口索引：把已安装 App 自己声明过的 Activity 建成一张表，
 * 用户说"打开微信扫一扫"时直接 `startActivity(ComponentName)`，不用点屏幕。
 *
 * ## 为什么能这么干
 * 系统级 agent 有 `QUERY_ALL_PACKAGES`，可以枚举所有 App 的 exported Activity
 * 和它们的 label。App 为了让功能能被桌面快捷方式、语音助手、系统搜索唤起，
 * 必须把这些入口声明成 exported——这就成了我们直接启动的把手。
 *
 * ## 局限要说清楚
 * - 很多 Activity 没有声明 label，只能拿类名兜底（中文口语匹配不上）
 * - 有 label 的多半是"桌面快捷方式"型入口（扫一扫、收款码），正好是高频需求
 * - 索引要 1~3 秒建完（100+ App × 平均 10 个 Activity），放后台线程
 */
class AppLaunchIndex(private val pm: PackageManager) {

    data class Entry(
        val label: String,
        val component: ComponentName,
        val appLabel: String,
    ) {
        val pkg: String get() = component.packageName
    }

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    var built = false
        private set

    @Volatile
    var lastBuiltMs: Long = -1
        private set

    /** 建索引。阻塞，放后台线程。 */
    fun build(force: Boolean = false): Int {
        if (built && !force) return entries.size
        val t0 = System.currentTimeMillis()
        val out = ArrayList<Entry>(512)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            for (app in pm.getInstalledApplications(0)) {
                // 只扫有 launcher 入口的 App，纯后台服务类 App 没有可启动的界面
                val hasLauncher = pm.queryIntentActivities(
                    Intent(launcher).setPackage(app.packageName), 0
                ).isNotEmpty()
                if (!hasLauncher) continue

                val info = runCatching {
                    pm.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
                }.getOrNull() ?: continue
                val appLabel = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)

                info.activities?.forEach { act ->
                    if (!act.exported || act.name.isNullOrBlank()) return@forEach
                    // ★ 声明了自定义 android:permission 的 Activity 一律不收录：
                    //   我们几乎不可能持有该权限，startActivity 会抛 SecurityException。
                    //   华为系统组件是重灾区——实测「手机管家 电池」
                    //   (HwPowerManagerActivity) 要求 com.huawei.systemmanager.permission.
                    //   ACCESS_INTERFACE，直达失败后整个任务连烧 5 步兜底点击。
                    //   不收录的话规划器会自然降级到 ruleTap/点击兜底，链路反而更稳。
                    if (act.permission != null) return@forEach
                    val label = runCatching { act.loadLabel(pm).toString() }.getOrNull().orEmpty()
                    // label 为空时拿类名末段（"ScannerActivity" 这种），至少能给英文/拼音匹配
                    val effective = label.ifEmpty { act.name.substringAfterLast('.').removeSuffix("Activity") }
                    if (effective.isBlank()) return@forEach
                    out += Entry(
                        label = effective,
                        component = ComponentName(app.packageName, act.name),
                        appLabel = appLabel,
                    )
                }
            }
        }.onFailure { Dbg.w(TAG, "建索引失败：${it.message}") }

        entries = out
        built = true
        lastBuiltMs = System.currentTimeMillis() - t0
        Dbg.i(TAG, "App 入口索引 ${out.size} 条，${lastBuiltMs}ms")
        return out.size
    }

    /**
     * 按 goal 找入口。
     *
     * 两段匹配：
     * 1. App 名出现在 goal 里 → 限定在这个 App 内找
     * 2. 组件 label 出现在 goal 里 → 命中
     *
     * 都要求 label 长度 ≥ 2，避免"扫"这种单字乱命中。
     */
    fun resolve(goal: String): Entry? {
        if (!built || entries.isEmpty()) return null
        val g = goal.lowercase()

        val appScoped = entries.filter { e ->
            e.appLabel.length >= 2 && g.contains(e.appLabel.lowercase())
        }
        val pool = appScoped.ifEmpty { entries }

        // 长的 label 优先（"微信扫一扫" 比 "扫一扫" 更精确）
        return pool
            .filter { it.label.length >= 2 && g.contains(it.label.lowercase()) }
            .maxByOrNull { it.label.length }
    }

    fun size(): Int = entries.size

    /**
     * 按中文关键词在**设置 App** 里找对应页面。
     *
     * 为什么需要它：设置里那些子页面的 label 大多就是"设置"，
     * 靠 [resolve]（按 label 匹配）根本区分不出来，于是「打开护眼模式」只能退到搜索 UI，
     * 而搜索页分区杂乱、容易点错。
     * 这里改按**类名**找：护眼 → `Settings$EyeComfortSettingsActivity`。
     * 表里没有的词返回 null，调用方继续走搜索兜底，不会更差。
     */
    fun resolveSettingsItem(keyword: String): Entry? {
        if (!built || entries.isEmpty()) {
            Dbg.w(TAG, "设置项直达：索引未就绪 built=$built size=${entries.size}")
            return null
        }
        val frag = SETTINGS_CLASS_HINTS.entries
            .filter { keyword.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.value ?: return null
        val hit = entries.firstOrNull { e ->
            val pkg = e.component.packageName
            pkg.contains("settings", ignoreCase = true) &&
                e.component.className.contains(frag, ignoreCase = true)
        }
        Dbg.i(TAG, "设置项直达：$keyword → 片段 $frag → ${hit?.component?.className ?: "未命中"}")
        return hit
    }

    private companion object {
        const val TAG = "Pilot"

        /**
         * 中文关键词 → 设置页类名里的英文片段。
         * 键用 AOSP 命名，华为/小米基本沿用；找不到就返回 null 走搜索兜底。
         */
        val SETTINGS_CLASS_HINTS = linkedMapOf(
            "护眼" to "EyeComfort",
            "夜间" to "NightDisplay",
            "深色" to "NightDisplay",
            "蓝牙" to "BluetoothSettings",
            "电池" to "Battery",
            "省电" to "BatterySaver",
            "显示" to "Display",
            "亮度" to "Display",
            "存储" to "Storage",
            "定位" to "Location",
            "位置" to "Location",
            "语言" to "Language",
            "输入法" to "InputMethod",
            "无障碍" to "Accessibility",
            "开发者" to "Development",
            "关于手机" to "About",
            "系统更新" to "SystemUpdate",
            "通知" to "Notification",
            "锁屏" to "Security",
            "指纹" to "Fingerprint",
            "账户" to "Account",
            "账号" to "Account",
            "流量" to "DataUsage",
            "热点" to "Tethering",
            "飞行" to "Airplane",
            "备份" to "Backup",
            "恢复出厂" to "Reset",
            "声音" to "Sound",
        )
    }

}
