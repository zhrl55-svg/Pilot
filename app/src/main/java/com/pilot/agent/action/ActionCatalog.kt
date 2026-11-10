package com.pilot.agent.action

import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 可直达的动作目录。
 *
 * ## 原则
 * **但凡能用一个 Intent 启动的，就不要去点屏幕。**
 * 点击是最后兜底——它慢（5~30s）、脆（改版就失效）、还可能点错。
 *
 * ## 三层
 * 1. 系统设置页：`Settings.ACTION_*`，800ms 一步到位
 * 2. 系统动作：拨号、闹钟、相机、地图、搜索……`Intent.ACTION_*`
 * 3. App 内入口：deep link（`weixin://`）或 App 自己声明的 exported Activity
 *
 * 第 3 层不在这个文件里——它需要运行时枚举已安装 App 的 intent-filter，
 * 见 [AppLaunchIndex]。
 */
object ActionCatalog {

    data class Entry(
        /** 触发词，goal 里包含任一即可 */
        val keywords: List<String>,
        /** Intent action；有 [uri] 时用 ACTION_VIEW */
        val action: String,
        val label: String,
        /** deep link，例如 weixin://、taobao:// */
        val uri: String? = null,
        /** 需要额外塞的 extra（闹钟时间之类），暂不支持，留给后续 */
        val pkg: String? = null,
    )

