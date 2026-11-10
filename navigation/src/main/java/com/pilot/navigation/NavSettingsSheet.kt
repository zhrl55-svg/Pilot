package com.pilot.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 导航设置面板（Compose 版）。
 *
 * ## 为什么用 Compose
 * 老板明确要求「用主界面菜单的开关样式」。
 * 主界面是 Compose + Material3，所以设置面板也必须是 Compose，
 * 才能用同一个 `Switch` 组件、同一套主题色。
 *
 * 传统 View 的 `android.widget.Switch` 在这个 ROM 的深色主题下
 * 轨道和背景同色，实测**完全看不见**（试过 tint 也压不住）。
 *
 * ## 布局
 * - 播报：三档单选（详细/标准/简洁）
 * - 导航行为：四个 Switch
 * - 常去地点：列表 + 添加
 * - 导航历史：列表
 */
@Composable
fun NavSettingsDialog(
    settings: NavigationSettings.NavSettings,
    onDismiss: () -> Unit,
    onLevelChange: (Int) -> Unit,
    onToggle: (key: String, value: Boolean) -> Unit,
    onAddFavorite: () -> Unit,
    onDeleteFavorite: (NavigationSettings.Favorite) -> Unit,
    onFavoriteClick: (NavigationSettings.Favorite) -> Unit,
    onDeleteHistory: (NavigationSettings.HistoryEntry) -> Unit,
    onHistoryClick: (NavigationSettings.HistoryEntry) -> Unit,
) {
    val levels = listOf("详细", "标准", "简洁")
    NavTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("完成") }
            },
            title = { Text("导航设置", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ---------- 播报 ----------
                    SectionTitle("播报")
                    levels.forEachIndexed { i, name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLevelChange(i) }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(
                                selected = settings.broadcastLevel == i,
                                onClick = { onLevelChange(i) },
                            )
                            Text(name, fontSize = 17.sp)
                        }
                    }

                    // ---------- 导航行为 ----------
                    SectionTitle("导航行为")
                    SettingSwitch("到达后自动退出", settings.arriveAutoExit) {
                        onToggle("arriveAutoExit", it)
                    }
                    SettingSwitch("导航时屏幕常亮", settings.keepScreenOn) {
                        onToggle("keepScreenOn", it)
                    }
                    SettingSwitch("优先躲避拥堵", settings.avoidJam) {
                        onToggle("avoidJam", it)
                    }
                    SettingSwitch("自动缩放地图", settings.autoZoom) {
                        onToggle("autoZoom", it)
                    }

                    // ---------- 常去地点 ----------
                    SectionTitle("常去地点")
                    if (settings.favorites.isEmpty()) {
                        HintText("还没有常去地点。说「导航回家」时会用到。")
                    } else {
                        settings.favorites.forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onFavoriteClick(f) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.label, fontSize = 16.sp)
                                    Text(
                                        f.address,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onDeleteFavorite(f) }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = onAddFavorite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("＋ 添加常去地点", fontSize = 15.sp) }

                    // ---------- 导航历史 ----------
                    SectionTitle("导航历史")
                    if (settings.history.isEmpty()) {
                        HintText("还没有导航记录。")
                    } else {
                        settings.history.take(8).forEach { h ->
                            val title = if (h.alias.isNotBlank()) "${h.alias}（${h.name}）" else h.name
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onHistoryClick(h) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDeleteHistory(h) }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/**
 * 设置项 + Material3 Switch（和主界面菜单同款）。
 */
@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember(checked) { mutableStateOf(checked) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                on = !on
                onChange(on)
            }
            .padding(vertical = 4.dp),
    ) {
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                onChange(it)
            },
        )
    }
}
