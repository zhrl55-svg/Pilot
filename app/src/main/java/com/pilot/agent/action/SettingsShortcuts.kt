package com.pilot.agent.action

/**
 * 关键词 → 系统设置页的 Intent action。
 *
 * ## 为什么要这张表
 * 「打开护眼模式」这类指令，之前只有两条路：
 * 1. `AppLaunchIndex` 按 label 找组件 —— 设置子页面的 label 基本都是"设置"，区分不出来；
 * 2. 桌面搜索 —— 结果页分区杂乱（最佳匹配/设置/应用市场/网络结果/资讯…），容易点错。
 *
 * 但系统其实**已经把这些页面暴露成标准 action 了**：
 * `android.settings.BLUETOOTH_SETTINGS`、`android.settings.DISPLAY_SETTINGS`……
 * 华为还额外给了自己的（`com.android.settings.EyeComfortSettings`）。
 * 直接发这个 Intent 一步到位，比搜索快一个数量级，也不受界面改版影响。
 *
 * ## 顺序约定
 * 同一关键词给多个候选，按顺序试（先 AOSP 标准，再厂商私有）。
 * 全都起不来时返回 null，调用方退回搜索兜底，不会更差。
 */
object SettingsShortcuts {

    /**
     * 词 → 候选 action（越靠前越优先）。
     *
     * 只收**确认过、且是设置项**的词；表里没有的词返回 null。
     */
    private val TABLE: Map<String, List<String>> = linkedMapOf(
        // 显示 / 护眼
        "护眼" to listOf(
            "com.android.settings.EyeComfortSettings",
            "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS",
        ),
        "深色" to listOf("android.settings.DARK_THEME_SETTINGS", "android.settings.NIGHT_DISPLAY_SETTINGS"),
        "夜间" to listOf("android.settings.NIGHT_DISPLAY_SETTINGS"),
        "显示" to listOf("android.settings.DISPLAY_SETTINGS"),
        "亮度" to listOf("android.settings.DISPLAY_SETTINGS"),
        "自动旋转" to listOf("android.settings.AUTO_ROTATE_SETTINGS"),
        "字体" to listOf("android.settings.DISPLAY_SETTINGS"),

        // 网络
        "蓝牙" to listOf("android.settings.BLUETOOTH_SETTINGS"),
        "wlan" to listOf("android.settings.WIFI_SETTINGS"),
        "wifi" to listOf("android.settings.WIFI_SETTINGS"),
        "无线" to listOf("android.settings.WIFI_SETTINGS"),
        "流量" to listOf("android.settings.DATA_USAGE_SETTINGS"),
        "移动数据" to listOf("android.settings.DATA_USAGE_SETTINGS"),
        "飞行" to listOf("android.settings.AIRPLANE_MODE_SETTINGS"),
        "热点" to listOf("android.settings.TETHER_SETTINGS"),
        "vpn" to listOf("android.settings.VPN_SETTINGS"),
        "nfc" to listOf("android.settings.NFC_SETTINGS"),
        "投射" to listOf("android.settings.CAST_SETTINGS"),

        // 声音
        "声音" to listOf("android.settings.SOUND_SETTINGS"),
        "振动" to listOf("android.settings.SOUND_SETTINGS"),
        "铃声" to listOf("android.settings.SOUND_SETTINGS"),
        "勿扰" to listOf("android.settings.ZEN_MODE_SETTINGS"),

        // 电池
        "电池优化" to listOf("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
        "省电" to listOf("android.settings.BATTERY_SAVER_SETTINGS"),
        "电池" to listOf("android.settings.BATTERY_SAVER_SETTINGS"),

        // 存储 / 应用
        "存储" to listOf("android.settings.STORAGE_MANAGER_SETTINGS", "android.settings.INTERNAL_STORAGE_SETTINGS"),
        "应用管理" to listOf("android.settings.MANAGE_APPLICATIONS_SETTINGS"),
        "默认应用" to listOf("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        "特殊权限" to listOf("android.settings.SPECIAL_ACCESS_SETTINGS"),

        // 隐私 / 安全
        "隐私" to listOf("android.settings.PRIVACY_SETTINGS"),
        "权限" to listOf("android.settings.PRIVACY_SETTINGS"),
        "定位" to listOf("android.settings.LOCATION_SOURCE_SETTINGS"),
        "位置" to listOf("android.settings.LOCATION_SOURCE_SETTINGS"),
        "安全" to listOf("android.settings.SECURITY_SETTINGS"),
        "锁屏" to listOf("android.settings.LOCK_SCREEN_SETTINGS"),
        "指纹" to listOf("android.settings.FINGERPRINT_SETTINGS"),
        "账户" to listOf("android.settings.ACCOUNT_SYNC_SETTINGS"),
        "账号" to listOf("android.settings.ACCOUNT_SYNC_SETTINGS"),

        // 系统
        "语言" to listOf("android.settings.LOCALE_SETTINGS"),
        "输入法" to listOf("android.settings.INPUT_METHOD_SETTINGS"),
        "键盘" to listOf("android.settings.INPUT_METHOD_SETTINGS"),
        "日期" to listOf("android.settings.DATE_SETTINGS"),
        "时间" to listOf("android.settings.DATE_SETTINGS"),
        "备份" to listOf("android.settings.BACKUP_AND_RESET_SETTINGS"),
        "恢复出厂" to listOf("android.settings.BACKUP_AND_RESET_SETTINGS"),
        "无障碍" to listOf("android.settings.ACCESSIBILITY_SETTINGS"),
        "开发者" to listOf("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
        "关于手机" to listOf("android.settings.DEVICE_INFO_SETTINGS"),
        "设备信息" to listOf("android.settings.DEVICE_INFO_SETTINGS"),
        "通知" to listOf("android.settings.NOTIFICATION_SETTINGS"),
        "状态栏" to listOf("android.settings.STATUS_BAR_SETTINGS"),
        "画中画" to listOf("android.settings.PICTURE_IN_PICTURE_SETTINGS"),
    )

    /**
     * 已经被系统明确拒绝的 action（`SecurityException: not exported`）。
     *
     * 华为不少设置子页只有设置自己（uid 1000）能进，第三方发 Intent 必被拒。
     * 记住它，下次直接跳过——不然每次都要先撞一次墙再走搜索。
     */
    private val dead = mutableSetOf<String>()

    /** 关键词命中的最长词优先（「电池优化」优先于「电池」）。 */
    fun resolve(keyword: String): List<String> {
        val k = keyword.lowercase()
        val longest = TABLE.keys.filter { k.contains(it) }.maxByOrNull { it.length } ?: return emptyList()
        return TABLE[longest].orEmpty().filterNot { it in dead }
    }

    /** 记下这个 action 起不来，后续不再尝试。 */
    fun markDead(action: String) {
        if (action.isNotBlank()) dead += action
    }

    /** 自检用：当前被拉黑的 action。 */
    fun deadActions(): List<String> = dead.toList()

    /** 这张表覆盖了哪些词（给设置页做展示 / 自检用）。 */
    fun keywords(): List<String> = TABLE.keys.toList()
}