    /**
     * 排序有讲究：**具体的在前，笼统的在后**。
     * 「电池」必须先于「设置」，否则「打开电池设置」会被「设置」抢走。
     */
    private val TABLE: List<Entry> = listOf(
        // ---------------- 系统设置页 ----------------
        // ★ action 的粒度决定落点，别混：
        //   ACTION_POWER_USAGE_SUMMARY → 电池用量（用户说"打开电池"想看的是这个）
        //   ACTION_BATTERY_SAVER_SETTINGS → 节电助手（省电开关，不是电池详情）
        Entry(listOf("电池", "电量", "耗电"), Intent.ACTION_POWER_USAGE_SUMMARY, "电池用量"),
        Entry(listOf("省电", "节电", "电池 saver"), Settings.ACTION_BATTERY_SAVER_SETTINGS, "节电助手"),
        Entry(listOf("wifi", "wi-fi", "无线网", "局域网"), Settings.ACTION_WIFI_SETTINGS, "WLAN"),
        Entry(listOf("蓝牙"), Settings.ACTION_BLUETOOTH_SETTINGS, "蓝牙"),
        Entry(listOf("移动网络", "流量", "数据网络", "双卡"), Settings.ACTION_DATA_ROAMING_SETTINGS, "移动网络"),
        Entry(listOf("飞行模式"), Settings.ACTION_AIRPLANE_MODE_SETTINGS, "飞行模式"),
        Entry(listOf("显示", "亮度"), Settings.ACTION_DISPLAY_SETTINGS, "显示"),
        Entry(listOf("声音", "音量", "铃声"), Settings.ACTION_SOUND_SETTINGS, "声音"),
        Entry(listOf("存储", "空间"), Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "存储"),
        Entry(listOf("应用管理", "已安装应用"), Settings.ACTION_APPLICATION_SETTINGS, "应用"),
        Entry(listOf("位置信息", "定位", "gps"), Settings.ACTION_LOCATION_SOURCE_SETTINGS, "位置信息"),
        Entry(listOf("通知"), "android.settings.NOTIFICATION_SETTINGS", "通知"),
        Entry(listOf("无障碍", "辅助功能"), Settings.ACTION_ACCESSIBILITY_SETTINGS, "无障碍"),
        Entry(listOf("开发者"), Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "开发者选项"),
        Entry(listOf("输入法"), Settings.ACTION_INPUT_METHOD_SETTINGS, "输入法"),
        Entry(listOf("日期", "时间设置"), Settings.ACTION_DATE_SETTINGS, "日期和时间"),
        Entry(listOf("安全", "锁屏", "密码"), Settings.ACTION_SECURITY_SETTINGS, "安全"),
        Entry(listOf("隐私", "权限管理"), Settings.ACTION_PRIVACY_SETTINGS, "隐私"),
        Entry(listOf("账户", "账号"), Settings.ACTION_SYNC_SETTINGS, "账户"),

        // ---------------- 系统动作（不属于设置） ----------------
        Entry(listOf("闹钟", "定闹钟", "叫醒"), "android.intent.action.SET_ALARM", "闹钟"),
        Entry(listOf("计时器", "倒计时"), "android.intent.action.SET_TIMER", "计时器"),
        Entry(listOf("拨号", "打电话", "拨电话"), Intent.ACTION_DIAL, "拨号"),
        Entry(listOf("发短信", "写短信"), Intent.ACTION_SENDTO, "短信", uri = "smsto:"),
        Entry(listOf("相机", "拍照", "照相"), "android.media.action.STILL_IMAGE_CAMERA", "相机"),
        Entry(listOf("录像", "拍视频"), "android.media.action.VIDEO_CAMERA", "录像"),
        Entry(listOf("导航", "地图", "带我去"), Intent.ACTION_VIEW, "地图", uri = "geo:0,0?q="),
        Entry(listOf("网页搜索", "上网搜", "百度一下"), Intent.ACTION_WEB_SEARCH, "网页搜索"),
        Entry(listOf("新建日程", "加日程", "日历"), "android.intent.action.INSERT", "日历"),
        Entry(listOf("发邮件", "写邮件"), Intent.ACTION_SENDTO, "邮件", uri = "mailto:"),
        Entry(listOf("设置主页", "系统设置", "打开设置"), Settings.ACTION_SETTINGS, "设置"),

        // ---------------- 常见 App 的 deep link ----------------
        // 这些是各家公开的 scheme，写死是现实妥协：
        // 没有标准能"发现"别的 App 支持哪些 deep link，只能靠已知目录 + 兜底点击。
        Entry(listOf("微信扫一扫", "扫码"), Intent.ACTION_VIEW, "微信扫一扫", uri = "weixin://scanqrcode"),
        Entry(listOf("微信", "weixin"), Intent.ACTION_VIEW, "微信", uri = "weixin://"),
        Entry(listOf("支付宝扫码", "付款码"), Intent.ACTION_VIEW, "支付宝扫码", uri = "alipays://platformapi/startapp?appId=10000003"),
        Entry(listOf("支付宝"), Intent.ACTION_VIEW, "支付宝", uri = "alipays://"),
        Entry(listOf("淘宝"), Intent.ACTION_VIEW, "淘宝", uri = "taobao://"),
        Entry(listOf("高德"), Intent.ACTION_VIEW, "高德地图", uri = "amapuri://"),
        Entry(listOf("抖音"), Intent.ACTION_VIEW, "抖音", uri = "snssdk1128://"),
        Entry(listOf("美团"), Intent.ACTION_VIEW, "美团", uri = "imeituan://"),
        Entry(listOf("京东"), Intent.ACTION_VIEW, "京东", uri = "openapp.jdmobile://"),
    )

    /** 只有表达"打开/进入/启动"意图时才算直达，别把"电池还剩多少"当成本地跳转 */
    private val INTENT_WORDS = listOf("打开", "启动", "进入", "去", "看看", "查看", "设置", "管理", "扫", "开始")

    fun resolve(goal: String): Entry? {
        val g = goal.lowercase()
        if (INTENT_WORDS.none { g.contains(it) }) return null
        return TABLE.firstOrNull { e -> e.keywords.any { g.contains(it.lowercase()) } }
    }

    fun toIntent(entry: Entry): Intent =
        if (entry.uri != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(entry.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 给云端 planner 看的可用直达清单，让它少走弯路 */
    fun catalog(): String = TABLE.map { it.label }.distinct().joinToString("、")
}
