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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

import com.pilot.agent.Dbg
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
    // 用 TextFieldValue 而不是 String：才能把光标放到句末
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var showSettings by remember { mutableStateOf(false) }
    // 当前哪条消息处于文字选择态；点对话区空白处就清空（放弃选择）
    var selectingEntryId by remember { mutableStateOf<Long?>(null) }
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

        // ★ 音乐播放条：在聊天区上部显示歌名 + 暂停/继续 + 停止
        vm.nowPlaying?.let { title ->
            MusicBar(
                title = title,
                paused = vm.musicPaused,
                onToggle = { vm.toggleMusic() },
                onStop = { vm.stopMusic() },
            )
            Spacer(Modifier.height(12.dp))
        }

        Box(
            Modifier
                .weight(1f)
                // ★ 点对话区（含空白处）→ 输入框取消焦点、键盘收起。
                //   用 clickable 而不是 focusable：不用依赖 isFocused 之类的 API，
                //   行为也更直接（点一下就收键盘）。
                .pointerInput(Unit) {
                    // ★ 用 detectTapGestures 而不是 clickable：
                    //   clickable 在长按松手时也会判成 click → 刚选中的文字立刻被清掉。
                    //   detectTapGestures 的 onTap 只在真正短按时触发。
                    detectTapGestures(onTap = {
                        rootFocusManager.clearFocus(force = true)
                        keyboard?.hide()
                        selectingEntryId = null      // 单击其他地方 = 放弃选择
                    })
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
                            selectionActive = selectingEntryId == entry.id,
                            onSelectionChange = { active ->
                                selectingEntryId = if (active) entry.id else null
                            },
                            onCopy = { text ->
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Pilot", text))
                                android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onAddToChat = { text ->
                                // ★ 添加到输入框后光标自动到句末（用户能接着改/接着说）
                                val t = if (input.text.isBlank()) text else input.text + " " + text
                                input = TextFieldValue(t, TextRange(t.length))
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
            sessionListening = run {
                val st = vm.voiceState
                st == com.pilot.agent.voice.VoiceInput.State.ACTIVE ||
                    st == com.pilot.agent.voice.VoiceInput.State.LISTENING ||
                    st == com.pilot.agent.voice.VoiceInput.State.RECOGNIZING
            },
            onSend = {
                vm.run(input.text)
                input = TextFieldValue("")
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
                    false
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

    // 技能列表放这一层：嵌在设置弹窗里会被遮住（跟声纹注册同一个坑）
    if (vm.showSkillList) {
        SkillListDialog(vm, onDismiss = { vm.closeSkillList() })
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
        // ★ 老板要求：标题左侧不要圆点
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

/** 音乐播放条：歌名 + 暂停/继续 + 停止。 */
@Composable
private fun MusicBar(
    title: String,
    paused: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.secondaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                "♪",
                fontSize = 15.sp,
                color = colors.onSecondaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = 13.sp,
                color = colors.onSecondaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggle) {
                Text(if (paused) "继续" else "暂停", fontSize = 12.sp)
            }
            TextButton(onClick = onStop) {
                Text("停止", fontSize = 12.sp, color = colors.error)
            }
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
 * 长按 → **在原文上**做选择（参考系统文字选择）：
 * - 文字按分隔符切块，长按命中哪块就选中哪块（蓝色高亮画在原文上）
 * - 选中范围两端画竖线 + 圆点手柄，**两根手柄都能反复拖动**扩缩范围
 * - 半透明菜单**浮在文字上方**（用 Box 叠层 + 负 offset，不占布局高度）
 * - 点对话区其他地方 → 放弃选择
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    entry: LogEntry,
    selectionActive: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onAddToChat: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isUser = entry.kind == LogEntry.Kind.USER
    val textColor = if (entry.kind == LogEntry.Kind.ERROR) colors.error else colors.onBackground

    val chunks = remember(entry.text) { splitBySeparator(entry.text) }
    val ranges = remember(chunks) {
        var acc = 0
        chunks.map { part ->
            val r = acc until (acc + part.length)
            acc += part.length
            r
        }
    }
    var selStart by remember { mutableStateOf<Int?>(null) }
    var selEnd by remember { mutableStateOf<Int?>(null) }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    LaunchedEffect(selectionActive) {
        if (!selectionActive) { selStart = null; selEnd = null }
    }

    val selRange: IntRange? = if (selStart != null && selEnd != null) {
        val a = ranges.getOrNull(selStart!!)?.first ?: 0
        val b = (ranges.getOrNull(selEnd!!)?.last ?: (entry.text.length - 1)) + 1
        a until b.coerceAtMost(entry.text.length)
    } else null
    val selectedText = selRange?.let { entry.text.substring(it.first, it.last) }?.trim().orEmpty()

    // ★ 手势协程用 rememberUpdatedState 读最新选择范围：
    //   直接捕获 selRange 会在重组后一直读到旧快照 → 手柄"只能拖一次，下次不动"（实测）
    val rangeNow = rememberUpdatedState(selRange)
    val layoutNow = rememberUpdatedState(layout)

    fun chunkAt(offset: androidx.compose.ui.geometry.Offset): Int? {
        val l = layoutNow.value ?: return null
        if (entry.text.isEmpty()) return null
        val charOffset = l.getOffsetForPosition(offset).coerceIn(0, entry.text.length - 1)
        return ranges.indexOfFirst { charOffset in it }.takeIf { it >= 0 }
    }

    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(entry.text)
        if (selRange != null && selRange.first < entry.text.length) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = androidx.compose.ui.graphics.Color(0xFF2B5FA8),
                    color = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                ),
                selRange.first, selRange.last + 1,
            )
        }
    }

    Box(Modifier.fillMaxWidth()) {
        val bubbleShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
        val bodyModifier = Modifier
            .widthIn(max = if (isUser) 280.dp else 300.dp)
            .let { m ->
                if (isUser) {
                    m.background(colors.primary.copy(alpha = 0.20f), bubbleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                } else m
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 判断按下的是哪根手柄
                    var handle: Int? = null
                    val r = rangeNow.value
                    val l = layoutNow.value
                    if (r != null && l != null && entry.text.isNotEmpty()) {
                        val a = l.getBoundingBox(r.first)
                        val b = l.getBoundingBox(r.last.coerceAtMost(entry.text.length - 1))
                        val radius = 30.dp.toPx()
                        val dl = (down.position - Offset(a.left, a.bottom)).getDistance()
                        val dr = (down.position - Offset(b.right, b.bottom)).getDistance()
                        if (minOf(dl, dr) < radius) handle = if (dl <= dr) 0 else 1
                    }
                    if (handle != null) {
                        // ★ 拖动手柄：每帧都读最新范围，拖多少次都有效
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pos = ev.changes.firstOrNull()?.position
                            if (pos != null) {
                                val c = chunkAt(pos)
                                if (c != null) {
                                    val s0 = selStart ?: c
                                    val e0 = selEnd ?: c
                                    if (handle == 0) {
                                        selStart = minOf(c, e0); selEnd = e0
                                    } else {
                                        selEnd = maxOf(c, s0); selStart = s0
                                    }
                                }
                            }
                            if (ev.changes.any { it.changedToUpIgnoreConsumed() }) break
                        }
                        onSelectionChange(true)
                    } else {
                        // 单击 / 长按（靠超时唤醒，按住不动不会有新事件）
                        val tapped = withTimeoutOrNull(450L) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.any { it.changedToUpIgnoreConsumed() }) break
                            }
                            true
                        }
                        if (tapped != null) {
                            Dbg.i("PilotSel", "短按 selStart=$selStart")
                            if (selStart != null) {
                                chunkAt(down.position)?.let { c -> selStart = c; selEnd = c }
                                onSelectionChange(true)
                            }
                        } else {
                            // 落在文字边缘/空白处时兜底选第一块，别让长按"没反应"
                            val c = chunkAt(down.position) ?: 0
                            Dbg.i("PilotSel", "长按命中 chunk=$c text=${entry.text.take(12)}")
                            selStart = c; selEnd = c
                            onSelectionChange(true)
                        }
                    }
                }
            }

        Box(Modifier.align(if (isUser) Alignment.TopEnd else Alignment.TopStart)) {
            Text(
                text = annotated,
                fontSize = 14.sp,
                color = textColor,
                lineHeight = 21.sp,
                onTextLayout = { layout = it },
                modifier = bodyModifier,
            )
            if (selRange != null && layout != null) {
                Canvas(Modifier.matchParentSize()) {
                    val l = layoutNow.value ?: return@Canvas
                    val a = l.getBoundingBox(selRange.first)
                    val b = l.getBoundingBox(selRange.last.coerceAtMost(entry.text.length - 1))
                    val hc = androidx.compose.ui.graphics.Color(0xFF4A90E2)
                    val w = 2.dp.toPx()
                    drawLine(hc, Offset(a.left, a.top), Offset(a.left, a.bottom), w)
                    drawLine(hc, Offset(b.right, b.top), Offset(b.right, b.bottom), w)
                    val r = 5.dp.toPx()
                    drawCircle(hc, r, Offset(a.left, a.bottom + r))
                    drawCircle(hc, r, Offset(b.right, b.bottom + r))
                }
            }
        }

        // ★ 半透明菜单浮在文字上方。
        //   ★ 关键：外面套一个 size(0) 的 Box —— 否则菜单虽然用负 offset 画到文字上方，
        //   它**仍然参与父 Box 的测量**，下面的文字会被顶下去（用户反馈"复制时下面下移"）。
        if (selRange != null) {
            Box(
                Modifier
                    .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
                    .size(0.dp)
                    .zIndex(2f),
            ) {
            Surface(
                color = androidx.compose.ui.graphics.Color(0x802A2A2A),   // 50% 透明
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .offset(y = (-44).dp)
                    .widthIn(max = 330.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    MenuChip("复制") { onCopy(selectedText); selStart = null; selEnd = null; onSelectionChange(false) }
                    MenuChip("添加到输入框") { onAddToChat(selectedText); selStart = null; selEnd = null; onSelectionChange(false) }
                    MenuChip("全选") { selStart = 0; selEnd = ranges.lastIndex; onSelectionChange(true) }
                }
            }
            }
        }
    }
}

