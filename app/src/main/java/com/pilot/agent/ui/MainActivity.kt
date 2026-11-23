package com.pilot.agent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.Crossfade
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.browser.BrowserActivity
import com.pilot.agent.chat.ChatSessionStore

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
        PilotStatusOverlay.onAppForeground(this)
        vm.refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && vm.shouldShowStatusOverlay()) {
            PilotStatusOverlay.show(this, vm.statusForOverlay())
        } else {
            PilotStatusOverlay.hide(this)
        }
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
    var showClearChatConfirm by remember { mutableStateOf(false) }
    // 当前哪条消息处于文字选择态；点对话区空白处就清空（放弃选择）
    var selectingEntryId by remember { mutableStateOf<Long?>(null) }
    // ★ 当前是否有 LogRow 正在处理手势、命中的是哪一条。
    //   父级的 onTap 也会收到行内点击的抬起事件（Compose 的 tap 检测
    //   在 up 上不检查消费标记），所以靠这两个值判断"点在选中行上"还是"点空白"。
    var rowGestureActive by remember { mutableStateOf(false) }
    var rowGestureId by remember { mutableStateOf<Long?>(null) }
    // ★ 最近一次长按的时间戳。松手时父级也会收到一次 tap，
    //   用它区分"用户点了别处" vs "刚长按完松手"，后者不能清选择。
    var lastLongPressAt by remember { mutableStateOf(0L) }
    val selectionHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
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
            onSettings = { selectingEntryId = null; showSettings = true },
            onClearChat = { selectingEntryId = null; showClearChatConfirm = true },
            onTerminalOpen = {
                runCatching {
                    ctx.startActivity(Intent(ctx, TerminalActivity::class.java))
                }.onFailure {
                    android.widget.Toast.makeText(
                        ctx,
                        "无法打开终端：${it.message.orEmpty()}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onBrowserOpen = {
                runCatching {
                    ctx.startActivity(Intent(ctx, BrowserActivity::class.java))
                }.onFailure {
                    android.widget.Toast.makeText(
                        ctx,
                        "无法打开浏览器：${it.message.orEmpty()}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onScheduledTasksOpen = { vm.openScheduledTasks() },
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
        AssistantStatusBar(
            model = vm.settings.model,
            reasoningEffort = vm.settings.reasoningEffort,
            sessions = vm.sessions,
            currentSessionId = vm.currentSessionId,
            busy = vm.running || vm.justSent,
            onReasoningSelect = { vm.selectReasoningEffort(it) },
            onOpenSession = { vm.openSession(it) },
            onNewSession = { vm.newSession() },
        )
        Spacer(Modifier.height(12.dp))
        StatusCard(
            vm,
            onEnable = { openAccessibilitySettings(ctx) },
        )
        Spacer(Modifier.height(12.dp))

        // ★ 音乐播放条：在聊天区上部显示歌名 + 暂停/继续 + 停止
        vm.nowPlaying?.let { title ->
            MusicBar(vm)
            Spacer(Modifier.height(8.dp))
        }

        Box(
            Modifier
                .weight(1f)
                // ★ 点对话区（含空白处）→ 输入框取消焦点、键盘收起。
                //   用 clickable 而不是 focusable：不用依赖 isFocused 之类的 API，
                //   行为也更直接（点一下就收键盘）。
                .pointerInput(Unit) {
                    // ★ 点在 LogRow 之外 → 收键盘 + 取消文字选择。
                    //   行内手势会先置 rowGestureActive，所以点在选中的那条
                    //   消息上时不清；点其他消息（id 不同）或空白都会清。
                    detectTapGestures(onTap = {
                        rootFocusManager.clearFocus(force = true)
                        keyboard?.hide()
                        val keep = rowGestureActive && rowGestureId == selectingEntryId
                        if (!keep) selectingEntryId = null
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
                            onLongPress = { lastLongPressAt = SystemClock.uptimeMillis() },
                            onRowGesture = { id, active ->
                                if (active) {
                                    rowGestureId = id
                                    rowGestureActive = true
                                } else {
                                    rowGestureActive = false
                                }
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
            // 只在"正在采这一句"时显示录音动画；识别中(RECOGNIZING)和
            // 唤醒后的活跃期(ACTIVE)都不显示——松手后动画必须立刻停。
            sessionListening = run {
                val st = vm.voiceState
                !vm.dndRequested && st == com.pilot.agent.voice.VoiceInput.State.LISTENING
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
            onInputFocused = { selectingEntryId = null },
        )
    }

    // 打开设置时刷新系统音色和本机工具状态。
    LaunchedEffect(showSettings) {
        vm.loadSystemVoices()
        if (showSettings) vm.refreshLocalToolState()
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

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text("清空聊天记录？") },
            text = { Text("当前对话和用于上下文的聊天记录都会清除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearChat()
                    selectingEntryId = null
                    showClearChatConfirm = false
                }) { Text("清空", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showSettings) {
        PilotSettingsDialog(vm, onDismiss = { showSettings = false })
    }

    // 技能列表放这一层：嵌在设置弹窗里会被遮住（跟声纹注册同一个坑）
    if (vm.showSkillList) {
        SkillListDialog(vm, onDismiss = { vm.closeSkillList() })
    }

    if (vm.showMemoryList) {
        MemoryListDialog(vm, onDismiss = { vm.closeMemoryList() })
    }

    if (vm.showScheduledTasks) {
        ScheduledTaskDialog(vm, onDismiss = { vm.closeScheduledTasks() })
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
private fun AssistantStatusBar(
    model: String,
    reasoningEffort: String,
    sessions: List<ChatSessionStore.Session>,
    currentSessionId: String?,
    busy: Boolean,
    onReasoningSelect: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
    ) {
        // 现在已经自动选择路由（快路径有规则就本机执行，没有就交给 Agent），
        // 所以不再显示助理 / Agent 开关；左侧留给最近会话，
        // 右侧显示当前模型和思考强度。
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.42f))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionMenuButton(
                sessions = sessions,
                currentSessionId = currentSessionId,
                enabled = !busy,
                onOpenSession = onOpenSession,
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(15.dp)
                    .background(colors.outline.copy(alpha = 0.35f)),
            )
            NewSessionButton(enabled = !busy, onClick = onNewSession)
        }

        ModelReasoningChips(
            model = model,
            reasoningEffort = reasoningEffort,
            onReasoningSelect = onReasoningSelect,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** 顶栏右侧：当前模型 + 思考强度（点强度可改）。 */
@Composable
private fun ModelReasoningChips(
    model: String,
    reasoningEffort: String,
    onReasoningSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var reasoningExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("模型", fontSize = 9.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(5.dp))
        Text(
            model.ifBlank { "未配置" },
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .width(1.dp)
                .height(15.dp)
                .background(colors.outline.copy(alpha = 0.35f)),
        )
        Box {
            Row(
                modifier = Modifier.clickable { reasoningExpanded = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("思考", fontSize = 9.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    "${reasoningEffortLabel(reasoningEffort)} ▾",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary.copy(alpha = 0.90f),
                )
            }
            DropdownMenu(
                expanded = reasoningExpanded,
                onDismissRequest = { reasoningExpanded = false },
                modifier = Modifier.width(132.dp),
            ) {
                Text(
                    "思考强度",
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                listOf(
                    "low" to "低",
                    "medium" to "中",
                    "high" to "高",
                    "xhigh" to "极高",
                    "max" to "最高",
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(label, fontSize = 12.sp)
                                if (value == reasoningEffort) {
                                    Text("✓", fontSize = 12.sp, color = colors.primary)
                                }
                            }
                        },
                        onClick = {
                            onReasoningSelect(value)
                            reasoningExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * 「最近会话」入口：三道横线（☰）按钮 + 折叠菜单。
 *
 * 菜单里按更新时间倒序列出最近会话，点一条就回放那一段对话；
 * 当前会话会标出来，避免切错。
 */
@Composable
private fun SessionMenuButton(
    sessions: List<ChatSessionStore.Session>,
    currentSessionId: String?,
    enabled: Boolean,
    onOpenSession: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(enabled = enabled) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp, 10.dp)) {
                val tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f)
                val stroke = 1.5.dp.toPx()
                listOf(0.10f, 0.50f, 0.90f).forEach { fraction ->
                    drawLine(
                        color = tint,
                        start = Offset(0f, size.height * fraction),
                        end = Offset(size.width, size.height * fraction),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(248.dp),
        ) {
            Text(
                "最近会话",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    "还没有会话，发出第一条消息会自动保存",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                sessions.forEach { session ->
                    val current = session.id == currentSessionId
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        session.title,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (current) colors.primary else colors.onSurface,
                                    )
                                    Text(
                                        sessionTimeLabel(session.updatedAt),
                                        fontSize = 10.sp,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                if (current) {
                                    Text("当前", fontSize = 10.sp, color = colors.primary)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onOpenSession(session.id)
                        },
                    )
                }
            }
        }
    }
}

/** 新建会话：一枚和「☰」同组的加号按钮。 */
@Composable
private fun NewSessionButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f)
            val stroke = 1.5.dp.toPx()
            drawLine(
                color = tint,
                start = Offset(center.x, 0f),
                end = Offset(center.x, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 会话列表里的相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / M月d日。 */
private fun sessionTimeLabel(at: Long): String {
    val diff = (System.currentTimeMillis() - at).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
            .format(java.util.Date(at))
    }
}

private fun reasoningEffortLabel(value: String): String = when (value) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最高"
    else -> "中"
}

@Composable
private fun Header(
    vm: AgentViewModel,
    onSettings: () -> Unit,
    onClearChat: () -> Unit,
    onTerminalOpen: () -> Unit,
    onBrowserOpen: () -> Unit,
    onScheduledTasksOpen: () -> Unit,
    onNavOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }
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
            TextButton(
                onClick = onClearChat,
                enabled = vm.chatLogs.isNotEmpty() && !vm.running && !vm.justSent,
            ) { Text("清屏", fontSize = 13.sp) }
            Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("打开终端") },
                        onClick = {
                            menuExpanded = false
                            onTerminalOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("定时任务") },
                        onClick = {
                            menuExpanded = false
                            onScheduledTasksOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("打开浏览器") },
                        onClick = {
                            menuExpanded = false
                            onBrowserOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            menuExpanded = false
                            onSettings()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 动态音柱：几根竖条随机跳动，模拟音频波形。
 *
 * 播放中跳动、暂停时静止——一眼能看出"在放"还是"停着"。
 */
@Composable
private fun PlayingBars(playing: Boolean, tint: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "bars")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "t",
    )
    Canvas(Modifier.size(width = 18.dp, height = 16.dp)) {
        val n = 4
        val gap = size.width / n
        val barW = gap * 0.45f
        for (i in 0 until n) {
            // 每根柱子用不同相位，避免整齐跳动
            val phase = (t + i * 0.23f) % 1f
            val h = if (playing) {
                (0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(phase * Math.PI * 2).toFloat()))
            } else {
                0.28f
            }
            val barH = size.height * h
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(i * gap + (gap - barW) / 2, size.height - barH),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2),
            )
        }
    }
}

/** 紧凑播放器：用一行同步歌词和一行控制键尽量节省纵向空间。 */
@Composable
private fun MusicBar(vm: AgentViewModel) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var showRecommendations by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val playing = !vm.musicPaused
    val progress = if (vm.musicDurationMs > 0L) {
        (vm.musicPositionMs.toFloat() / vm.musicDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val repeatLabel = when (vm.musicPlaybackMode) {
        com.pilot.agent.music.MusicPlayer.PlaybackMode.SEQUENCE -> "顺序"
        com.pilot.agent.music.MusicPlayer.PlaybackMode.REPEAT_ALL -> "列表循环"
        com.pilot.agent.music.MusicPlayer.PlaybackMode.REPEAT_ONE -> "单曲循环"
    }
    val lyric = vm.musicLyric.ifBlank {
        when {
            !vm.musicLyricsReady -> "正在加载歌词…"
            vm.musicPositionMs < 1_000L -> "正在准备播放…"
            else -> "♪ 前奏"
        }
    }
    val trackLine = buildString {
        append(vm.nowPlaying.orEmpty())
        if (vm.musicArtist.isNotBlank()) append("  ·  ${vm.musicArtist}")
    }
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.28f),
                        colors.surfaceVariant.copy(alpha = 0.92f),
                        colors.surface.copy(alpha = 0.98f),
                    ),
                ),
            )
            .border(1.dp, colors.primary.copy(alpha = 0.20f), cardShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(
            modifier = Modifier.height(21.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayingBars(playing = playing, tint = colors.primary)
            Spacer(Modifier.width(7.dp))
            Text(
                trackLine,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Box {
                Row(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            colors.primary.copy(alpha = 0.22f),
                            RoundedCornerShape(50),
                        )
                        .clickable {
                            showQueue = false
                            showRecommendations = true
                        }
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "推荐",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "▾",
                        fontSize = 8.sp,
                        color = colors.primary.copy(alpha = 0.80f),
                    )
                }
                DropdownMenu(
                    expanded = showRecommendations,
                    onDismissRequest = { showRecommendations = false },
                ) {
                    MusicListMenuItem("最近播放", "播放最近", vm) { showRecommendations = false }
                    MusicListMenuItem("热歌榜", "播放热门歌曲", vm) { showRecommendations = false }
                    MusicListMenuItem("本地音乐", "播放本地音乐", vm) { showRecommendations = false }
                    MusicListMenuItem("每日推荐", "播放每日推荐", vm) { showRecommendations = false }
                    MusicListMenuItem("我喜欢的", "播放我喜欢的音乐", vm) { showRecommendations = false }
                    MusicListMenuItem("新歌榜", "播放新歌", vm) { showRecommendations = false }
                    MusicListMenuItem("飙升榜", "播放飙升榜", vm) { showRecommendations = false }
                }
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable { vm.stopMusic() },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 20.sp, color = colors.onSurfaceVariant)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(27.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = if (playing) 1f else 0.45f)),
                )
                Spacer(Modifier.width(8.dp))
                Crossfade(
                    targetState = lyric,
                    animationSpec = tween(durationMillis = 220),
                    label = "music-lyric",
                    modifier = Modifier.weight(1f),
                ) { line ->
                    Text(
                        line,
                        fontSize = if (vm.musicLyric.isNotBlank()) 15.sp else 12.sp,
                        fontWeight = if (vm.musicLyric.isNotBlank()) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        color = if (vm.musicLyric.isNotBlank()) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    "${formatMusicTime(vm.musicPositionMs)} / ${formatMusicTime(vm.musicDurationMs)}",
                    fontSize = 9.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MusicModeChip(
                text = "随机",
                active = vm.musicShuffle,
                onClick = { vm.toggleMusicShuffle() },
            )
            MusicSkipButton(next = false, onClick = { vm.previousMusic() })
            MusicPlayButton(playing = playing, onClick = { vm.toggleMusic() })
            MusicSkipButton(next = true, onClick = { vm.nextMusic() })
            MusicModeChip(
                text = repeatLabel,
                // 随机是左侧主状态；开启随机时右侧播放模式只作为待恢复设置，
                // 不能同时高亮，否则用户会误以为两套模式正在叠加生效。
                active = !vm.musicShuffle,
                onClick = {
                    val turningOffShuffle = vm.musicShuffle
                    if (turningOffShuffle) {
                        vm.toggleMusicShuffle()
                    } else {
                        vm.cycleMusicPlaybackMode()
                    }
                    val message = buildString {
                        if (turningOffShuffle) append("已关闭随机 · ")
                        append(when (vm.musicPlaybackMode) {
                            com.pilot.agent.music.MusicPlayer.PlaybackMode.SEQUENCE -> "顺序播放"
                            com.pilot.agent.music.MusicPlayer.PlaybackMode.REPEAT_ALL -> "列表循环"
                            com.pilot.agent.music.MusicPlayer.PlaybackMode.REPEAT_ONE -> "单曲循环"
                        })
                    }
                    android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
                },
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            colors.primary.copy(alpha = 0.22f),
                            RoundedCornerShape(50),
                        )
                        .clickable {
                            showRecommendations = false
                            showQueue = true
                        }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "候选 ${vm.musicTrackIndex}/${vm.musicQueueSize}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "▾",
                        fontSize = 8.sp,
                        color = colors.primary.copy(alpha = 0.75f),
                    )
                }
                DropdownMenu(
                    expanded = showQueue,
                    onDismissRequest = { showQueue = false },
                ) {
                    vm.musicQueue.forEachIndexed { index, entry ->
                        MusicQueueMenuItem(
                            index = index,
                            title = entry.title,
                            artist = entry.artist,
                            active = index + 1 == vm.musicTrackIndex,
                            vm = vm,
                        ) { showQueue = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicListMenuItem(
    label: String,
    command: String,
    vm: AgentViewModel,
    onSelected: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.width(112.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.72f)),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    label,
                    fontSize = 13.sp,
                    color = colors.onSurface,
                )
            }
        },
        onClick = {
            onSelected()
            vm.run(command)
        },
    )
}

@Composable
private fun MusicQueueMenuItem(
    index: Int,
    title: String,
    artist: String,
    active: Boolean,
    vm: AgentViewModel,
    onSelected: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.width(218.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%02d".format(index + 1),
                    modifier = Modifier.width(24.dp),
                    fontSize = 10.sp,
                    color = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.72f),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.primary else colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            artist,
                            fontSize = 10.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                }
            }
        },
        onClick = {
            onSelected()
            vm.playMusicAt(index)
        },
    )
}

