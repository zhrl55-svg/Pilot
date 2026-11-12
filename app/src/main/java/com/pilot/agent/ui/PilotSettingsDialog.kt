package com.pilot.agent.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.agent.AssistantMode
import com.pilot.agent.tool.ToolGroup
import com.pilot.agent.tool.ToolPolicy
import kotlinx.coroutines.delay

private val SettingsBlack = Color(0xFF000000)
private val SettingsCard = Color(0xFF0D1118)
private val SettingsCardRaised = Color(0xFF121824)
private val SettingsLine = Color(0xFF223047)
private val SettingsBlue = Color(0xFF78A9FF)
private val SettingsMuted = Color(0xFF94A3B8)

private enum class SettingsGlyph {
    TEXT,
    SPEAKER,
    WRENCH,
    DATABASE,
}

private enum class SettingsPage(
    val glyph: SettingsGlyph,
    val marker: String,
    val title: String,
    val subtitle: String,
    val accent: Color,
) {
    VOICE(
        SettingsGlyph.SPEAKER,
        "",
        "语音与播报",
        "唤醒、声纹、语音打断与播报方式",
        Color(0xFF70A7FF),
    ),
    AI(
        SettingsGlyph.TEXT,
        "AI",
        "AI 与网络",
        "对话模型、上下文和联网搜索",
        Color(0xFF9B8CFF),
    ),
    AGENT(
        SettingsGlyph.TEXT,
        "A",
        "Agent 与扩展",
        "运行模式、执行预算、浏览器、脚本和 MCP",
        Color(0xFF68B7FF),
    ),
    MUSIC(
        SettingsGlyph.TEXT,
        "♪",
        "音乐服务",
        "网易云登录、服务地址与网络选路",
        Color(0xFF45C8A2),
    ),
    PERMISSIONS(
        SettingsGlyph.WRENCH,
        "",
        "权限与工具",
        "助理权限、文件访问和工具确认策略",
        Color(0xFFFFB45E),
    ),
    SANDBOX(
        SettingsGlyph.DATABASE,
        "",
        "沙箱与数据",
        "容器挂载、资源回收和已学技能",
        Color(0xFF55B9E8),
    ),
}

private class SettingsDraft(initial: AgentSettings) {
    var baseUrl by mutableStateOf(initial.baseUrl)
    var apiKey by mutableStateOf(initial.apiKey)
    var webSearchApiKey by mutableStateOf(initial.webSearchApiKey)
    var model by mutableStateOf(initial.model)
    var maxTurns by mutableStateOf(initial.maxTurns.toString())
    var mode by mutableStateOf(initial.mode)
    var reasoningEffort by mutableStateOf(initial.reasoningEffort)
    var preferOnDevice by mutableStateOf(initial.preferOnDevice)
    var allowSkillReplay by mutableStateOf(initial.allowSkillReplay)
    var maxChatTurns by mutableStateOf(initial.chatContextTurns.toString())

    var wakeWords by mutableStateOf(initial.wakeWords)
    var wakeTone by mutableStateOf(initial.wakeTone)
    var requireVoiceprint by mutableStateOf(initial.requireVoiceprint)
    var voiceprintThreshold by mutableStateOf(
        String.format(java.util.Locale.US, "%.2f", initial.voiceprintThreshold),
    )
    var idleSleepSeconds by mutableStateOf(initial.idleSleepSeconds.toString())
    var interruptEnabled by mutableStateOf(initial.interruptEnabled)
    var interruptWords by mutableStateOf(initial.interruptWords)
    var ttsEngine by mutableStateOf(initial.ttsEngine)
    var ttsVoiceId by mutableStateOf(initial.ttsVoiceId)
    var systemVoice by mutableStateOf(initial.systemTtsVoice)

    var musicPolicy by mutableStateOf(initial.musicPolicy)
    var musicBaseUrl by mutableStateOf(initial.musicBaseUrl)
    var musicFallbackUrl by mutableStateOf(initial.musicFallbackUrl)
    var sandboxIdleMinutes by mutableStateOf(initial.sandboxIdleMinutes.toString())

