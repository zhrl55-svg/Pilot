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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.withTimeoutOrNull

import com.pilot.agent.agent.AgentSettings

/** 按住多久算长按（开始录音）。低于系统长按阈值，手感更跟手。 */
private const val LONG_PRESS_MS = 450L

class MainActivity : ComponentActivity() {

    private val vm: AgentViewModel by viewModels()

    private val micPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.startVoiceHold()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PilotTheme {
                AgentScreen(vm, onRequestMic = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) })
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
private fun AgentScreen(vm: AgentViewModel, onRequestMic: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val rootFocusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // ★ 声纹注册弹窗放在这一层：跟设置弹窗同级会互相遮挡，点「开始」看着没反应（实测）。
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(vm.chatLogs.size) {
        if (vm.chatLogs.isNotEmpty()) listState.animateScrollToItem(vm.chatLogs.lastIndex)
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
        )
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .weight(1f)
                // ★ 点对话区（含空白处）→ 输入框取消焦点、键盘收起。
                //   用 clickable 而不是 focusable：不用依赖 isFocused 之类的 API，
                //   行为也更直接（点一下就收键盘）。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    rootFocusManager.clearFocus(force = true)
                    keyboard?.hide()
                },
        ) {
            if (vm.chatLogs.isEmpty()) {
                EmptyHint()
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.chatLogs, key = { it.id }) { entry ->
                        LogRow(
                            entry = entry,
                            onCopy = { text ->
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Pilot", text))
                                android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onAddToChat = { text ->
                                // "添加到对话"：把这段文本放进输入框，用户可以直接改再发
                                input = if (input.isBlank()) text else "$input $text"
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        InputBar(
            value = input,
            onValueChange = { input = it },
            running = vm.running,
            // ★ 只在"正在接收这一句"时才算在听。
            //   麦克风是常驻的（唤醒词一直在跑），状态多半是 WAKE_WAIT/ACTIVE，
            //   之前把 listening 也算进去 → 输入框一直显示"正在听…"（老板反馈的 bug）。
            listening = vm.voiceState == com.pilot.agent.voice.VoiceInput.State.LISTENING ||
                vm.voiceState == com.pilot.agent.voice.VoiceInput.State.RECOGNIZING,
            onSend = {
                vm.run(input)
                input = ""
            },
            justSent = vm.justSent,
            speaking = vm.speaking,
            onStopSpeaking = { vm.stopSpeaking() },
            onStop = { vm.stop() },
            onStartVoice = {
                // 长按说话要先有录音权限；没有就当场申请，授权后立刻开始
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    vm.startVoiceHold()
                } else {
                    onRequestMic()
                }
            },
            onFinishVoice = { vm.finishVoiceHold() },
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
private fun StatusCard(vm: AgentViewModel, onEnable: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (vm.accessibilityOn) colors.primary else colors.error, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                ) { Text("去开启", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * 一条对话。
 *
 * 版式（老板定的）：**用户靠右带气泡、AI 靠左无气泡**。
 * 只渲染三种：用户说的话、AI 的回答、出错提示；
 * 过程类（思考/执行/观察）不显示——那是给人调试看的，不是给用户看的。
 *
 * 长按弹菜单：选择 / 复制 / 添加到对话。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    entry: LogEntry,
    onCopy: (String) -> Unit,
    onAddToChat: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isUser = entry.kind == LogEntry.Kind.USER
    val textColor = if (entry.kind == LogEntry.Kind.ERROR) colors.error else colors.onBackground
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            if (isUser) {
                Surface(
                    color = colors.primary.copy(alpha = 0.20f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp),
                ) {
                    Text(
                        text = entry.text,
                        fontSize = 14.sp,
                        color = textColor,
                        lineHeight = 21.sp,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                Text(
                    text = entry.text,
                    fontSize = 14.sp,
                    color = textColor,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        ),
                )
            }
        }

        // 长按菜单：选择 / 复制 / 添加到对话
        if (menuOpen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { menuOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0x66000000))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                waitForUpOrCancellation()
                                menuOpen = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = colors.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.widthIn(max = 280.dp),
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                entry.text,
                                fontSize = 13.sp,
                                color = colors.onBackground,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            androidx.compose.material3.HorizontalDivider(
                                color = colors.outlineVariant.copy(alpha = 0.4f),
                            )
                            MenuRow("选择") {
                                selected = true
                                menuOpen = false
                            }
                            MenuRow("复制") {
                                onCopy(entry.text)
                                menuOpen = false
                            }
                            MenuRow("添加到对话") {
                                onAddToChat(entry.text)
                                menuOpen = false
                            }
                        }
                    }
                }
            }
        }

        // "选择"后把文本填进输入框
        if (selected) {
            selected = false
            onAddToChat(entry.text)
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 14.sp,
        color = colors.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
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

/**
 * 输入栏。
 *
 * 交互按老板定的来：
 * - **点一下**输入框 → 弹键盘打字；
 * - **长按**输入框 → 开始录音，松手立即发送（识别结果直接执行）；
 * - 录音时输入框有一圈**旋转的彩色描边**，一眼看出在录；
 * - 不再有"说"按钮，发送按钮是个箭头。
 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    listening: Boolean,
    justSent: Boolean,
    speaking: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStopSpeaking: () -> Unit,
    onStartVoice: () -> Unit,
    onFinishVoice: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focusReq = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var recording by remember { mutableStateOf(false) }



    // ★ 不要旋转着画矩形：rotate 会把圆角矩形转出界，看起来是"扭"的。
    //   改成颜色在两个主色之间呼吸，形状稳定、也明显是"在录"。
    val spin = rememberInfiniteTransition(label = "rec")
    val phase by spin.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // ★ 放慢：1600ms 一圈太快，看着像"闪"而不是"在录"。3600ms 慢一点才从容。
            tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )
    // 边框流光只在**长按录音中**时亮。语音层常驻监听状态一直是 LISTENING，
    // 用它判断会让边框一直亮着（等于没有"在录音"的提示作用）。
    val live = recording

    Row(verticalAlignment = Alignment.Bottom) {
        Box(
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // ★ 自己遍历指针事件，不用 waitForUpOrCancellation()：
                        //   TextField 会消费 up 事件，那个函数就返回 null，
                        //   结果"单击"被判成"长按"→ 输入框一直显示"正在听…"（老板反馈）。
                        var longPressed = false
                        val startAt = down.uptimeMillis
                        while (true) {
                            val event = awaitPointerEvent()
                            val up = event.changes.any { it.changedToUpIgnoreConsumed() }
                            if (up) break
                            if (!longPressed && event.changes.firstOrNull()?.uptimeMillis?.let {
                                    it - startAt > LONG_PRESS_MS
                                } == true
                            ) {
                                longPressed = true
                                // 长按 = 录音：先收键盘，否则它会挡住输入框
                                recording = true
                                runCatching { focusManager.clearFocus() }
                                keyboard?.hide()
                                onStartVoice()
                            }
                        }
                        if (longPressed) {
                            onFinishVoice()
                            // 松手即退出"录音中"：边框收掉、文案变回提示。
                            // 识别还在后台跑（onText 触发），但那不是"正在听"。
                            recording = false
                        } else {
                            // 短按 = 文本输入：只聚焦，让键盘弹出来
                            focusReq.requestFocus()
                        }
                    }
                },
        ) {
            if (live) {
                // 一段亮线沿圆角边框滚动（流光），形状稳定、不随角度变形。
                val border = androidx.compose.ui.graphics.lerp(colors.primary, colors.tertiary, phase)
                Canvas(Modifier.matchParentSize().padding(1.dp)) {
                    val r = CornerRadius(16.dp.toPx())
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f, top = 0f,
                                right = size.width, bottom = size.height,
                                cornerRadius = r,
                            ),
                        )
                    }
                    val stroke = 2.5.dp.toPx()
                    // 底：一圈很淡的边，保证任何时刻都看得出"框在这儿"
                    drawPath(path, color = border.copy(alpha = 0.25f), style = Stroke(stroke))
                    // 上：一段占周长约 1/4 的高亮沿框流动
                    val perimeter = 2f * (size.width + size.height)
                    val dash = perimeter * 0.25f
                    drawPath(
                        path = path,
                        color = border,
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(dash, perimeter - dash),
                                -perimeter * phase,
                            ),
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = if (live) "" else value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusReq),
                placeholder = {
                    Text(
                        when {
                            // ★ 只认"长按中"：语音层常驻监听时状态一直是 LISTENING，
                            //   用它判断会导致单击输入框后也显示"正在听…"（老板反馈的 bug）。
                            recording -> "正在听…（松手发送）"
                            else -> "点一下输入，长按说话"
                        },
                        fontSize = 13.sp,
                    )
                },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                colors = if (live) {
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        // ★ 停止按钮的语义：**播报中按它 = 闭嘴**（老板原话"停止的目的是终止播报"）；
        //   任务跑着但没在说话时按它 = 中止任务。
        val showStop = speaking || running || justSent
        Button(
            onClick = when {
                speaking -> onStopSpeaking
                running || justSent -> onStop
                else -> onSend
            },
            enabled = showStop || value.isNotBlank(),
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                // 空闲时按钮底色用浅色（图标是 primary 色，深底会看不清）；
                // 停止态保持红底白块，一眼能认。
                containerColor = if (showStop) colors.error else colors.primary.copy(alpha = 0.12f),
                contentColor = if (showStop) colors.onError else colors.primary,
            ),
        ) {
            Canvas(Modifier.size(20.dp)) {
                if (showStop) {
                    // 停止：实心圆角方块（白块，红底上要看得清）
                    drawRoundRect(
                        color = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                        topLeft = Offset(size.width * 0.26f, size.height * 0.26f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.48f, size.height * 0.48f),
                        cornerRadius = CornerRadius(size.width * 0.08f),
                    )
                } else {
                    // 发送：纸飞机（比"↑"更像发送，也更像"发出去"这个动作）。
                    // 颜色用**输入框聚焦时的边框色**（primary），和输入框有呼应。
                    val p = Path().apply {
                        moveTo(size.width * 0.06f, size.height * 0.46f)
                        lineTo(size.width * 0.94f, size.height * 0.06f)
                        lineTo(size.width * 0.58f, size.height * 0.94f)
                        lineTo(size.width * 0.44f, size.height * 0.58f)
                        close()
                    }
                    drawPath(p, color = androidx.compose.ui.graphics.Color(colors.primary.value))
                }
            }
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
private fun SettingsDialog(
    vm: AgentViewModel,
    onDismiss: () -> Unit,
) {
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
    var chatTurns by remember { mutableStateOf(vm.settings.chatContextTurns.toString()) }
    var interruptOn by remember { mutableStateOf(vm.settings.interruptEnabled) }
    var interruptWords by remember { mutableStateOf(vm.settings.interruptWords) }
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
                    // ★ 不再有"注册声纹"按钮：清除后**第一句话**自动注册。
                    //   原来那套要念 3 遍固定口令，既重又要求口令与唤醒词一致。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (vm.voiceprintEnrolled) {
                                "已注册，只认这个声音"
                            } else {
                                "未注册 —— 说第一句话时自动注册"
                            },
                            fontSize = 12.sp,
                            color = if (vm.voiceprintEnrolled) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (vm.voiceprintEnrolled) {
                            TextButton(onClick = { vm.clearVoiceprint() }) {
                                Text("清除", fontSize = 12.sp, color = colors.error)
                            }
                        }
                    }
                    if (!vm.voiceprintEnrolled) {
                        Text(
                            "清除后，下一次说唤醒词时会把那句话记成你的声纹。",
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                    }
                }

                // ---------------- 聊天 ----------------
                SettingsSection(
                    title = "聊天",
                    hint = "上下文轮数决定模型能记住多少轮对话。0=每句独立（记不住前后文）。",
                ) {
                    SmallField(
                        chatTurns, { chatTurns = it },
                        "上下文轮数（0~20）",
                        KeyboardType.Number,
                    )
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
                        chatContextTurns = chatTurns.toIntOrNull()?.coerceIn(0, 20) ?: 4,
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

}

private fun openAccessibilitySettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
