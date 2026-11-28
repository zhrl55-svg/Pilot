package com.pilot.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.pilot.agent.agent.AgentSettings

/**
 * Pilot 的语义色板。
 *
 * 两套配色对应设计稿的 B（明）/ C（暗）：
 *  · 明色「暖白纸感」—— 暖白底 + 陶土橙强调，18px 大圆角，长时间阅读更柔和
 *  · 暗色「深空聚焦」—— 近黑非纯黑底 + 亮蓝强调，降低暗环境眩光
 *
 * 界面里除了 Material 的 colorScheme，还有一批 Material 不覆盖的层级
 * （卡片底、浮起面、极细分隔线、弱化文字），统一收在这里，
 * 保证明暗切换时不会漏掉某个硬编码色。
 */
@Immutable
data class PilotColors(
    val isDark: Boolean,
    /** 页面主背景 */
    val background: Color,
    /** 卡片 / 对话框表面 */
    val surface: Color,
    /** 浮起面：输入框、次级卡片、气泡底 */
    val surfaceRaised: Color,
    /** 描边 / 分隔线 */
    val line: Color,
    /** 唯一强调色（按钮、链接、焦点、字标） */
    val accent: Color,
    /** 强调色上的前景（按钮文字与图标） */
    val onAccent: Color,
    /** 主文字 */
    val onSurface: Color,
    /** 正文级次要文字 */
    val onSurfaceVariant: Color,
    /** 弱化说明文字（标注、单位、辅助信息） */
    val muted: Color,
    /** 成功 */
    val success: Color,
    /** 警告（影子态、需注意） */
    val warning: Color,
    /** 错误 / 危险 / 停止 */
    val danger: Color,
)

/** 明色 · 暖白纸感（方案 B） */
val PilotLightColors = PilotColors(
    isDark = false,
    background = Color(0xFFFCFAF7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF5EFE9),
    line = Color(0xFFEBE3D8),
    accent = Color(0xFFC2643F),
    onAccent = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2C2622),
    onSurfaceVariant = Color(0xFF5C5148),
    muted = Color(0xFF8B7B6D),
    success = Color(0xFF6E7A46),
    warning = Color(0xFFA96B12),
    danger = Color(0xFFB4433A),
)

/** 暗色 · 深空聚焦（方案 C） */
val PilotDarkColors = PilotColors(
    isDark = true,
    background = Color(0xFF111318),
    surface = Color(0xFF1B1E26),
    surfaceRaised = Color(0xFF232838),
    line = Color(0xFF2E323C),
    accent = Color(0xFF6E8BFF),
    onAccent = Color(0xFF0B1224),
    onSurface = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFFD4D4D8),
    muted = Color(0xFFA1A1AA),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFE0B34C),
    danger = Color(0xFFF87171),
)

val LocalPilotColors = staticCompositionLocalOf { PilotDarkColors }

/** 取当前主题的语义色板：`pilotColors.accent`。 */
val pilotColors: PilotColors
    @Composable
    get() = LocalPilotColors.current

private fun lightScheme(c: PilotColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accent.copy(alpha = 0.12f),
    onPrimaryContainer = c.accent,
    secondary = c.onSurfaceVariant,
    onSecondary = c.surface,
    background = c.background,
    onBackground = c.onSurface,
    surface = c.surface,
    onSurface = c.onSurface,
    surfaceVariant = c.surfaceRaised,
    onSurfaceVariant = c.muted,
    outline = c.line,
    error = c.danger,
    onError = Color.White,
)

private fun darkScheme(c: PilotColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accent.copy(alpha = 0.18f),
    onPrimaryContainer = c.onSurface,
    secondary = c.onSurfaceVariant,
    onSecondary = c.surface,
    background = c.background,
    onBackground = c.onSurface,
    surface = c.surface,
    onSurface = c.onSurface,
    surfaceVariant = c.surfaceRaised,
    onSurfaceVariant = c.muted,
    outline = c.line,
    error = c.danger,
    onError = c.onAccent,
)

/**
 * 主题入口。
 *
 * @param themeMode [AgentSettings.THEME_AUTO] 跟随系统深浅色；
 *   [AgentSettings.THEME_LIGHT] 强制明色；[AgentSettings.THEME_DARK] 强制暗色。
 */
@Composable
fun PilotTheme(
    themeMode: String = AgentSettings.THEME_AUTO,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        AgentSettings.THEME_LIGHT -> false
        AgentSettings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) PilotDarkColors else PilotLightColors
    CompositionLocalProvider(LocalPilotColors provides palette) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            content = content,
        )
    }
}
