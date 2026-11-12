package com.pilot.agent.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pilot.agent.sandbox.SandboxTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visible shell for the same persistent sandbox session used by the agent.
 * Keeping one execution backend avoids a second, subtly different terminal.
 */
class TerminalActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var runButton: Button
    private val transcript = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        SandboxTerminal.init(applicationContext)
        setContentView(buildContent())
        appendLine("Pilot 终端")
        appendLine("与 Agent 共用沙箱会话。首次执行需要初始化 Alpine，通常约 30 秒。")
        appendLine("输入命令后按回车或点“运行”。")
        appendLine("")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 8.dp, 10.dp, 8.dp)
            setBackgroundColor(Color.rgb(12, 18, 28))
        }
        header.addView(TextView(this).apply {
            text = "PILOT TERMINAL"
            setTextColor(Color.rgb(96, 165, 250))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "清空"
            setOnClickListener {
                transcript.clear()
                output.text = ""
            }
        })
        header.addView(Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        })
        root.addView(header)

        outputScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 8, 13))
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        output = TextView(this).apply {
            setTextColor(Color.rgb(209, 250, 229))
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(14.dp, 12.dp, 14.dp, 18.dp)
            typeface = Typeface.MONOSPACE
        }
        outputScroll.addView(
            output,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(outputScroll)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 10.dp)
            setBackgroundColor(Color.rgb(12, 18, 28))
        }
        commandInput = EditText(this).apply {
            hint = "输入 shell 命令"
            setHintTextColor(Color.rgb(100, 116, 139))
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            minimumHeight = 48.dp
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    executeCurrent()
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        footer.addView(commandInput)
        runButton = Button(this).apply {
            text = "运行"
            setOnClickListener { executeCurrent() }
        }
        footer.addView(runButton)
        runButton.minimumHeight = 48.dp
        root.addView(footer)
        return root
    }

    private fun executeCurrent() {
        if (!runButton.isEnabled) return
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) return
        commandInput.setText("")
        appendLine("$ $command")
        setRunning(true)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SandboxTerminal.run(applicationContext, command, 420_000L)
                }
            }.getOrElse {
                SandboxTerminal.Result(
                    output = "执行失败：${it.message ?: it.javaClass.simpleName}",
                    exitCode = -1,
                    durationMs = 0L,
                )
            }
            if (result.output.isNotBlank()) appendLine(result.output.trimEnd())
            appendLine("[exit=${result.exitCode}, ${result.durationMs} ms]")
            appendLine("")
            setRunning(false)
        }
    }

    private fun setRunning(running: Boolean) {
        runButton.isEnabled = !running
        runButton.text = if (running) "执行中…" else "运行"
        commandInput.isEnabled = !running
        if (!running) commandInput.requestFocus()
    }

    private fun appendLine(text: String) {
        if (transcript.isNotEmpty()) transcript.append('\n')
        transcript.append(text)
        if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            transcript.delete(0, transcript.length - MAX_TRANSCRIPT_CHARS)
        }
        output.text = transcript
        outputScroll.post { outputScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 120_000
    }
}
