package com.pilot.agent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilot.agent.agent.AgentSettings

class MainActivity : ComponentActivity() {

    private val vm: AgentViewModel by viewModels()

    private val micPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.toggleVoice()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PilotTheme {
                AgentScreen(vm, onMicClick = ::requestMic)
            }
        }
        handleGoalIntent(intent)
    }

    private fun requestMic() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            vm.toggleVoice()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGoalIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStatus()
    }

    /**
     * 自动化测试入口：
     *   adb shell am start -n com.pilot.agent.debug/com.pilot.agent.ui.MainActivity \
     *       --es goal "打开设置"
     * 省掉「点输入框、敲文字、点执行」三步，回归脚本能一把跑完。
     */
    private fun handleGoalIntent(intent: Intent?) {
        val goal = intent?.getStringExtra("goal")?.trim().orEmpty()
        if (goal.isEmpty()) return
        intent?.removeExtra("goal")
        window.decorView.postDelayed({ vm.run(goal) }, 900)
    }
}

@Composable
private fun AgentScreen(vm: AgentViewModel, onMicClick: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(vm.logs.size) {
        if (vm.logs.isNotEmpty()) listState.animateScrollToItem(vm.logs.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Header(
            vm,
            onSettings = { showSettings = true },
            onNavOpen = {
                // 直接拉起导航界面（NavActivity 在 :navigation 进程）
                runCatching {
                    ctx.startActivity(
                        Intent().apply {
                            setClassName(ctx.packageName, "com.pilot.navigation.NavActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure {
                    android.widget.Toast.makeText(
                        ctx, "打不开导航界面：${it.message}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        StatusCard(
            vm,
            onEnable = { openAccessibilitySettings(ctx) },
            onToggleWake = { vm.toggleWake() },
        )
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            if (vm.logs.isEmpty()) {
                EmptyHint()
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.logs, key = { it.id }) { LogRow(it) }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        InputBar(
            value = input,
            onValueChange = { input = it },
            running = vm.running,
            listening = vm.voiceState == com.pilot.agent.voice.VoiceInput.State.LISTENING ||
                vm.voiceState == com.pilot.agent.voice.VoiceInput.State.RECOGNIZING,
            onSend = {
                vm.run(input)
                input = ""
            },
            onStop = { vm.stop() },
            onMicClick = onMicClick,
        )
    }

    vm.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.answerConfirm(false) },
            title = { Text("需要你确认") },
            text = { Text(c.question) },
            confirmButton = {
                TextButton(onClick = { vm.answerConfirm(true) }) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerConfirm(false) }) { Text("取消") }
            },
        )
    }

    if (showSettings) {
        SettingsDialog(vm, onDismiss = { showSettings = false })
    }

    // 二维码单独一层：不嵌在设置弹窗里，扫码时设置可以先关掉
    val qr = vm.qrBitmap
    if (qr != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { vm.cancelQrLogin() }) {
            androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("扫码登录网易云", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(220.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        vm.loginTip.ifEmpty { "用网易云 App 扫码" },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.cancelQrLogin() }) { Text("取消", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun Header(vm: AgentViewModel, onSettings: () -> Unit, onNavOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (vm.accessibilityOn) colors.primary else colors.error, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Pilot", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = colors.onBackground)
            Text(
                if (vm.foreground.isNotEmpty()) "当前 ${vm.foreground}" else "系统级手机助理",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ★ 导航入口（老板要求）：直接进高德导航界面。
            //   放标题栏而不是输入栏——放输入栏会把输入框挤窄
            //   （实测「让 Pilot 做什么？」被挤成两行）。
            // 老板要求：只显示文字，不要图标
            TextButton(onClick = onNavOpen) {
                Text("导航", fontSize = 13.sp)
            }
            TextButton(onClick = onSettings) { Text("设置", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun StatusCard(vm: AgentViewModel, onEnable: () -> Unit, onToggleWake: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.accessibilityOn) "操作权限 已就绪" else "操作权限 未开启",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (vm.accessibilityOn) colors.primary else colors.error,
                )
                Spacer(Modifier.weight(1f))
                if (!vm.accessibilityOn) {
                    Button(
                        onClick = onEnable,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    ) { Text("去开启", fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Metric("规划器", if (vm.settings.cloudReady) "端云双速" else "端侧规则")
                Spacer(Modifier.width(20.dp))
                Metric("云端模型", if (vm.settings.cloudReady) vm.settings.model else "未配置")
                Spacer(Modifier.width(20.dp))
                Metric("技能", "${vm.skillCount} 条")
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Metric("端侧ASR", if (vm.asrReady) "就绪" else vm.asrNote)
                Spacer(Modifier.width(20.dp))
                Metric("端侧LLM", if (vm.llmReady) "就绪" else vm.llmNote)
                Spacer(Modifier.width(20.dp))
                Metric(
                    "声纹",
                    vm.voiceprintInfo.ifEmpty { if (vm.voiceprintEnrolled) "已注册" else "未注册" },
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (vm.voiceState) {
                        com.pilot.agent.voice.VoiceInput.State.WAKE_WAIT -> "待命中，说唤醒词就能用"
                        com.pilot.agent.voice.VoiceInput.State.ASLEEP -> "已休眠，说唤醒词或点一下麦克风"
                        com.pilot.agent.voice.VoiceInput.State.ACTIVE -> "在等你接着说（不用再喊唤醒词）"
                        com.pilot.agent.voice.VoiceInput.State.LISTENING -> "在听，说完停顿一下"
                        com.pilot.agent.voice.VoiceInput.State.RECOGNIZING -> "识别中…"
                        else -> "未开启语音"
                    },
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleWake) {
                    Text(
                        if (vm.wakeOn) "停止待命" else "开始待命",
                        fontSize = 12.sp,
                        color = if (vm.wakeOn) colors.error else colors.primary,
                    )
                }
            }
            if (vm.progress.second > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "步骤 ${vm.progress.first} / ${vm.progress.second}",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = colors.onBackground, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = MaterialTheme.colorScheme
    val (tint, prefix) = when (entry.kind) {
        LogEntry.Kind.USER -> colors.onBackground to "你"
        LogEntry.Kind.THINK -> colors.onSurfaceVariant to "想"
        LogEntry.Kind.ACT -> colors.primary to "做"
        LogEntry.Kind.OBSERVE -> colors.secondary to "看"
        LogEntry.Kind.DONE -> colors.primary to "完成"
        LogEntry.Kind.ERROR -> colors.error to "注意"
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            prefix,
            fontSize = 11.sp,
            color = tint,
            modifier = Modifier
                .width(34.dp)
                .padding(top = 2.dp),
        )
        Text(entry.text, fontSize = 13.sp, color = tint, lineHeight = 19.sp)
    }
}

@Composable
private fun EmptyHint() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("说一句话，剩下的我来", fontSize = 15.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            "试试：打开微信\n点开第一条消息\n返回桌面",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    listening: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMicClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("让 Pilot 做什么？", fontSize = 13.sp) },
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        // 按住说话：点一下开始，再点一下结束。端侧识别，断句静默 800ms 自动出结果。
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    if (listening) colors.error else colors.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onMicClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (listening) "停" else "说",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (listening) colors.onError else colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = if (running) onStop else onSend,
            enabled = running || value.isNotBlank(),
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) colors.error else colors.primary,
                contentColor = if (running) colors.onError else colors.onPrimary,
            ),
        ) {
            Text(if (running) "停止" else "执行", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        ) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary,
            )
            if (hint != null) Hint(hint)
        }
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.surfaceVariant.copy(alpha = 0.35f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

/**
 * 选项按钮组。选中态必须有底色——原来只改文字颜色，一眼看不出当前选的是哪个。
 */
@Composable
private fun OptionChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .background(
                        if (active) colors.primary else colors.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 紧凑输入框：标题在上、框在下。
 * 不用 Material3 的浮动 label——那会让每个框固定 56dp，十几个框叠起来非常占地方。
 */
@Composable
private fun SmallField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** 密钥类字段：默认掩码，点右边能临时看一下 */
    secret: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var revealed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 10.sp, color = colors.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(8.dp),
            // ★ 密钥默认掩码：设置页经常被截图/被人瞄一眼，明文等于泄露
            visualTransformation = if (secret && !revealed) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            trailingIcon = if (secret) {
                {
                    Text(
                        if (revealed) "隐藏" else "显示",
                        fontSize = 10.sp,
                        color = colors.primary.copy(alpha = 0.8f),
                        modifier = Modifier
                            .clickable { revealed = !revealed }
                            .padding(end = 10.dp),
                    )
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        )
    }
}

/**
 * 小号开关。
 * Material3 的 Switch 是 52x32dp，跟 12sp 的说明文字放一起比例失衡，还很占宽度。
 * 缩放 0.7 后用 40x24 的盒子占位，视觉和布局一起变小。
 */
@Composable
private fun CompactSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier.size(width = 40.dp, height = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.7f),
        )
    }
}

/** 说明文字收进 ⓘ，点一下弹出。省版面，又不丢信息 */
@Composable
private fun Hint(text: String) {
    val colors = MaterialTheme.colorScheme
    var show by remember { mutableStateOf(false) }
    Text(
        "ⓘ",
        fontSize = 12.sp,
        color = colors.primary.copy(alpha = 0.75f),
        modifier = Modifier
            .clickable { show = true }
            .padding(start = 4.dp, end = 4.dp),
    )
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            text = { Text(text, fontSize = 12.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text("知道了", fontSize = 12.sp) }
            },
        )
    }
}

@Composable
private fun SettingsDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    var baseUrl by remember { mutableStateOf(vm.settings.baseUrl) }
    var apiKey by remember { mutableStateOf(vm.settings.apiKey) }
    var model by remember { mutableStateOf(vm.settings.model) }
    var maxTurns by remember { mutableStateOf(vm.settings.maxTurns.toString()) }
    var preferOnDevice by remember { mutableStateOf(vm.settings.preferOnDevice) }
    var ttsEngine by remember { mutableStateOf(vm.settings.ttsEngine) }
    var musicPolicy by remember { mutableStateOf(vm.settings.musicPolicy) }
    var sandboxIdleMin by remember { mutableStateOf(vm.settings.sandboxIdleMinutes.toString()) }
    var wakeWords by remember { mutableStateOf(vm.settings.wakeWords) }
    var wakeTone by remember { mutableStateOf(vm.settings.wakeTone) }
    var idleSleep by remember { mutableStateOf(vm.settings.idleSleepSeconds.toString()) }
    var interruptOn by remember { mutableStateOf(vm.settings.interruptEnabled) }
    var interruptWords by remember { mutableStateOf(vm.settings.interruptWords) }
    var showEnroll by remember { mutableStateOf(false) }
    var requireVp by remember { mutableStateOf(vm.settings.requireVoiceprint) }
    var vpThreshold by remember {
        mutableStateOf(String.format(java.util.Locale.US, "%.2f", vm.settings.voiceprintThreshold))
    }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------- 语音唤醒 ----------------
                SettingsSection("语音唤醒") {
                    SmallField(wakeWords, { wakeWords = it }, "唤醒词（逗号分隔）")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("唤醒提示音", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Hint("命中唤醒词后播一声短 beep，告诉你\u201c我在听\u201d。麦克风会听到它，但只有约 100ms，识别层按唤醒回声丢弃。")
                        CompactSwitch(wakeTone) { wakeTone = it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("声纹校验", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        CompactSwitch(requireVp) { requireVp = it }
                    }
                    SmallField(
                        vpThreshold, { vpThreshold = it },
                        "声纹阈值 0.1~0.9（越低越宽松）",
                        KeyboardType.Decimal,
                    )
                    SmallField(
                        idleSleep, { idleSleep = it },
                        "对话结束后多久休眠（秒，0=不休眠）",
                        KeyboardType.Number,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("语音打断", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Hint("播报中说出打断词即可立刻停播并开始听。要求声纹通过——播报时录到的声音里混着喇叭声，阈值自动放宽一半。")
                        CompactSwitch(interruptOn) { interruptOn = it }
                    }
                    if (interruptOn) {
                        SmallField(interruptWords, { interruptWords = it }, "打断词（逗号分隔）")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showEnroll = true }) {
                            Text(
                                if (vm.voiceprintEnrolled) "重新注册" else "注册声纹",
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { vm.clearVoiceprint() }) {
                            Text("清除", fontSize = 12.sp, color = colors.error)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (vm.voiceprintEnrolled) "已注册" else "未注册",
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                // ---------------- 播报 ----------------
                SettingsSection(
                    title = "播报",
                    hint = "MiniMax 音色自然，每句多约 1 秒；系统 TTS 零延迟、离线可用。",
                ) {
                    OptionChips(
                        options = listOf(
                            AgentSettings.TTS_MINIMAX to "MiniMax",
                            AgentSettings.TTS_SYSTEM to "系统",
                            AgentSettings.TTS_OFF to "关闭",
                        ),
                        selected = ttsEngine,
                        onSelect = { ttsEngine = it },
                    )
                    Text(
                        "播报时音乐",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                    )
                    OptionChips(
                        options = listOf(
                            AgentSettings.MUSIC_DUCK to "降低音量",
                            AgentSettings.MUSIC_STOP to "停止音乐",
                        ),
                        selected = musicPolicy,
                        onSelect = { musicPolicy = it },
                    )
                    Hint("AI 播报时，正在播放的音乐怎么处理：降音量（不断歌，播完恢复）或直接停掉。")
                }

                // ---------------- 云端模型 ----------------
                SettingsSection("云端模型") {
                    SmallField(baseUrl, { baseUrl = it }, "Base URL")
                    SmallField(apiKey, { apiKey = it }, "API Key", secret = true)
                    SmallField(model, { model = it }, "模型名")
                    SmallField(maxTurns, { maxTurns = it }, "最大步数", KeyboardType.Number)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("简单指令优先走端侧规则", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        CompactSwitch(preferOnDevice) { preferOnDevice = it }
                    }
                }

                // ---------------- 音乐 ----------------
                SettingsSection(
                    title = "音乐",
                    hint = "未登录只能播 30 秒试听。登录后 cookie 存在本机，不落服务器。",
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (vm.neteaseNick.isNotBlank()) "已登录：${vm.neteaseNick}" else "未登录",
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                            color = if (vm.neteaseNick.isNotBlank()) colors.onSurface
                            else colors.onSurfaceVariant,
                        )
                        if (vm.settings.neteaseCookie.isNotBlank()) {
                            TextButton(onClick = { vm.logoutNetease() }) {
                                Text("退出", fontSize = 12.sp, color = colors.error)
                            }
                        }
                        TextButton(onClick = { vm.startQrLogin() }) {
                            Text(if (vm.settings.neteaseCookie.isBlank()) "扫码登录" else "重新登录", fontSize = 12.sp)
                        }
                    }
                }

                // ---------------- 维护 ----------------
                SettingsSection("维护") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已学技能 ${vm.skillCount} 条", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearSkills() }) {
                            Text("清空", fontSize = 12.sp, color = colors.error)
                        }
                    }
                    SettingsSection(title = "沙箱挂载目录", hint = "SAF 选目录 bind 进容器 /var/minis/mounts/<名字>；添加后热壳自动重启，立即生效") {
                        val mountPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                        ) { uri ->
                            if (uri != null) {
                                val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                                    ?: "mnt"
                                vm.mountSandboxFolder(uri, name)
                            }
                        }
                        vm.sandboxMounts().forEach { (name, path) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$name → $path", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.unmountSandboxFolder(name) }) {
                                    Text("移除", fontSize = 11.sp, color = colors.error)
                                }
                            }
                        }
                        TextButton(onClick = { mountPicker.launch(null) }) {
                            Text("添加挂载目录", fontSize = 12.sp)
                        }
                        SmallField(
                            sandboxIdleMin, { sandboxIdleMin = it },
                            "沙箱空闲回收（分钟，0=不回收）",
                            KeyboardType.Number,
                        )
                        Hint("沙箱进程闲置后会自杀回收（释放约 130MB 内存），下次使用自动重启（1.5 秒）。")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AgentSettings.save(
                    vm.settings.copy(
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        maxTurns = maxTurns.toIntOrNull()?.coerceIn(3, 60) ?: 25,
                        preferOnDevice = preferOnDevice,
                        ttsEngine = ttsEngine,
                        musicPolicy = musicPolicy,
                        sandboxIdleMinutes = sandboxIdleMin.toIntOrNull()?.coerceIn(0, 120) ?: 5,
                        wakeWords = wakeWords.trim().ifEmpty { AgentSettings.DEFAULT_WAKE_WORDS },
                        wakeTone = wakeTone,
                        requireVoiceprint = requireVp,
                        voiceprintThreshold = vpThreshold.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.30f,
                        idleSleepSeconds = idleSleep.toIntOrNull()?.coerceIn(0, 600) ?: 60,
                        interruptEnabled = interruptOn,
                        interruptWords = interruptWords.trim().ifEmpty { "停一下,停下来,不说了,别说了" },
                    )
                )
                vm.refreshStatus()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // 声纹注册引导：独立窗口（dialog 可以叠在设置弹窗上）
    if (showEnroll) {
        VoiceprintEnrollDialog(vm, onDismiss = { showEnroll = false })
    }
}

