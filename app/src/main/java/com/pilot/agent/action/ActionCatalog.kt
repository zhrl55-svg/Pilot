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
        Entry(listOf("蓝牙", "bluetooth"), Settings.ACTION_BLUETOOTH_SETTINGS, "蓝牙"),
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

    /**
     * 意图动词：只有紧挨在目标词前面（或整句就是目标词）才算直达。
     *
     * ★ 这里曾经是「整句含意图词 + 整句含目标词」，而词表里连「设置」「查看」
     *   都有 —— 「你测试一下系统设置执行生效了没有」既含「设置」又含
     *   「系统设置」，真的把设置页打开了。用户说的明明是另一件事。
     *   设置页是给别人看的界面，不该因为一句话里顺带提到软件功能就弹出来。
     */
    private val INTENT_WORDS = listOf(
        "打开", "启动", "进入", "跳到", "跳转", "前往",
        "看看", "查看", "浏览", "管理", "扫", "开始",
        "open", "launch", "go to", "show",
    )

    /** 单字意图词必须**紧贴**目标词：「去设置」行，「我昨天去了系统设置」不行。 */
    private val ADJACENT_INTENT = listOf("去", "到")

    /** 后置动词：「把蓝牙打开」也算命令，但必须紧挨着目标词。 */
    private val POST_VERBS = listOf("打开", "开启", "关闭", "关掉", "启用", "禁用", "开一下", "关一下")

    /** 目标词后面只允许这些尾巴；再多就是顺带提及，不是"去打开它"。 */
    private val TAIL_OK = setOf(
        "", "设置", "设置页", "设置页面", "设置界面", "设置里",
        "页", "页面", "界面", "里", "详情", "一下", "看看", "看一下", "settings",
    )

    /** 在**聊**这个功能的句子，一律不动手。 */
    private val TOPIC_CUES = listOf(
        "讨论", "方案", "原理", "代码", "日志", "排查", "实现",
        "接口", "设计", "开发", "优化", "重构", "架构",
    )

    /** 意图词与目标词之间允许隔几个字（「打开一下蓝牙」）。 */
    private const val INTENT_WINDOW = 8

    fun resolve(goal: String): Entry? {
        val g = goal.lowercase().trim()
        if (g.isEmpty()) return null
        if (TOPIC_CUES.any { g.contains(it) }) return null
        return TABLE.firstOrNull { e -> e.keywords.any { isDirectiveReference(g, it.lowercase()) } }
    }

    /**
     * 按**目标名**找条目，不再做意图判定。
     *
     * 给已经抽过槽的调用方用：用户说了「打开 X」，槽位就是 X，
     * 此时「系统设置」这种纯名词本身已经是命令对象。
     */
    fun resolveTarget(name: String): Entry? {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return null
        return TABLE.firstOrNull { e -> e.keywords.any { n.contains(it.lowercase()) } }
    }

    /**
     * 目标词在这句话里是不是被当成「去打开 / 去看看」的对象。
     *
     * 判据刻意落在**词与词的相对位置**上，而不是整句里"有没有"：
     * - 前面 8 个字内出现意图动词（「打开蓝牙设置」）；
     * - 或单字意图词紧贴（「去设置」）；
     * - 或整句就是一个目标名（「系统设置」「电池」）；
     * - 或后置动词紧贴（「把蓝牙打开」）。
     * 其余一律视为提及 —— 宁可漏给云端模型（它能读上下文），也不能误弹页面。
     */
    fun isDirectiveReference(goal: String, target: String): Boolean {
        val g = goal.lowercase().trim()
        val t = target.lowercase().trim()
        if (g.isEmpty() || t.isEmpty()) return false
        var from = 0
        while (true) {
            val i = g.indexOf(t, from)
            if (i < 0) return false
            if (referenceOk(g, i, t)) return true
            from = i + 1
        }
    }

    private fun referenceOk(g: String, i: Int, t: String): Boolean {
        val head = g.substring(0, i).trim()
        val tail = g.substring(i + t.length).trim()
            .trimEnd('。', '！', '!', '，', ',', '~', '～')
        val tailOk = tail.isEmpty() || tail in TAIL_OK
        val postIntent = POST_VERBS.any { tail == it || tail == it + "一下" || tail == it + "吧" }
        if (!tailOk && !postIntent) return false

        // 整句就是一个目标名：「系统设置」「电池」「打开设置」（关键词本身带动词）。
        if (head.isEmpty()) return tailOk

        val pre = g.substring(maxOf(0, i - INTENT_WINDOW), i)
        if (INTENT_WORDS.any { pre.contains(it) }) return true
        if (ADJACENT_INTENT.any { pre.endsWith(it) }) return true
        return postIntent
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
