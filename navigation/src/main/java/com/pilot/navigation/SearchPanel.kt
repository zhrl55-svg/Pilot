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
) {

    companion object {
        private const val TAG = "PilotNavSearch"

        /** 停止输入多久后自动搜（毫秒）。太短会边打边搜，太长反应迟钝。 */
        private const val DEBOUNCE_MS = 600L

        /** 最多列几条候选。老板要求 5 条。 */
        private const val MAX_CANDIDATES = 5
    }

    /** 面板根视图。 */
    val root: View by lazy { build() }

    private var input: EditText? = null
    private var resultBox: LinearLayout? = null
    private var candidates: List<SdkPoiSearch.Candidate> = emptyList()
    private var searchJob: Runnable? = null

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                // 贴在转向提示框下方（高德顶部提示约 150dp 高）
                topMargin = dp(158)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        }

        // ---- 输入框（灰色系）----
        val box = EditText(activity).apply {
            hint = "搜地点，或直接说出来"
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.parseColor("#212121"))
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(10), dp(14), dp(10))
            // 灰色系：浅灰底 + 中灰描边
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6EEEEEE"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#BDBDBD"))
            }
            // 打字停下就搜
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    scheduleSearch(s?.toString()?.trim().orEmpty())
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchJob?.let { wrap.handler?.removeCallbacks(it) }
                    doSearch(text.toString().trim())
                    true
                } else false
            }
        }
        input = box
        wrap.addView(box)

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

    /** 防抖：停止输入 600ms 才搜。 */
    private fun scheduleSearch(q: String) {
        searchJob?.let { root.handler?.removeCallbacks(it) }
        if (q.length < 2) {
            resultScroll?.visibility = View.GONE
            candidates = emptyList()
            return
        }
        val job = Runnable { doSearch(q) }
        searchJob = job
        root.postDelayed(job, DEBOUNCE_MS)
    }

    /** 执行搜索（Web API 优先，带距离安全阀）。 */
    private fun doSearch(q: String) {
        if (q.length < 2) return
        NLog.i(TAG, "搜索「$q」")
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
            activity.runOnUiThread { showResults(q, near.take(MAX_CANDIDATES)) }
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
            scroll.visibility = View.VISIBLE
            onSpeak("没找到$q")
            return
        }

        for ((i, c) in list.withIndex()) {
            box.addView(row(i + 1, c))
        }
        scroll.visibility = View.VISIBLE

        // ★ 语音播报候选，让用户认
        //   「找到 5 个。第 1 个，张掖西站停车场。第 2 个，…。说第几个，或者点一下。」
        val spoken = buildString {
            append("找到${list.size}个。")
            for ((i, c) in list.withIndex()) {
                append("第${i + 1}个，${c.name}。")
            }
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
        NLog.i(TAG, "选定第 $index 个：${c.name}")
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

        // 先看是不是「第 N 个」（选候选）
        if (handleVoicePick(text)) return

        // 否则当作地点填进输入框
        NLog.i(TAG, "语音填入：$text")
        input?.setText(text)
        input?.setSelection(text.length)
    }

    /** 显示/隐藏面板。 */
    fun toggle() {
        root.visibility = if (root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (root.visibility == View.VISIBLE) {
            input?.requestFocus()
        }
    }
}