/**
 * 引导式声纹注册弹窗。
 *
 * 旧流程的坑：点按钮时拿的是环形缓冲里"刚才碰巧说过的那句"，
 * 用户没说话就注册失败，说了话也不知道说的够不够。
 * 现在：明确口令 → VAD 切满 3 句自动合并入库，进度实时显示。
 */
@Composable
private fun VoiceprintEnrollDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {
            if (vm.enrollRunning) vm.cancelEnrollGuide()
            onDismiss()
        },
        title = { Text("注册声纹", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "对着手机把下面这句话清晰念 3 遍（语速正常、别背对麦克风）：",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "「${vm.enrollPhrase}」",
                    fontSize = 14.sp,
                    color = colors.primary,
                )
                when {
                    vm.enrollRunning -> Text(
                        "正在采集… ${vm.enrollProgress}（自动计数，说满 3 句即完成）",
                        fontSize = 12.sp,
                        color = colors.error,
                    )
                    vm.enrollResult == true -> Text(
                        "注册完成，之后唤醒只认这个声音。",
                        fontSize = 12.sp,
                        color = colors.primary,
                    )
                    vm.enrollResult == false -> Text(
                        "注册未完成，可重试。",
                        fontSize = 12.sp,
                        color = colors.error,
                    )
                }
            }
        },
        confirmButton = {
            if (vm.enrollRunning) {
                TextButton(onClick = { vm.cancelEnrollGuide() }) { Text("取消采集", fontSize = 12.sp) }
            } else {
                TextButton(onClick = { vm.startEnrollGuide() }) {
                    Text(if (vm.enrollResult == null) "开始" else "再来一次", fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
            }
        },
    )
}

private fun openAccessibilitySettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