    fun saveFrom(base: AgentSettings): AgentSettings = base.copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        webSearchApiKey = webSearchApiKey.trim(),
        model = model.trim(),
        mode = mode,
        maxTurns = maxTurns.toIntOrNull()
            ?.coerceIn(AgentSettings.MIN_AGENT_TURNS, AgentSettings.MAX_AGENT_TURNS)
            ?: AgentSettings.DEFAULT_MAX_TURNS,
        reasoningEffort = reasoningEffort.takeIf {
            it in AgentSettings.REASONING_EFFORTS
        } ?: AgentSettings.DEFAULT_REASONING_EFFORT,
        preferOnDevice = preferOnDevice,
        allowSkillReplay = allowSkillReplay,
        chatContextTurns = maxChatTurns.toIntOrNull()?.coerceIn(0, 20) ?: 4,
        wakeWords = wakeWords.trim().ifEmpty { AgentSettings.DEFAULT_WAKE_WORDS },
        wakeTone = wakeTone,
        requireVoiceprint = requireVoiceprint,
        voiceprintThreshold =
            voiceprintThreshold.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.30f,
        idleSleepSeconds = idleSleepSeconds.toIntOrNull()?.coerceIn(0, 600) ?: 60,
        interruptEnabled = interruptEnabled,
        interruptWords = interruptWords.trim().ifEmpty {
            "停一下,停下来,不说了,别说了"
        },
        ttsEngine = ttsEngine,
        ttsVoiceId = ttsVoiceId,
        systemTtsVoice = systemVoice,
        musicPolicy = musicPolicy,
        musicBaseUrl = musicBaseUrl.trim().trimEnd('/'),
        musicFallbackUrl = musicFallbackUrl.trim().trimEnd('/'),
        sandboxIdleMinutes =
            sandboxIdleMinutes.toIntOrNull()?.coerceIn(0, 120) ?: 5,
    )
}

@Composable
internal fun PilotSettingsDialog(
    vm: AgentViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val draft = remember { SettingsDraft(vm.settings) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var toolPolicyDraft by remember { mutableStateOf(vm.toolPolicies) }
    val homeListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshLocalToolState()
            delay(1_500)
        }
    }

    BackHandler(enabled = page != null) {
        page = null
    }

    Dialog(
        onDismissRequest = {
            if (page == null) {
                onDismiss()
            } else {
                page = null
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SettingsBlack)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                SettingsTopBar(
                    page = page,
                    onBack = { page = null },
                    onClose = onDismiss,
                )
                HorizontalDivider(color = SettingsLine.copy(alpha = 0.55f))

                Box(Modifier.weight(1f)) {
                    if (page == null) {
                        SettingsHome(
                            vm = vm,
                            draft = draft,
                            listState = homeListState,
                            onOpen = { page = it },
                        )
                    } else {
                        SettingsPageContent(
                            page = page!!,
                            vm = vm,
                            draft = draft,
                            toolPolicyDraft = toolPolicyDraft,
                            onToolPolicyChange = { group, policy ->
                                toolPolicyDraft = toolPolicyDraft + (group to policy)
                            },
                        )
                    }
                }

                SettingsSaveBar(
                    onSave = {
                        toolPolicyDraft.forEach { (group, policy) ->
                            vm.setToolPolicy(group, policy)
                        }
                        AgentSettings.save(draft.saveFrom(vm.settings))
                        vm.reloadSettings()
                        vm.refreshStatus()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsTopBar(
    page: SettingsPage?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (page == null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(SettingsBlue.copy(alpha = 0.24f), Color.Transparent),
                        ),
                    )
                    .border(
                        1.dp,
                        SettingsBlue.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SettingsGearGlyph(
                    color = SettingsBlue,
                    modifier = Modifier
                        .size(21.dp)
                        .semantics { contentDescription = "设置中心" },
                )
            }
        } else {
            SettingsRoundButton(
                text = "‹",
                contentDescription = "返回设置首页",
                onClick = onBack,
            )
        }

        if (page == null) {
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page.title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                )
                Text(
                    page.subtitle,
                    fontSize = 10.sp,
                    color = SettingsMuted,
                )
            }
        }
        SettingsRoundButton(
            text = "×",
            contentDescription = "关闭设置，不保存修改",
            onClick = onClose,
        )
    }
}

@Composable
private fun SettingsGearGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.8.dp.toPx()
        val gear = Path()
        repeat(16) { index ->
            val angle = Math.toRadians((index * 22.5) - 90.0)
            val radius = size.minDimension * if (index % 2 == 0) 0.47f else 0.34f
            val point = Offset(
                center.x + kotlin.math.cos(angle).toFloat() * radius,
                center.y + kotlin.math.sin(angle).toFloat() * radius,
            )
            if (index == 0) {
                gear.moveTo(point.x, point.y)
            } else {
                gear.lineTo(point.x, point.y)
            }
        }
        gear.close()
        drawPath(gear, color = color, style = Stroke(width = stroke))
        drawCircle(
            color = color,
            radius = size.minDimension * 0.11f,
            center = center,
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun SettingsRoundButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(SettingsCardRaised)
            .border(1.dp, SettingsLine, CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
    }
}

