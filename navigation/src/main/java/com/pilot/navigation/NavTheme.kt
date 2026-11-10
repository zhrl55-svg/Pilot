package com.pilot.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 导航模块的 Compose 主题 —— **和主界面的 PilotTheme 完全一致**。
 *
 * ## 为什么要复制一份
 * 主界面的 `PilotTheme` 在 app 模块里（`com.pilot.agent.ui.Theme`），
 * navigation 是独立模块，不能反向依赖 app（模块依赖是 app → navigation）。
 *
 * 所以把色值**抄一份**在这里。改主界面主题时这里要同步——
 * 注释里标了来源，方便对照。
 *
 * ## 为什么需要 Compose
 * 设置面板的开关要用 Material3 的 `Switch`，跟主界面菜单的开关**长得一样**
 * （老板明确要求「用主界面菜单的开关样式」）。
 * 传统 View 的 `android.widget.Switch` 在这个 ROM 的深色主题下几乎看不见，
 * 试过 thumbTintList/trackTintList 也不生效。
 */
private val NavColorScheme = darkColorScheme(
    primary = Color(0xFF6FD3C0),
    onPrimary = Color(0xFF06201C),
    primaryContainer = Color(0xFF13332E),
    onPrimaryContainer = Color(0xFF9BE8D8),
    secondary = Color(0xFF8AA0B8),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE7EBF2),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE7EBF2),
    surfaceVariant = Color(0xFF1E242D),
    onSurfaceVariant = Color(0xFF9AA6B5),
    outline = Color(0xFF33404F),
    error = Color(0xFFEF7A6D),
    onError = Color(0xFF2A0B08),
)

@Composable
fun NavTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NavColorScheme, content = content)
}
