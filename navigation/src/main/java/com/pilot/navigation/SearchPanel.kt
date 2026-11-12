package com.pilot.navigation

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 导航界面的「搜地点」面板（转弯提示框下方）。
 *
 * ## 交互链路（老板要的）
 * 1. 用户在灰色输入框里打字，或**语音说出来**（识别结果自动填入）
 * 2. 停下来 600ms 自动搜（不用按搜索键），列出**最近 5 条**候选
 * 3. 语音播报「找到 5 个地点，说第几个或者点一下」，
 *    并把前几条念出来让用户认
 * 4. 用户说「第 2 个」或点第 2 条 → 直接开始导航
 *
 * ## 为什么做在导航界面里
 * 开车时最需要的操作是「临时改目的地」——停车、加油、找个厕所。
 * 这时候去主界面重新喊一遍「导航去XX」要多好几步，
 * 而导航界面上直接搜（还能语音说）最省事。
 */
class SearchPanel(
    private val activity: Activity,
    /** 用户选定目的地后回调（开始导航） */
    private val onPick: (SdkPoiSearch.Candidate) -> Unit,
    /** 要播报一句话（走统一 TTS 队列） */
    private val onSpeak: (String) -> Unit,
    /** 语音说「结束导航」时回调（真正停导航，不是退出界面） */
    private val onEndNavi: () -> Unit = {},
) {

    companion object {
        private const val TAG = "PilotNavSearch"

        /** 停止输入多久后自动搜（毫秒）。太短会边打边搜，太长反应迟钝。 */
        private const val DEBOUNCE_MS = 600L

        /** 最多列几条候选。老板要求 5 条。 */
        private const val MAX_CANDIDATES = 5

        /**
         * 结果出来后延迟多久再念。
         *
         * 打字过程中可能连续搜两次（比如先打「中央」再打「中央公园」）。
         * 候选列表可以立刻显示，但播报要等输入稳定；期间只要又有新输入，
         * 就把上一次待播的念稿取消，避免同一批地点被念两遍。
         */
        private const val SPEAK_DELAY_MS = 1200L

        /** 列表出来后多久没人选就自动收起（老板要的 20 秒）。 */
        private const val IDLE_CLEAR_MS = 20_000L
    }

    /** 面板根视图。 */
    val root: View by lazy { build() }

    private var input: EditText? = null
    private var resultBox: LinearLayout? = null
    private var candidates: List<SdkPoiSearch.Candidate> = emptyList()
    private var searchJob: Runnable? = null
    private var speakJob: Runnable? = null
    private var idleClearJob: Runnable? = null

    /**
     * 搜索请求版本号。
     *
     * 输入中文时 `afterTextChanged` 可能连发好几次，旧请求比新请求晚回来
     * 就会把旧候选盖到新候选上——日志里表现为**同一句话播两遍**。
     * 每次输入/搜索都 +1，回包时版本对不上直接丢弃。
     */
    private var queryVersion = 0

    /** 上一次已经播报过的候选签名（防止同一批结果重复念）。 */
    private var lastSpokenSignature = ""
    private var lastSpokenAt = 0L

    /**
     * 上一次用过的语音识别文本时间戳。
     *
     * ★ 初始值必须取**当前**时间戳，不能是 0。
     *   否则一进界面就把上一次导航时说的话（比如「导航去张掖西站」）
     *   当成新输入重新搜一遍——实测踩过：刚打开导航界面就自己跑了一遍搜索。
     */
    private var lastAsrTs = runCatching {
        VoiceState.readAsr(activity)?.second ?: 0L
    }.getOrDefault(0L)

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics,
    ).toInt()

    private fun build(): View {
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                (activity.resources.displayMetrics.widthPixels / 2 - dp(24)).coerceAtLeast(dp(160)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                // 贴在转向提示框下方（高德顶部提示约 150dp 高）
                topMargin = dp(158)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }

        // ---- 输入框（灰色系）----
        val box = EditText(activity).apply {
            hint = "搜地点"
            // 底色和右侧圆形按钮统一（灰色半透明），文字用深色保证可读
            setHintTextColor(Color.parseColor("#4F4F4F"))
            setTextColor(Color.parseColor("#212121"))
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(10), dp(42), dp(10))
            // 底色统一用 NavColors.FLOATING_BTN（和右侧圆形按钮同一份配色）。
            background = GradientDrawable().apply {
                setColor(NavColors.FLOATING_BTN)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            // 打字停下就搜
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    clearButton?.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    scheduleSearch(s?.toString()?.trim().orEmpty())
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchJob?.let { wrap.handler?.removeCallbacks(it) }
                    queryVersion++
                    doSearch(text.toString().trim(), queryVersion)
                    true
                } else false
            }
        }
        input = box
        val inputFrame = FrameLayout(activity)
        inputFrame.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val clear = TextView(activity).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6C6C6C"))
            contentDescription = "清除搜索文字"
            visibility = View.GONE
            isClickable = true
            setOnClickListener { input?.text?.clear() }
        }
        clearButton = clear
        inputFrame.addView(clear, FrameLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        wrap.addView(inputFrame)

        // ---- 结果列表 ----
        val listWrap = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240),
            ).apply { topMargin = dp(6) }
            isFillViewport = false
        }
        val box2 = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2FFFFFF"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        resultBox = box2
        listWrap.addView(box2)
        wrap.addView(listWrap)

        // 初始隐藏结果列表（输入为空时不需要占位）
        listWrap.visibility = View.GONE
        resultScroll = listWrap

        return wrap
    }

    private var resultScroll: ScrollView? = null
    private var clearButton: TextView? = null

    /** 防抖：停止输入 600ms 才搜。 */
    private fun scheduleSearch(q: String) {
        searchJob?.let { root.handler?.removeCallbacks(it) }
        cancelPendingSpeech()
        queryVersion++
        val version = queryVersion
        if (q.length < 2) {
            resultScroll?.visibility = View.GONE
            candidates = emptyList()
            runCatching { VoiceState.clearNavSearch(activity) }
            cancelIdleClear()
            return
        }
        val job = Runnable { doSearch(q, version) }
        searchJob = job
        root.postDelayed(job, DEBOUNCE_MS)
    }

    /** 执行搜索（Web API 优先，带距离安全阀）。 */
    private fun doSearch(q: String, version: Int) {
        if (q.length < 2 || version != queryVersion) return
        NLog.i(TAG, "搜索「$q」version=$version")
        val loc = NavigationService.peekLocationProvider()
        val lat = loc?.lat ?: 0.0
        val lon = loc?.lon ?: 0.0
        val city = loc?.city
        Thread {
            val list = runCatching {
                WebPoiSearch.searchBlocking(q, city, lat, lon, limit = 10)
            }.getOrDefault(emptyList())
            // 距离安全阀：超过 50km 的丢掉（防止搜出外地同名地点）
            val near = if (lat != 0.0) {
                list.filter { it.distance in 1..WebPoiSearch.MAX_SANE_DISTANCE_M }
                    .ifEmpty { list.take(3) }
            } else list
            activity.runOnUiThread {
                if (version != queryVersion) {
                    NLog.i(TAG, "丢弃过期搜索结果「$q」version=$version（当前 $queryVersion）")
                    return@runOnUiThread
                }
                if (input?.text?.toString()?.trim() != q) {
                    NLog.i(TAG, "丢弃已改词的搜索结果「$q」")
                    return@runOnUiThread
                }
                showResults(q, near.take(MAX_CANDIDATES))
            }
        }.apply { name = "nav-search"; isDaemon = true }.start()
    }

    /** 显示候选（点一下直接导航）。 */
    private fun showResults(q: String, list: List<SdkPoiSearch.Candidate>) {
        val box = resultBox ?: return
        val scroll = resultScroll ?: return
        candidates = list
        box.removeAllViews()

        if (list.isEmpty()) {
            box.addView(TextView(activity).apply {
                text = "没找到「$q」"
                setTextColor(Color.parseColor("#757575"))
                textSize = 14f
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            candidates = emptyList()
            runCatching { VoiceState.clearNavSearch(activity) }
            scroll.visibility = View.VISIBLE
            scheduleIdleClear()
            scheduleSpeakCandidates(q, list)
            return
        }

        for ((i, c) in list.withIndex()) {
            box.addView(row(i + 1, c))
        }
        scroll.visibility = View.VISIBLE

        // 列表出来后 20 秒没人选就自动收起（老板要求）
        // 面板收起来时不能登记候选，否则导航播报中说「第二个」会误选看不见的条目
        if (root.visibility == View.VISIBLE) VoiceState.writeNavSearch(activity, list.size)
        else runCatching { VoiceState.clearNavSearch(activity) }
        scheduleIdleClear()

        // ★ 语音播报候选，让用户认
        //   「找到 5 个。第 1 个，张掖西站停车场。第 2 个，…。说第几个，或者点一下。」
        scheduleSpeakCandidates(q, list)
    }

    /**
     * 20 秒后自动清掉输入文字和候选列表。
     *
     * 为什么要有：开车时搜完又不想去了，面板会一直挂在地图上挡路。
     * 任何一次新的输入/结果都会重新计时。
     */
    private fun scheduleIdleClear() {
        cancelIdleClear()
        val job = Runnable {
            idleClearJob = null
            if (candidates.isEmpty() && input?.text.isNullOrEmpty()) return@Runnable
            NLog.i(TAG, "20 秒未选择，自动清除搜索文字与候选列表")
            cancelPendingSpeech()
            runCatching { AgentBridge.stopSpeaking(activity) }
            candidates = emptyList()
            resultBox?.removeAllViews()
            resultScroll?.visibility = View.GONE
            clearButton?.visibility = View.GONE
            input?.setText("")
            runCatching { VoiceState.clearNavSearch(activity) }
        }
        idleClearJob = job
        root.postDelayed(job, IDLE_CLEAR_MS)
    }

    private fun cancelIdleClear() {
        idleClearJob?.let { root.handler?.removeCallbacks(it) }
        idleClearJob = null
    }

    /** 等输入真正稳定后再念候选；新输入会取消这次待播。 */
    private fun scheduleSpeakCandidates(q: String, list: List<SdkPoiSearch.Candidate>) {
        cancelPendingSpeech()
        val job = Runnable { speakCandidates(q, list) }
        speakJob = job
        root.postDelayed(job, SPEAK_DELAY_MS)
    }

    private fun cancelPendingSpeech() {
        speakJob?.let { root.handler?.removeCallbacks(it) }
        speakJob = null
    }

    /**
     * 播报候选（同一批结果只念一次）。
     *
     * 之前两次搜索回包会各念一遍，听起来就是「模型读了两遍」。
     * 这里用「查询词 + 候选名/坐标」做签名，15 秒内同一批结果不重复念。
     */
    private fun speakCandidates(q: String, list: List<SdkPoiSearch.Candidate>) {
        val signature = buildString {
            append(q).append('|')
            for (c in list) append(c.name).append(c.lat).append(',').append(c.lon).append(';')
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (signature == lastSpokenSignature && now - lastSpokenAt < 15_000L) {
            NLog.i(TAG, "同一批候选已播报过，跳过：$q")
            return
        }
        lastSpokenSignature = signature
        lastSpokenAt = now

        if (list.isEmpty()) {
            onSpeak("没找到$q")
            return
        }
        val spoken = buildString {
            append("找到${list.size}个。")
            // ★ 不念「第 N 个」：念了会被常驻 KWS 当成用户说的选序号，
            //   播报自己打断自己，直接跳到第 1 个。
            for (c in list) append("${c.name}。")
            append("说第几个，或者点一下。")
        }
        NLog.i(TAG, "播报候选：$spoken")
        onSpeak(spoken)
    }

    /** 一条候选：序号 + 名字 + 距离。 */
    private fun row(index: Int, c: SdkPoiSearch.Candidate): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener {
                NLog.i(TAG, "用户点了第 $index 个：${c.name}")
                pick(index)
            }
        }
        row.addView(TextView(activity).apply {
            text = "$index"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#9E9E9E"))
                cornerRadius = dp(9).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = c.name
            setTextColor(Color.parseColor("#212121"))
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(activity).apply {
            text = if (c.distance > 0) "${c.address.take(18)} · ${c.distance}米" else c.address.take(24)
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textCol)
        return row
    }

    /** 选中第 n 个（1 起）。 */
    fun pick(index: Int) {
        val c = candidates.getOrNull(index - 1) ?: run {
            onSpeak("没有第${index}个")
            return
        }
        // 选完就别再让还在路上的旧搜索回包播报了
        queryVersion++
        searchJob?.let { root.handler?.removeCallbacks(it) }
        searchJob = null
        cancelPendingSpeech()
        cancelIdleClear()
        runCatching { VoiceState.clearNavSearch(activity) }
        // 选完清空候选与输入，避免同一句话再说一次又导航一遍
        candidates = emptyList()
        input?.setText("")
        NLog.i(TAG, "选定第 $index 个：${c.name}")
        // ★ 列表可能还在念候选，点选后必须马上闭嘴（云端 TTS 不能只靠队列 FLUSH）
        runCatching { AgentBridge.stopSpeaking(activity) }
        onSpeak("开始导航去${c.name}")
        onPick(c)
        // 收起面板（让用户看到路线）
        root.visibility = View.GONE
    }

    /**
     * 处理语音输入的「第 N 个」。
     * @return true = 已处理（调用方不用再管这句话）
     */
    fun handleVoicePick(text: String): Boolean {
        if (candidates.isEmpty()) return false
        // 「第2个」「第二个」「2」「选第二个」「就第二个吧」
        val n = parseOrdinal(text) ?: return false
        NLog.i(TAG, "语音选第 $n 个（原话：$text）")
        pick(n)
        return true
    }

    /** 解析「第2个/第二个/选2/就第3个吧」里的序号。 */
    private fun parseOrdinal(text: String): Int? {
        val t = text.trim()
        // 阿拉伯数字
        Regex("第?\\s*(\\d{1,2})\\s*个?").find(t)?.let { m ->
            m.groupValues.getOrNull(1)?.toIntOrNull()?.let { if (it in 1..9) return it }
        }
        // 中文数字
        val cn = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9, '两' to 2,
        )
        for ((ch, v) in cn) {
            if (t.contains("第$ch") || t.contains("选$ch") || t == ch.toString()) return v
        }
        return null
    }

    /** 轮询语音识别结果，自动填入输入框（老板要的「语音识别到地址后自动填入」）。 */
    fun pollVoiceInput() {
        val r = VoiceState.readAsr(activity) ?: return
        val (text, ts) = r
        if (ts <= lastAsrTs) return
        lastAsrTs = ts

        // 「结束导航」是控制指令，不能当地名塞进搜索框
        if (isEndNaviCommand(text)) {
            val state = NavigationService.peek()?.currentState
            val active = state == NavigationService.State.ROUTING ||
                state == NavigationService.State.NAVIGATING
            NLog.i(TAG, "语音指令「$text」→ 结束导航（当前 $state）")
            cancelPendingSpeech()
            cancelIdleClear()
            runCatching { VoiceState.clearNavSearch(activity) }
            candidates = emptyList()
            resultBox?.removeAllViews()
            resultScroll?.visibility = View.GONE
            input?.setText("")
            runCatching { VoiceState.disarmNavVoice(activity) }
            if (active) onEndNavi()
            return
        }

        // 先看是不是「第 N 个」（选候选）
        if (handleVoicePick(text)) {
            runCatching { VoiceState.disarmNavVoice(activity) }
            return
        }

        // ★ 只有「点导航麦克风」发起的识别才当地名。
        //   否则地图界面喊「小派，讲个故事」会被拿去搜 POI（实测踩过）。
        if (!VoiceState.isNavVoiceArmed(activity)) {
            NLog.i(TAG, "语音不是导航麦克风发起，忽略填词：$text")
            return
        }

        // 「导航去张掖西站」这类整句要截成地名，不能整句塞进搜索框
        val query = poiQueryFromVoice(text)

        // 同一句话已经填过就不再 setText，避免又触发一轮搜索
        if (input?.text?.toString()?.trim() == query) {
            NLog.i(TAG, "语音文本与输入框一致，跳过重复填入：$text")
            return
        }

        // 否则当作地点填进输入框
        NLog.i(TAG, "语音填入：$text → $query")
        runCatching { VoiceState.disarmNavVoice(activity) }
        input?.setText(query)
        input?.setSelection(query.length)
    }

    /**
     * 从语音原话里抠出「地名」。
     *
     * 老板说「导航去张掖西站」，ASR 给的是整句；原来整句塞进输入框，
     * 搜索词变成一句指令，结果一条也搜不到。
     * 这里只剥掉开头的动词短语和结尾的语气词，地名本身一个字都不动
     * （「去张掖西站」→「张掖西站」，普通地名如「张掖西站」原样保留）。
     */
    private fun poiQueryFromVoice(text: String): String {
        var s = text.trim().trim('。', '，', ',', '.', '！', '!', '？', '?', ' ')
        val command = Regex(
            "^(?:请|帮我|麻烦)?\\s*(?:导航去|导航到|导航|带我去往|带我去|带我到|我要去|我想去|前往|去|到)\\s*",
        )
        s = s.replace(command, "").trim()
        s = s.replace(Regex("(吧|可以吗|好吗|行吗|谢谢)$"), "").trim()
        return s.ifBlank { text.trim() }
    }

    /** 「结束导航 / 停止导航 / 退出导航 / 不导航了」这类控制指令。 */
    private fun isEndNaviCommand(text: String): Boolean {
        val t = text.trim()
        return Regex("(结束|停止|退出|关闭|取消).{0,3}导航").containsMatchIn(t) ||
            t == "不导航了" || t == "导航结束"
    }

    /** 清空输入和候选（结束导航时调）。 */
    fun reset() {
        queryVersion++
        searchJob?.let { root.handler?.removeCallbacks(it) }
        searchJob = null
        cancelPendingSpeech()
        cancelIdleClear()
        runCatching { VoiceState.clearNavSearch(activity) }
        input?.setText("")
        candidates = emptyList()
        resultBox?.removeAllViews()
        resultScroll?.visibility = View.GONE
        lastSpokenSignature = ""
        lastSpokenAt = 0L
        NLog.i(TAG, "搜索面板已重置")
    }

    /** 显示/隐藏面板（导航中要收起，别挡住路线）。 */
    fun setVisible(visible: Boolean) {
        if (!visible) {
            cancelPendingSpeech()
            cancelIdleClear()
            runCatching { VoiceState.clearNavSearch(activity) }
        }
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 显示/隐藏面板。 */
    fun toggle() {
        setVisible(root.visibility != View.VISIBLE)
        if (root.visibility == View.VISIBLE) {
            input?.requestFocus()
        }
    }
}