@Composable
private fun SettingsHome(
    vm: AgentViewModel,
    draft: SettingsDraft,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (SettingsPage) -> Unit,
) {
    val permissionCount = listOf(
        vm.notificationAccess,
        vm.reminderNotificationsAllowed,
        vm.allFilesAccess,
    ).count { it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 10.dp,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SettingsCategoryCard(
                page = SettingsPage.VOICE,
                status = when (draft.ttsEngine) {
                    AgentSettings.TTS_MINIMAX -> "MiniMax 播报"
                    AgentSettings.TTS_SYSTEM -> "系统播报"
                    else -> "仅文字回复"
                },
                statusActive = draft.ttsEngine != AgentSettings.TTS_OFF,
                onClick = { onOpen(SettingsPage.VOICE) },
            )
        }
        item {
            SettingsCategoryCard(
                page = SettingsPage.AI,
                status = if (draft.apiKey.isBlank()) "模型待配置" else draft.model,
                statusActive = draft.apiKey.isNotBlank(),
                onClick = { onOpen(SettingsPage.AI) },
            )
        }
        item {
            SettingsCategoryCard(
                page = SettingsPage.AGENT,
                status = "${draft.mode.label} · ${draft.maxTurns} 步 · " +
                    "${vm.userScripts.size} 个脚本 · ${vm.mcpServers.size} 个 MCP",
                statusActive = draft.mode == AssistantMode.AGENT,
                onClick = { onOpen(SettingsPage.AGENT) },
            )
        }
        item {
            SettingsCategoryCard(
                page = SettingsPage.MUSIC,
                status = if (vm.neteaseNick.isBlank()) "网易云未登录" else "已登录 ${vm.neteaseNick}",
                statusActive = vm.neteaseNick.isNotBlank(),
                onClick = { onOpen(SettingsPage.MUSIC) },
            )
        }
        item {
            SettingsCategoryCard(
                page = SettingsPage.PERMISSIONS,
                status = "$permissionCount 项系统权限已开启",
                statusActive = permissionCount >= 2,
                onClick = { onOpen(SettingsPage.PERMISSIONS) },
            )
        }
        item {
            SettingsCategoryCard(
                page = SettingsPage.SANDBOX,
                status = "${vm.sandboxMounts().size} 个目录挂载 · ${vm.skillCount} 条技能",
                statusActive = true,
                onClick = { onOpen(SettingsPage.SANDBOX) },
            )
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SettingsCard.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(SettingsBlue),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "设置保存在本机；密钥字段使用加密存储。",
                    fontSize = 10.sp,
                    color = SettingsMuted,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    val color = if (active) Color(0xFF59D6A8) else SettingsMuted
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SettingsCategoryCard(
    page: SettingsPage,
    status: String,
    statusActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .clickable(onClick = onClick),
        color = SettingsCard,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, SettingsLine.copy(alpha = 0.8f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(page.accent.copy(alpha = 0.13f))
                    .border(
                        1.dp,
                        page.accent.copy(alpha = 0.24f),
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SettingsCategoryGlyph(page)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    page.subtitle,
                    fontSize = 9.sp,
                    color = SettingsMuted,
                    lineHeight = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    status,
                    fontSize = 9.sp,
                    color = if (statusActive) page.accent else SettingsMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 24.sp, color = page.accent.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun SettingsCategoryGlyph(page: SettingsPage) {
    if (page.glyph == SettingsGlyph.TEXT) {
        Text(
            page.marker,
            fontSize = if (page.marker.length > 1) 11.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = page.accent,
        )
        return
    }

    Canvas(Modifier.size(25.dp)) {
        val w = size.width
        val h = size.height
        val color = page.accent
        val stroke = 1.9.dp.toPx()

        when (page.glyph) {
            SettingsGlyph.SPEAKER -> {
                val body = Path().apply {
                    moveTo(w * 0.10f, h * 0.42f)
                    lineTo(w * 0.31f, h * 0.42f)
                    lineTo(w * 0.52f, h * 0.22f)
                    lineTo(w * 0.52f, h * 0.78f)
                    lineTo(w * 0.31f, h * 0.58f)
                    lineTo(w * 0.10f, h * 0.58f)
                    close()
                }
                drawPath(body, color)
                drawArc(
                    color = color,
                    startAngle = -52f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = Offset(w * 0.50f, h * 0.34f),
                    size = Size(w * 0.25f, h * 0.32f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color.copy(alpha = 0.82f),
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(w * 0.51f, h * 0.19f),
                    size = Size(w * 0.41f, h * 0.62f),
                    style = Stroke(width = stroke * 0.82f, cap = StrokeCap.Round),
                )
            }

            SettingsGlyph.WRENCH -> {
                fun x(value: Float) = w * (value / 24f)
                fun y(value: Float) = h * (value / 24f)

                val wrench = Path().apply {
                    moveTo(x(22.7f), y(19f))
                    lineTo(x(13.6f), y(9.9f))
                    cubicTo(
                        x(14.5f),
                        y(7.6f),
                        x(14.0f),
                        y(4.9f),
                        x(12.1f),
                        y(3.0f),
                    )
                    cubicTo(
                        x(10.1f),
                        y(1.0f),
                        x(7.1f),
                        y(0.6f),
                        x(4.7f),
                        y(1.7f),
                    )
                    lineTo(x(9.0f), y(6.0f))
                    lineTo(x(6.0f), y(9.0f))
                    lineTo(x(1.6f), y(4.7f))
                    cubicTo(
                        x(0.4f),
                        y(7.1f),
                        x(0.9f),
                        y(10.1f),
                        x(2.9f),
                        y(12.1f),
                    )
                    cubicTo(
                        x(4.8f),
                        y(14.0f),
                        x(7.5f),
                        y(14.5f),
                        x(9.8f),
                        y(13.6f),
                    )
                    lineTo(x(18.9f), y(22.7f))
                    cubicTo(
                        x(19.3f),
                        y(23.1f),
                        x(19.9f),
                        y(23.1f),
                        x(20.3f),
                        y(22.7f),
                    )
                    lineTo(x(22.6f), y(20.4f))
                    cubicTo(
                        x(23.0f),
                        y(20.0f),
                        x(23.0f),
                        y(19.4f),
                        x(22.7f),
                        y(19.0f),
                    )
                    close()
                }
                drawPath(wrench, color)
            }

            SettingsGlyph.DATABASE -> {
                drawOval(
                    color = color,
                    topLeft = Offset(w * 0.20f, h * 0.14f),
                    size = Size(w * 0.60f, h * 0.22f),
                    style = Stroke(width = stroke),
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.20f, h * 0.25f),
                    end = Offset(w * 0.20f, h * 0.73f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.80f, h * 0.25f),
                    end = Offset(w * 0.80f, h * 0.73f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawArc(
                    color = color.copy(alpha = 0.82f),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, h * 0.43f),
                    size = Size(w * 0.60f, h * 0.18f),
                    style = Stroke(width = stroke * 0.86f),
                )
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, h * 0.64f),
                    size = Size(w * 0.60f, h * 0.19f),
                    style = Stroke(width = stroke),
                )
            }

            SettingsGlyph.TEXT -> Unit
        }
    }
}

@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    vm: AgentViewModel,
    draft: SettingsDraft,
    toolPolicyDraft: Map<ToolGroup, ToolPolicy>,
    onToolPolicyChange: (ToolGroup, ToolPolicy) -> Unit,
) {
    when (page) {
        SettingsPage.VOICE -> VoiceSettingsPage(vm, draft)
        SettingsPage.AI -> AiSettingsPage(draft)
        SettingsPage.AGENT -> AgentSettingsPage(vm, draft)
        SettingsPage.MUSIC -> MusicSettingsPage(vm, draft)
        SettingsPage.PERMISSIONS -> PermissionSettingsPage(
            vm,
            toolPolicyDraft,
            onToolPolicyChange,
        )
        SettingsPage.SANDBOX -> SandboxSettingsPage(vm, draft)
    }
}

@Composable
private fun VoiceSettingsPage(vm: AgentViewModel, draft: SettingsDraft) {
    SettingsPageList {
        item {
            SettingsSectionCard(
                title = "唤醒与声纹",
                description = "唤醒词支持用逗号添加多个说法。声纹未注册时，第一次唤醒会自动建立模板。",
            ) {
                SettingsTextField(
                    label = "唤醒词",
                    value = draft.wakeWords,
                    onValueChange = { draft.wakeWords = it },
                    placeholder = AgentSettings.DEFAULT_WAKE_WORDS,
                )
                SettingsToggleRow(
                    title = "唤醒提示音",
                    subtitle = "命中唤醒词后播放一声短提示音",
                    checked = draft.wakeTone,
                    onCheckedChange = { draft.wakeTone = it },
                )
                HorizontalDivider(color = SettingsLine.copy(alpha = 0.55f))
                SettingsToggleRow(
                    title = "声纹校验",
                    subtitle = "只响应已注册主人的声音",
                    checked = draft.requireVoiceprint,
                    onCheckedChange = { draft.requireVoiceprint = it },
                )
                SettingsTextField(
                    label = "声纹阈值（0.10–0.90，越低越宽松）",
                    value = draft.voiceprintThreshold,
                    onValueChange = { draft.voiceprintThreshold = it },
                    keyboardType = KeyboardType.Decimal,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.voiceprintEnrolled) "已注册声纹模板" else "尚未注册声纹",
                        fontSize = 11.sp,
                        color = if (vm.voiceprintEnrolled) Color(0xFF59D6A8) else SettingsMuted,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.voiceprintEnrolled) {
                        TextButton(onClick = { vm.clearVoiceprint() }) {
                            Text("清除模板", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "聆听与打断",
                description = "唤醒后在一段时间内可直接连续对话；可设置播报期间用语音打断。",
            ) {
                SettingsTextField(
                    label = "对话结束后休眠（秒；0 表示持续保持聆听）",
                    value = draft.idleSleepSeconds,
                    onValueChange = { draft.idleSleepSeconds = it },
                    keyboardType = KeyboardType.Number,
                )
                SettingsToggleRow(
                    title = "语音打断",
                    subtitle = "播报时说出打断词即可停止并开始聆听",
                    checked = draft.interruptEnabled,
                    onCheckedChange = { draft.interruptEnabled = it },
                )
                if (draft.interruptEnabled) {
                    SettingsTextField(
                        label = "打断词",
                        value = draft.interruptWords,
                        onValueChange = { draft.interruptWords = it },
                        placeholder = "停一下,停下来,不说了",
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "声音与播报",
                description = "MiniMax 更自然；系统 TTS 离线、延迟更低。",
            ) {
                SegmentChoice(
                    value = draft.ttsEngine,
                    options = listOf(
                        AgentSettings.TTS_MINIMAX to "MiniMax",
                        AgentSettings.TTS_SYSTEM to "系统 TTS",
                        AgentSettings.TTS_OFF to "关闭",
                    ),
                    onSelect = { draft.ttsEngine = it },
                )
                when (draft.ttsEngine) {
                    AgentSettings.TTS_MINIMAX -> DropdownSetting(
                        label = "MiniMax 音色",
                        selected = draft.ttsVoiceId,
                        options = com.pilot.agent.voice.MiniMaxTts.VOICES.map {
                            it.id to it.label
                        },
                        onSelect = { draft.ttsVoiceId = it },
                        onPreview = { vm.previewVoice(AgentSettings.TTS_MINIMAX, it) },
                    )
                    AgentSettings.TTS_SYSTEM -> DropdownSetting(
                        label = "系统音色",
                        selected = draft.systemVoice,
                        options = listOf("" to "默认音色") +
                            vm.systemVoices.mapIndexed { index, voice ->
                                voice.name to voice.label(index)
                            },
                        onSelect = { draft.systemVoice = it },
                        placeholder = if (vm.systemVoices.isEmpty()) {
                            "正在读取本机音色…"
                        } else {
                            "选择音色"
                        },
                        onPreview = { id ->
                            if (id.isNotBlank()) {
                                vm.previewVoice(AgentSettings.TTS_SYSTEM, id)
                            }
                        },
                    )
                }
                Text("播报时音乐", fontSize = 10.sp, color = SettingsMuted)
                SegmentChoice(
                    value = draft.musicPolicy,
                    options = listOf(
                        AgentSettings.MUSIC_DUCK to "降低音量",
                        AgentSettings.MUSIC_STOP to "停止音乐",
                    ),
                    onSelect = { draft.musicPolicy = it },
                )
            }
        }
    }
}

@Composable
private fun AiSettingsPage(draft: SettingsDraft) {
    SettingsPageList {
        item {
            SettingsSectionCard(
                title = "对话模型",
            ) {
                SettingsTextField(
                    label = "Base URL（OpenAI Chat Completions 兼容接口）",
                    value = draft.baseUrl,
                    onValueChange = { draft.baseUrl = it },
                    keyboardType = KeyboardType.Uri,
                )
                SettingsSecretField(
                    label = "API Key（加密保存在本机）",
                    value = draft.apiKey,
                    onValueChange = { draft.apiKey = it },
                )
                SettingsTextField(
                    label = "模型名",
                    value = draft.model,
                    onValueChange = { draft.model = it },
                )
                SettingsTextField(
                    label = "上下文轮数（普通聊天携带最近几轮问答）",
                    value = draft.maxChatTurns,
                    onValueChange = { draft.maxChatTurns = it },
                    keyboardType = KeyboardType.Number,
                )
                SettingsToggleRow(
                    title = "简单指令优先走端侧",
                    subtitle = "少一次模型请求，响应更快",
                    checked = draft.preferOnDevice,
                    onCheckedChange = { draft.preferOnDevice = it },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "网络搜索",
                description = "新闻主题仍可通过 Google News RSS 获取。",
            ) {
                SettingsSecretField(
                    label = "Brave Search API Key（通用网页搜索，可在 Dashboard 申请）",
                    value = draft.webSearchApiKey,
                    onValueChange = { draft.webSearchApiKey = it },
                )
            }
        }
    }
}

@Composable
private fun AgentSettingsPage(vm: AgentViewModel, draft: SettingsDraft) {
    val scriptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importUserScript(uri)
    }

    LaunchedEffect(Unit) {
        vm.refreshExtensions()
    }

    SettingsPageList {
        item {
            SettingsSectionCard(
                title = "运行模式",
                description = "助理自动处理规则、查询和聊天；Agent 增加多步执行、浏览器、MCP 与用户脚本。",
            ) {
                SegmentChoice(
                    value = draft.mode.storageValue,
                    options = AssistantMode.entries.map { it.storageValue to it.label },
                    onSelect = { draft.mode = AssistantMode.fromStorage(it) },
                )
                InfoLine(draft.mode.description)
                SettingsTextField(
                    label = "Agent 最大执行步数（${AgentSettings.MIN_AGENT_TURNS}–" +
                        "${AgentSettings.MAX_AGENT_TURNS}，默认 ${AgentSettings.DEFAULT_MAX_TURNS}）",
                    value = draft.maxTurns,
                    onValueChange = { draft.maxTurns = it.filter(Char::isDigit).take(2) },
                    keyboardType = KeyboardType.Number,
                )
                DropdownSetting(
                    label = "模型思考强度",
                    selected = draft.reasoningEffort,
                    options = listOf(
                        "low" to "低",
                        "medium" to "中",
                        "high" to "高",
                        "xhigh" to "极高",
                        "max" to "最高",
                    ),
                    onSelect = { draft.reasoningEffort = it },
                )
                InfoLine("Agent 云端模型默认使用“中”；模型支持时可调整。")
                SettingsToggleRow(
                    title = "复用已学操作轨迹",
                    subtitle = "相同任务优先回放成功过的界面步骤",
                    checked = draft.allowSkillReplay,
                    onCheckedChange = { draft.allowSkillReplay = it },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "浏览器会话",
                description = "Agent 使用可见 WebView，保留登录 Cookie；只允许 HTTP/HTTPS，本地文件访问已关闭。",
            ) {
                StatusActionRow(
                    title = "持久登录状态",
                    value = "Cookie 保留在 Pilot 私有目录",
                    action = "清除",
                    onAction = { vm.clearBrowserSession() },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "用户脚本",
                description = "脚本仅支持浏览器与 MCP 步骤。导入 JSON 后，命中触发器即可执行。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledAction("导入 JSON", Modifier.weight(1f)) {
                        scriptPicker.launch(arrayOf("application/json", "text/plain"))
                    }
                    OutlineAction("刷新", Modifier.weight(1f)) { vm.refreshExtensions() }
                }
                if (vm.userScripts.isEmpty()) {
                    InfoLine("尚未安装脚本。可把 .json 放入 files/minis-global/skills。")
                } else {
                    vm.userScripts.forEach { script ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.18f))
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    script.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    script.triggers.joinToString(" · ").take(100),
                                    fontSize = 9.sp,
                                    color = SettingsMuted,
                                )
                            }
                            TextButton(onClick = { vm.deleteUserScript(script.id) }) {
                                Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "MCP 客户端",
                description = "与沙箱终端共用 servers.json；支持 HTTP、STDIO 与 OAuth 登录桥。",
            ) {
                if (vm.mcpServers.isEmpty()) {
                    InfoLine("尚未配置 MCP 服务器。配置文件：files/minis-global/mcp-servers/servers.json")
                } else {
                    vm.mcpServers.forEach { server ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                server.name,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            StatusPill(
                                "${server.transport}${if (server.enabled) "" else " · 停用"}",
                                server.enabled,
                            )
                        }
                    }
                }
                FilledAction(
                    if (vm.mcpProbeRunning) "正在检查…" else "检查 MCP 运行时",
                    Modifier.fillMaxWidth(),
                ) {
                    if (!vm.mcpProbeRunning) vm.probeMcp()
                }
                if (vm.mcpProbeText.isNotBlank()) {
                    Text(
                        vm.mcpProbeText,
                        fontSize = 10.sp,
                        color = SettingsMuted,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicSettingsPage(vm: AgentViewModel, draft: SettingsDraft) {
    val colors = MaterialTheme.colorScheme
    SettingsPageList {
        item {
            SettingsSectionCard(
                title = "网易云连接",
                description = "本地服务部署在局域网或 Tailnet。Pilot 会优先选择当前网络可达的地址。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (vm.neteaseNick.isBlank()) "未登录网易云" else "已登录",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                        )
                        Text(
                            vm.neteaseNick.ifBlank { "扫码后即可播放会员歌曲" },
                            fontSize = 10.sp,
                            color = SettingsMuted,
                        )
                    }
                    StatusPill(
                        if (vm.neteaseNick.isBlank()) "等待登录" else "账号正常",
                        vm.neteaseNick.isNotBlank(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.settings.neteaseCookie.isNotBlank()) {
                        OutlineAction("退出登录", Modifier.weight(1f)) { vm.logoutNetease() }
                    }
                    FilledAction(
                        if (vm.settings.neteaseCookie.isBlank()) "扫码登录" else "重新登录",
                        Modifier.weight(1f),
                    ) { vm.startQrLogin() }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "服务地址",
                description = "Wi‑Fi 下优先局域网，移动网络下优先 Tailnet；主地址失败时自动尝试备用地址。",
            ) {
                SettingsTextField(
                    label = "在线 API 主地址（Wi‑Fi 下优先局域网）",
                    value = draft.musicBaseUrl,
                    onValueChange = { draft.musicBaseUrl = it },
                    keyboardType = KeyboardType.Uri,
                )
                SettingsTextField(
                    label = "备用地址（主地址失败时自动尝试）",
                    value = draft.musicFallbackUrl,
                    onValueChange = { draft.musicFallbackUrl = it },
                    keyboardType = KeyboardType.Uri,
                )
                StatusActionRow(
                    title = "Tailnet",
                    value = vm.tailnetState,
                    action = "连接",
                    onAction = { vm.connectTailnet() },
                )
                StatusActionRow(
                    title = "音乐 API",
                    value = vm.musicServiceState,
                    action = "测试",
                    onAction = {
                        vm.testMusicService(draft.musicBaseUrl, draft.musicFallbackUrl)
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionSettingsPage(
    vm: AgentViewModel,
    toolPolicyDraft: Map<ToolGroup, ToolPolicy>,
    onToolPolicyChange: (ToolGroup, ToolPolicy) -> Unit,
) {
    val context = LocalContext.current
    SettingsPageList {
        item {
            SettingsSectionCard(
                title = "个人助理权限",
                description = "通知读取仅用于当前通知与提醒；提醒功能需要通知发送权限。",
            ) {
                PermissionActionRow(
                    title = "通知读取",
                    state = if (vm.notificationAccess) "已开启" else "未授权",
                    active = vm.notificationAccess,
                    action = if (vm.notificationAccess) "管理" else "开启",
                ) {
                    openSystemSettings(
                        context,
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    )
                }
                PermissionActionRow(
                    title = "提醒通知",
                    state = if (vm.reminderNotificationsAllowed) "可用" else "未授权",
                    active = vm.reminderNotificationsAllowed,
                    action = "管理",
                    onAction = {
                        val intent = if (Build.VERSION.SDK_INT >= 26) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        } else {
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            )
                        }
                        openSystemSettings(context, intent)
                    },
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "工具确认策略",
                description = "按工具类型设置是否允许、每次询问或禁止。删除与外发默认每次询问。",
            ) {
                ToolGroup.entries.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(color = SettingsLine.copy(alpha = 0.5f))
                    }
                    Text(
                        group.label,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SegmentChoice(
                        value = (toolPolicyDraft[group] ?: group.defaultPolicy).name,
                        options = ToolPolicy.entries.map { it.name to it.label },
                        onSelect = { value ->
                            runCatching { ToolPolicy.valueOf(value) }
                                .getOrNull()
                                ?.let { onToolPolicyChange(group, it) }
                        },
                    )
                }
                HorizontalDivider(color = SettingsLine.copy(alpha = 0.5f))
                Text(
                    "长期记忆 ${vm.toolMemoryCount} 条",
                    fontSize = 12.sp,
                    color = SettingsBlue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openMemoryList() }
                        .padding(vertical = 8.dp),
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "系统访问",
                description = "应用使用统计用于理解前台应用；所有文件访问用于本地文件和沙箱目录。",
            ) {
                PermissionActionRow(
                    title = "应用使用统计",
                    state = "系统权限",
                    active = false,
                    action = "授权",
                ) {
                    openSystemSettings(
                        context,
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    )
                }
                PermissionActionRow(
                    title = "所有文件访问",
                    state = if (vm.allFilesAccess) "已授权" else "未授权",
                    active = vm.allFilesAccess,
                    action = if (vm.allFilesAccess) "管理" else "开启",
                ) {
                    val appSettings = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    val opened = runCatching {
                        context.startActivity(appSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        openSystemSettings(
                            context,
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxSettingsPage(vm: AgentViewModel, draft: SettingsDraft) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var addPathDialog by remember { mutableStateOf(false) }
    val mounts = remember(refreshTick) { vm.sandboxMounts() }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?: "mnt"
            vm.mountSandboxFolder(uri, name)
            refreshTick++
        }
    }

    SettingsPageList {
        item {
            SettingsSectionCard(
                title = "沙箱挂载目录",
                description = "目录会 bind 到容器 /var/minis/mounts/<名字>，下一条沙箱命令立即生效。",
            ) {
                if (mounts.isEmpty()) {
                    EmptySettingsState(
                        marker = "夹",
                        title = "还没有挂载目录",
                        subtitle = "把 Download、文档或项目目录挂进沙箱",
                    )
                } else {
                    mounts.forEach { (name, path) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    path,
                                    fontSize = 9.sp,
                                    color = SettingsMuted,
                                    maxLines = 1,
                                )
                            }
                            TextButton(
                                onClick = {
                                    vm.unmountSandboxFolder(name)
                                    refreshTick++
                                },
                            ) {
                                Text(
                                    "移除",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledAction("选择目录", Modifier.weight(1f)) {
                        picker.launch(null)
                    }
                    OutlineAction("手动路径", Modifier.weight(1f)) {
                        addPathDialog = true
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "运行与回收",
                description = "沙箱进程闲置后自动回收内存，下次使用会自动重启。",
            ) {
                SettingsTextField(
                    label = "空闲回收时间（分钟；0 表示不自动回收）",
                    value = draft.sandboxIdleMinutes,
                    onValueChange = { draft.sandboxIdleMinutes = it },
                    keyboardType = KeyboardType.Number,
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "学习与数据",
                description = "Pilot 会记录成功完成的操作，之后可复用为技能。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已学习 ${vm.skillCount} 条技能",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "点击查看可复用的操作流程",
                            fontSize = 10.sp,
                            color = SettingsMuted,
                        )
                    }
                    TextButton(onClick = { vm.openSkillList() }) {
                        Text("查看", fontSize = 11.sp)
                    }
                }
                OutlineAction("清空全部已学技能", Modifier.fillMaxWidth()) {
                    vm.clearSkills()
                }
            }
        }
    }

    if (addPathDialog) {
        AddMountPathDialog(
            onDismiss = { addPathDialog = false },
            onAdd = { path, name ->
                vm.mountSandboxPath(path, name)
                refreshTick++
                addPathDialog = false
            },
        )
    }
}

@Composable
private fun AddMountPathDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var path by remember { mutableStateOf("/storage/emulated/0/Download") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SettingsCardRaised,
        title = { Text("手动添加挂载目录", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsTextField(
                    label = "目录路径",
                    value = path,
                    onValueChange = { path = it },
                )
                SettingsTextField(
                    label = "挂载名称（可留空）",
                    value = name,
                    onValueChange = { name = it },
                )
                InfoLine("仅支持共享存储中的可读目录")
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(path, name) }) {
                Text("添加", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun SettingsPageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 10.dp,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingsSectionCard(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = SettingsCard,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, SettingsLine.copy(alpha = 0.8f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                if (description != null) {
                    Text(
                        description,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = SettingsMuted,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = SettingsMuted)
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardType = keyboardType,
        )
    }
}

@Composable
private fun SettingsSecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = SettingsMuted)
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                TextButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        if (revealed) "隐藏" else "显示",
                        fontSize = 9.sp,
                        color = SettingsBlue,
                    )
                }
            },
        )
    }
}

@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SettingsCardRaised.copy(alpha = 0.55f))
            .border(
                1.dp,
                if (focused) SettingsBlue.copy(alpha = 0.85f) else SettingsLine,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(SettingsBlue),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            placeholder,
                            fontSize = 12.sp,
                            color = SettingsMuted.copy(alpha = 0.55f),
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, fontSize = 9.sp, lineHeight = 11.sp, color = SettingsMuted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .width(46.dp)
                .height(30.dp)
                .scale(0.78f),
        )
    }
}

@Composable
private fun SegmentChoice(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (key, label) ->
            val active = key == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) SettingsBlue else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color(0xFF071321) else SettingsMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DropdownSetting(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    placeholder: String = "请选择",
    onPreview: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: placeholder
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SettingsCardRaised)
            .border(1.dp, SettingsLine, RoundedCornerShape(12.dp))
            .clickable { expanded = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 10.sp, color = SettingsMuted, modifier = Modifier.weight(1f))
        Text(
            selectedLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (options.isEmpty()) SettingsMuted else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text("⌄", fontSize = 12.sp, color = SettingsBlue)
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { expanded = false },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = SettingsCardRaised,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, SettingsLine),
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 360.dp)
                        .clickable { },
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(16.dp),
                        )
                        HorizontalDivider(color = SettingsLine)
                        options.forEach { (key, text) ->
                            val active = key == selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (active) SettingsBlue.copy(alpha = 0.11f)
                                        else Color.Transparent,
                                    )
                                    .padding(start = 16.dp, end = 5.dp),
                            ) {
                                Text(
                                    text,
                                    fontSize = 12.sp,
                                    color = if (active) SettingsBlue
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onSelect(key)
                                            expanded = false
                                        }
                                        .padding(vertical = 13.dp),
                                )
                                if (onPreview != null) {
                                    TextButton(onClick = { onPreview(key) }) {
                                        Text("试听", fontSize = 10.sp, color = SettingsBlue)
                                    }
                                }
                            }
                        }
                        if (options.isEmpty()) {
                            Text(
                                "没有可用选项",
                                fontSize = 11.sp,
                                color = SettingsMuted,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    state: String,
    active: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                state,
                fontSize = 9.sp,
                color = if (active) Color(0xFF59D6A8) else SettingsMuted,
            )
        }
        TextButton(
            onClick = onAction,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(action, fontSize = 10.sp, color = SettingsBlue)
        }
    }
}

@Composable
private fun StatusActionRow(
    title: String,
    value: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(value, fontSize = 9.sp, color = SettingsMuted, lineHeight = 13.sp)
        }
        TextButton(
            onClick = onAction,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(action, fontSize = 10.sp, color = SettingsBlue)
        }
    }
}

@Composable
private fun FilledAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SettingsBlue,
            contentColor = Color(0xFF06111F),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OutlineAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .height(36.dp)
            .border(1.dp, SettingsLine, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun InfoLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(SettingsBlue),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 9.sp, color = SettingsMuted, lineHeight = 13.sp)
    }
}

@Composable
private fun EmptySettingsState(
    marker: String,
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(1.dp, SettingsLine.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(SettingsBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(marker, color = SettingsBlue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(9.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(subtitle, fontSize = 9.sp, color = SettingsMuted)
    }
}

@Composable
private fun SettingsSaveBar(onSave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsBlack.copy(alpha = 0.98f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SettingsBlue,
                contentColor = Color(0xFF06111F),
            ),
        ) {
            Text(
                "保存并关闭",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun openSystemSettings(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