/** 按分隔符切块，保留末尾标点（中文标点 / 问叹分冒号 / 空白）。 */
private fun splitBySeparator(text: String): List<String> =
    text.split(Regex("(?<=[，。！？；：、!?;:\\s])"))
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(text) }

@Composable
private fun MenuChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = androidx.compose.ui.graphics.Color(0xFFEEEEEE),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
 * ## 结构（重要，别再改回"手势放父 Box 上"）
 * ```
 * Box
 *  ├─ OutlinedTextField        ← 永远不自己处理触摸
 *  ├─ 流光边框 Canvas          ← 录音时
 *  └─ 透明手势罩层（仅未进入文本输入态时存在）← 所有点击都先到这
 * ```
 *
 * 之前把手势放在父 Box 上，和 TextField 自己的触摸处理抢同一个 down/up，
 * 结果：单击被判成长按、长按弹键盘、输入框一直抢焦点……
 * 打了 6 层补丁（clearFocus / hide / readOnly / focusProperties /
 * showKeyboardOnFocus / handler.postDelayed）互相打架，全删了。
 *
 * 现在：**文本框在"未聚焦"时完全收不到触摸**，焦点只由这里手动控制：
 * - 单击 → 撤掉罩层 + requestFocus + 弹键盘（文本输入态）
 * - 长按（按住 ≥450ms）→ 录音，从不聚焦 → 键盘不会弹
 * - 点外部 → 失焦 → 罩层回来
 *
 * ★ 长按判定用 withTimeoutOrNull 等 up：**按住不动时不会有新指针事件**，
 *   靠超时主动唤醒；用 while(awaitPointerEvent) 计时永远不会触发（踩过）。
 */