@Composable
private fun MusicModeChip(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) colors.primary.copy(alpha = 0.20f) else colors.onSurface.copy(alpha = 0.06f))
            .border(
                1.dp,
                if (active) colors.primary.copy(alpha = 0.55f) else colors.onSurface.copy(alpha = 0.10f),
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun MusicPlayButton(playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            if (playing) {
                val barWidth = size.width * 0.24f
                val barHeight = size.height * 0.72f
                val top = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.22f, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.54f, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            } else {
                val path = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.16f)
                    lineTo(size.width * 0.84f, size.height * 0.5f)
                    lineTo(size.width * 0.28f, size.height * 0.84f)
                    close()
                }
                drawPath(path, Color.White)
            }
        }
    }
}

@Composable
private fun MusicSkipButton(next: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 15.dp, height = 17.dp)) {
            val path = Path().apply {
                if (next) {
                    moveTo(size.width * 0.12f, size.height * 0.15f)
                    lineTo(size.width * 0.68f, size.height * 0.5f)
                    lineTo(size.width * 0.12f, size.height * 0.85f)
                } else {
                    moveTo(size.width * 0.88f, size.height * 0.15f)
                    lineTo(size.width * 0.32f, size.height * 0.5f)
                    lineTo(size.width * 0.88f, size.height * 0.85f)
                }
                close()
            }
            drawPath(path, colors.onSurface)
            val barX = if (next) size.width * 0.77f else size.width * 0.12f
            drawRoundRect(
                color = colors.onSurface,
                topLeft = Offset(barX, size.height * 0.14f),
                size = Size(size.width * 0.10f, size.height * 0.72f),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

private fun formatMusicTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
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
    onLongPress: () -> Unit,
    onRowGesture: (Long, Boolean) -> Unit = { _, _ -> },
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
    // ★ 选中范围用**字符下标**（含首含尾），不是标点块下标：
    //   - 整句没有标点时，块级手柄一次都拖不动；
    //   - substring(first, last) 少算一位，复制时最后一个字永远丢。
    var selStart by remember { mutableStateOf<Int?>(null) }
    var selEnd by remember { mutableStateOf<Int?>(null) }
    // 菜单贴着"正在拖的那一端"浮：拖右手柄时菜单跟着右手柄走，而不是钉在句首。
    var anchorChar by remember { mutableStateOf<Int?>(null) }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // ★ 文字在窗口里的位置：菜单位置要用「窗口坐标」算，才能贴着选中的那行浮出来。
    var textInWindow by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(selectionActive) {
        if (!selectionActive) { selStart = null; selEnd = null; anchorChar = null }
    }

    val selection: IntRange? = if (selStart != null && selEnd != null && entry.text.isNotEmpty()) {
        val a = minOf(selStart!!, selEnd!!).coerceIn(0, entry.text.length - 1)
        val b = maxOf(selStart!!, selEnd!!).coerceIn(0, entry.text.length - 1)
        a..b
    } else null
    val selectedText = selection
        ?.let { entry.text.substring(it.first, it.last + 1) }
        ?.trim()
        .orEmpty()
    // 菜单锚点：优先跟着正在拖的那一端，落选就退回选中范围开头。
    val menuAnchorIndex: Int? = selection?.let { r ->
        anchorChar?.takeIf { it in r } ?: r.first
    }

    // ★ 手势协程用 rememberUpdatedState 读最新选择范围：
    //   直接捕获 selection 会在重组后一直读到旧快照 → 手柄"只能拖一次，下次不动"（实测）
    val rangeNow = rememberUpdatedState(selection)
    val layoutNow = rememberUpdatedState(layout)

    /** 屏幕坐标 → 字符下标。末尾之后的位置夹到最后一个字，否则永远选不上末字。 */
    fun charAt(offset: androidx.compose.ui.geometry.Offset): Int? {
        val l = layoutNow.value ?: return null
        if (entry.text.isEmpty()) return null
        return l.getOffsetForPosition(offset).coerceIn(0, entry.text.length - 1)
    }

    /** 长按/单击命中某个标点块时，用整块作为初始选择。 */
    fun chunkBoundsAt(offset: androidx.compose.ui.geometry.Offset): IntRange? {
        val index = charAt(offset) ?: return null
        return ranges.firstOrNull { index in it }
    }

    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(entry.text)
        if (selection != null) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = androidx.compose.ui.graphics.Color(0xFF2B5FA8),
                    color = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                ),
                selection.first, selection.last + 1,
            )
        }
    }

    Box(Modifier.fillMaxWidth()) {
        val bubbleShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
        // ★ 手势挂在**含 padding 的外层 Box** 上：手柄圆点画在文字下方，
        //   如果只挂 Text，点在圆点上时收不到事件 → 手柄拖不动（实测）。
        //   手势坐标以整个气泡为原点，文字布局坐标从 padding 之后开始，
        //   所以命中/取字符前统一减去 pad。
        val rowGestureHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
        val gestureModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val downRaw = awaitFirstDown(requireUnconsumed = false)
                onRowGesture(entry.id, true)
                try {
                val pad = if (isUser) Offset(12.dp.toPx(), 8.dp.toPx()) else Offset.Zero
                val down = Offset(downRaw.position.x - pad.x, downRaw.position.y - pad.y)
                // 判断按下的是哪根手柄
                var handle: Int? = null
                val r = rangeNow.value
                val l = layoutNow.value
                if (r != null && l != null && entry.text.isNotEmpty()) {
                    val a = l.getBoundingBox(r.first)
                    val b = l.getBoundingBox(r.last.coerceAtMost(entry.text.length - 1))
                    val radius = 30.dp.toPx()
                    val dot = 5.dp.toPx()
                    val dl = (down - Offset(a.left, a.bottom - dot)).getDistance()
                    val dr = (down - Offset(b.right, b.bottom - dot)).getDistance()
                    if (minOf(dl, dr) < radius) handle = if (dl <= dr) 0 else 1
                }
                if (handle != null) {
                    // ★ 拖动手柄：按字符移动（含末字），并吃掉事件避免列表滚动。
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        val raw = ev.changes.firstOrNull()?.position
                        if (raw != null) {
                            val pos = Offset(raw.x - pad.x, raw.y - pad.y)
                            val c = charAt(pos)
                            val s0 = selStart
                            val e0 = selEnd
                            if (c != null && s0 != null && e0 != null) {
                                if (handle == 0) {
                                    selStart = minOf(c, e0)
                                } else {
                                    selEnd = maxOf(c, s0)
                                }
                                anchorChar = c
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
                            if (ev.changes.any { it.changedToUpIgnoreConsumed() }) {
                                // 选中态下点在本文上 = 调整选择；
                                // 消费抬起，父级 onTap 不会清掉选择。
                                if (selStart != null) ev.changes.forEach { it.consume() }
                                break
                            }
                        }
                        true
                    }
                    if (tapped != null) {
                        if (selStart != null) {
                            val c = chunkBoundsAt(down)
                            if (c != null) { selStart = c.first; selEnd = c.last; anchorChar = c.first }
                            onSelectionChange(true)
                        }
                    } else {
                        // 落在文字边缘/空白处时兜底选第一块，别让长按"没反应"
                        val c = chunkBoundsAt(down) ?: ranges.firstOrNull()
                        if (c != null) { selStart = c.first; selEnd = c.last; anchorChar = c.first }
                        // 通知父级"这是长按"，父级在松手时就不会清掉选择
                        onLongPress()
                        onSelectionChange(true)
                        // ★ 消费长按后的抬起：否则父级 onTap 会把刚选中的内容清掉
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.any { it.changedToUpIgnoreConsumed() }) break
                        }
                    }
                }
                } finally {
                    // 延迟置回：父级 onTap 和这次 up 在同一个事件里派发，
                    // 先让父级读到"点在行内"，再允许点击空白清除选择。
                    rowGestureHandler.postDelayed({ onRowGesture(entry.id, false) }, 200L)
                }
            }
        }
        val bubbleModifier = if (isUser) {
            Modifier
                .widthIn(max = 280.dp)
                .background(colors.primary.copy(alpha = 0.20f), bubbleShape)
                .then(gestureModifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        } else {
            Modifier.widthIn(max = 300.dp).then(gestureModifier)
        }

        Box(
            Modifier
                .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
                .then(bubbleModifier),
        ) {
            Text(
                text = annotated,
                fontSize = 14.sp,
                color = textColor,
                lineHeight = 21.sp,
                modifier = Modifier.onGloballyPositioned {
                    textInWindow = it.positionInWindow()
                },
                onTextLayout = { layout = it },
            )
            if (selection != null && layout != null) {
                Canvas(Modifier.matchParentSize()) {
                    val l = layoutNow.value ?: return@Canvas
                    val r = rangeNow.value ?: return@Canvas
                    val a = l.getBoundingBox(r.first)
                    val b = l.getBoundingBox(r.last.coerceAtMost(entry.text.length - 1))
                    val hc = androidx.compose.ui.graphics.Color(0xFF4A90E2)
                    val w = 2.dp.toPx()
                    drawLine(hc, Offset(a.left, a.top), Offset(a.left, a.bottom), w)
                    drawLine(hc, Offset(b.right, b.top), Offset(b.right, b.bottom), w)
                    val dot = 5.dp.toPx()
                    // ★ 圆点画在文字行内（bottom-dot），不能画到气泡外面：
                    //   气泡的 pointerInput 只覆盖自身布局，画到外面的圆点摸不到。
                    drawCircle(hc, dot, Offset(a.left, a.bottom - dot))
                    drawCircle(hc, dot, Offset(b.right, b.bottom - dot))
                }
            }
        }

        // ★ 菜单用 Popup 浮在选中文字上方（跟手拖动也会跟着走）。
        //   之前用 graphicsLayer 平移在气泡里画：位置永远钉在整条消息的顶部，
        //   长消息里选中哪一句，菜单都跑到上面去（用户反馈"菜单没有跟随"）；
        //   而且画到气泡外的部分点不到。
        val firstBox = menuAnchorIndex?.let { layout?.getBoundingBox(it) }
        if (selection != null && firstBox != null) {
            val density = LocalDensity.current
            val anchor = IntOffset(
                (textInWindow.x + firstBox.left).toInt(),
                (textInWindow.y + firstBox.top).toInt(),
            )
            val gap = with(density) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(anchor, gap) {
                    SelectionMenuPositionProvider(anchor.x, anchor.y, gap)
                },
                onDismissRequest = {
                    selStart = null
                    selEnd = null
                    onSelectionChange(false)
                },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                ),
            ) {
                Surface(
                    // ★ 深灰底：聊天区是纯黑，菜单必须一眼能分辨出来。
                    color = Color(0xFF35373D),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier.widthIn(max = 330.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        MenuChip("复制") { onCopy(selectedText); selStart = null; selEnd = null; onSelectionChange(false) }
                        MenuChip("添加到输入框") { onAddToChat(selectedText); selStart = null; selEnd = null; onSelectionChange(false) }
                        MenuChip("全选") {
                            if (entry.text.isNotEmpty()) {
                                selStart = 0
                                selEnd = entry.text.length - 1
                                anchorChar = 0
                            }
                            onSelectionChange(true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 空文本工具条：输入框里长按不该弹出系统"粘贴"浮窗
 * （华为/部分 ROM 上它一碰就消失，还会跟长按说话抢手势）。
 */
private object NoopTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

/**
 * 选中文字菜单的定位：横向以选中字为中心（贴边时夹回屏幕），
 * 纵向优先放在选中行上方；上方放不下就放到下方。
 */
private class SelectionMenuPositionProvider(
    private val anchorX: Int,
    private val anchorTop: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val x = (anchorX - popupContentSize.width / 2).coerceIn(gap, maxX)
        val above = anchorTop - popupContentSize.height - gap
        val y = if (above >= gap) above else anchorTop + gap
        return IntOffset(x, y)
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
    onInputFocused: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val focusReq = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var recording by remember { mutableStateOf(false) }
    // 松手后短时间内强制隐藏"正在听"动画：语音状态层可能还在 LISTENING/RECOGNIZING，
    // 但用户已经松手，输入框不该继续转圈。
    var suppressListeningUi by remember { mutableStateOf(false) }
    // 是否已进入"文本输入态"（聚焦中）。false 时罩层挡着，TextField 收不到触摸。
    var textMode by remember { mutableStateOf(false) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val showStop = speaking || running || justSent
    // 回车发送：空文本 / 任务进行中不误发。
    val submit = {
        if (!showStop && value.text.isNotBlank()) onSend()
    }

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

    // ★ 老板要求：语音唤醒或长按激活后进入连续对话，只要麦克风还在收，
    //   输入框就要显示「正在听…」+ 流光边框。
    //   但排除 WAKE_WAIT/ASLEEP（常驻待命也在开麦，那不算"在听"）——
    //   这正是之前"输入框一直显示正在听"的坑，靠 sessionListening 区分。
    val live = recording || (sessionListening && !suppressListeningUi)
    // 只在实际聆听时创建动画；进入休眠后立即销毁，避免隐藏边框继续空转。
    val phase = if (live) {
        val spin = rememberInfiniteTransition(label = "rec")
        val animatedPhase by spin.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(3600, easing = LinearEasing),   // 慢一点，像"在录"而不是"在闪"
                repeatMode = RepeatMode.Reverse,
            ),
            label = "phase",
        )
        animatedPhase
    } else {
        0f
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            // ★ 输入框不要系统"粘贴"浮窗：华为的浮窗一碰鼠标/手指就消失，
            //   还会和输入框上的长按手势互相抢事件。系统文本工具条换成空实现。
            CompositionLocalProvider(LocalTextToolbar provides NoopTextToolbar) {
                OutlinedTextField(
                    value = value,                    // ★ 录音时不清空用户已输入的字
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusReq)
                        .onFocusChanged { state ->
                            // 失焦就退回非文本态（罩层回来），下次单击重新判定
                            if (!state.isFocused && textMode) textMode = false
                            if (state.isFocused) onInputFocused()
                        }
                        // 硬件键盘回车发送；Shift+回车保留换行。
                        // ★ 用 onPreviewKeyEvent：TextField 自己会先消费 Enter
                        //   （插入换行），onKeyEvent 已经轮不到。
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                !event.isShiftPressed
                            ) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("点一下输入，长按说话", fontSize = 13.sp) },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
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
                                        // ★ 无论松手、超时还是协程被取消，都必须通知语音层收状态。
                                        //   先关本地录音态并抑制动画，避免识别期间继续转圈。
                                        recording = false
                                        suppressListeningUi = true
                                        handler.post { onFinishVoice() }
                                        handler.postDelayed({ suppressListeningUi = false }, 1_500L)
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        Button(
            onClick = when {
                speaking -> onStopSpeaking       // 播报中：闭嘴 + 停任务
                running || justSent -> onStop    // 任务中：中止任务
                else -> submit
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

/** Long-term memories are explicit records and can be deleted in this dialog. */
@Composable
private fun MemoryListDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var pendingDelete by remember {
        mutableStateOf<com.pilot.agent.tool.MemoryStore.Memory?>(null)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF000000),
        title = { Text("长期记忆 ${vm.memoryList.size} 条", fontSize = 16.sp) },
        text = {
            if (vm.memoryList.isEmpty()) {
                Text("还没有长期记忆。明确说“记住……”才会保存。", fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.memoryList, key = { it.id }) { memory ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                memory.content,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { pendingDelete = memory }) {
                                Text("删除", fontSize = 12.sp, color = colors.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
    pendingDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记忆？") },
            text = { Text(memory.content) },
            confirmButton = {
                TextButton(onClick = {
                    vm.forgetMemory(memory.id)
                    pendingDelete = null
                }) { Text("永久删除", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
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