@Composable
private fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    running: Boolean,
    justSent: Boolean,
    speaking: Boolean,
    /** 会话已激活、麦克风正在收这一句（唤醒/长按后的连续对话） */
    sessionListening: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStopSpeaking: () -> Unit,
    onStartVoice: () -> Boolean,
    onFinishVoice: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focusReq = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var recording by remember { mutableStateOf(false) }
    // 是否已进入"文本输入态"（聚焦中）。false 时罩层挡着，TextField 收不到触摸。
    var textMode by remember { mutableStateOf(false) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // 进入文本态后主动聚焦并弹键盘（requestFocus 后等一帧让重组生效）
    LaunchedEffect(textMode) {
        if (textMode) {
            delay(50)
            focusReq.requestFocus()
            keyboard?.show()
        }
    }

    // 兜底：万一手势协程整个被取消，recording 也要在 16 秒内归位
    LaunchedEffect(recording) {
        if (recording) {
            delay(16_000)
            recording = false
        }
    }

    val spin = rememberInfiniteTransition(label = "rec")
    val phase by spin.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3600, easing = LinearEasing),   // 慢一点，像"在录"而不是"在闪"
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )

    // ★ 老板要求：语音唤醒或长按激活后进入连续对话，只要麦克风还在收，
    //   输入框就要显示「正在听…」+ 流光边框。
    //   但排除 WAKE_WAIT/ASLEEP（常驻待命也在开麦，那不算"在听"）——
    //   这正是之前"输入框一直显示正在听"的坑，靠 sessionListening 区分。
    val live = recording || sessionListening

    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,                    // ★ 录音时不清空用户已输入的字
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusReq)
                    .onFocusChanged { state ->
                        // 失焦就退回非文本态（罩层回来），下次单击重新判定
                        if (!state.isFocused && textMode) textMode = false
                    },
                placeholder = { Text("点一下输入，长按说话", fontSize = 13.sp) },
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

            if (live) {
                // 在听时：一层淡底 + "正在听…"，盖在输入框上但看得见边框
                Box(
                    Modifier
                        .matchParentSize()
                        .background(colors.surface.copy(alpha = 0.88f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (recording) "正在听…（松手发送）" else "正在听…",
                        fontSize = 13.sp,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
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
                    drawPath(path, color = border.copy(alpha = 0.25f), style = Stroke(stroke))
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

            // ★ 只在非文本输入态盖罩层：TextField 完全收不到触摸，
            //   不会自己抢焦点、不会自己弹键盘。
            // ★ 录音时罩层**不能撤**：撤了 pointerInput 协程会被取消，
            //   松手事件收不到、onFinishVoice 也调不到（语音层会永远卡在 LISTENING）。
            //   罩层是透明的，盖在最上面不影响"正在听…"和流光边框显示。
            if (!textMode) {
                Box(
                    Modifier.matchParentSize().pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            // 等 up，最多 450ms。超时 = 长按（按住不动没有新事件，
                            // 靠 timeout 主动唤醒，不能靠轮询事件时间戳）。
                            val tapped = withTimeoutOrNull(LONG_PRESS_MS) {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.any { it.changedToUpIgnoreConsumed() }) break
                                }
                                true
                            }
                            if (tapped != null) {
                                // 单击 → 文本输入
                                textMode = true
                            } else {
                                // 长按 → 录音，直到松手。
                                // 启动失败（上一句还在识别）就别显示"正在听"，直接跳过。
                                val started = onStartVoice()
                                if (started) {
                                    recording = true
                                    // ★ 手势协程可能被取消（重组/切后台），
                                    //   finally 保证"正在听"一定会收掉，不会一直转。
                                    //   再加一道 15 秒超时：手指一直按着也不无限录。
                                    try {
                                        withTimeoutOrNull(15_000L) {
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                if (ev.changes.any { it.changedToUpIgnoreConsumed() }) break
                                            }
                                        }
                                    } finally {
                                        // ★ 无论松手、超时还是协程被取消，都必须通知语音层收状态
                                        handler.post { onFinishVoice() }
                                        recording = false
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        val showStop = speaking || running || justSent
        Button(
            onClick = when {
                speaking -> onStopSpeaking       // 播报中：闭嘴 + 停任务
                running || justSent -> onStop    // 任务中：中止任务
                else -> onSend
            },
            enabled = showStop || value.text.isNotBlank(),
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (showStop) {
                    androidx.compose.ui.graphics.Color(0xFFE53935)   // 明确的正红
                } else {
                    colors.primary.copy(alpha = 0.12f)
                },
                contentColor = if (showStop) {
                    androidx.compose.ui.graphics.Color(0xFFFFFFFF)
                } else {
                    colors.primary
                },
            ),
        ) {
            Canvas(Modifier.size(20.dp)) {
                if (showStop) {
                    drawRoundRect(
                        color = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                        topLeft = Offset(size.width * 0.26f, size.height * 0.26f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.48f, size.height * 0.48f),
                        cornerRadius = CornerRadius(size.width * 0.08f),
                    )
                } else {
                    // 纸飞机，颜色 = 输入框聚焦时的边框色（primary）
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
                        Text(
                            "已学技能 ${vm.skillCount} 条（点击查看）",
                            fontSize = 12.sp,
                            color = colors.primary,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { vm.openSkillList() }
                                .padding(vertical = 6.dp),
                        )
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

/** 已学技能列表。 */
@Composable
private fun SkillListDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已学技能 ${vm.skillList.size} 条", fontSize = 16.sp) },
        text = {
            if (vm.skillList.isEmpty()) {
                Text("还没有学到技能。成功执行过的操作会自动记下来。", fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.skillList, key = { it.id }) { skill ->
                        Surface(
                            color = colors.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(skill.goal, fontSize = 13.sp, color = colors.onBackground)
                                Text(
                                    buildString {
                                        if (skill.appPkg.isNotBlank()) append(skill.appPkg)
                                        append(" · ${skill.steps.size} 步 · 成功 ${skill.successes} 次")
                                    },
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceVariant,
                                )
                                if (skill.steps.isNotEmpty()) {
                                    Text(
                                        skill.steps.joinToString(" → ") { it.label.ifBlank { it.action } },
                                        fontSize = 11.sp,
                                        color = colors.onSurfaceVariant,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
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
