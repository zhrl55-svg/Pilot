package com.pilot.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilot.agent.tool.ScheduledTaskManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 定时任务面板（顶部 ⋮ 菜单入口）。
 *
 * 之前定时任务只能靠语音「取消定时任务 播报天气」这类模糊措辞管理，
 * 用户看不到到底排了什么、改时间全靠重新下指令。这里给出可视化列表，
 * 支持新建 / 改时间 / 改内容 / 切换每日 / 删除。
 */
@Composable
fun ScheduledTaskDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<ScheduledTaskManager.Task?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScheduledTaskManager.Task?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pilotColors.surface,
        title = { Text("定时任务 ${vm.scheduledTaskList.size} 条", fontSize = 16.sp) },
        text = {
            if (vm.scheduledTaskList.isEmpty()) {
                Text(
                    "还没有定时任务。\n\n可以对小派说「每天 8 点播报天气」，或在这里新建。",
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.scheduledTaskList, key = { it.id }) { task ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(task.goal, fontSize = 13.sp, lineHeight = 18.sp)
                            Text(
                                (if (task.daily) "每天 " else "") + formatTaskTime(task.at),
                                fontSize = 11.sp,
                                color = colors.onSurfaceVariant,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { editing = task }) {
                                    Text("编辑", fontSize = 12.sp)
                                }
                                TextButton(onClick = { pendingDelete = task }) {
                                    Text("删除", fontSize = 12.sp, color = colors.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creating = true }) { Text("新建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    if (creating) {
        TaskEditDialog(
            title = "新建定时任务",
            initial = null,
            onDismiss = { creating = false },
            onSave = { goal, at, daily ->
                vm.createScheduledTask(goal, at, daily)
                creating = false
            },
        )
    }

    editing?.let { task ->
        TaskEditDialog(
            title = "编辑定时任务",
            initial = task,
            onDismiss = { editing = null },
            onSave = { goal, at, daily ->
                vm.updateScheduledTask(task.id, goal, at, daily)
                editing = null
            },
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = pilotColors.surface,
            title = { Text("删除这个定时任务？") },
            text = { Text("${task.goal}\n${if (task.daily) "每天 " else ""}${formatTaskTime(task.at)}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteScheduledTask(task.id)
                    pendingDelete = null
                }) { Text("删除", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 新建 / 编辑表单。
 *
 * 时间用「MM-dd HH:mm」文本输入而不是 DatePicker：改一个定时任务通常只挪几十分钟，
 * 打一行字比翻两套选择器快；格式不合法时明确报错，不静默丢弃。
 */
@Composable
private fun TaskEditDialog(
    title: String,
    initial: ScheduledTaskManager.Task?,
    onDismiss: () -> Unit,
    onSave: (goal: String, at: Long, daily: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var goal by remember {
        mutableStateOf(initial?.goal.orEmpty())
    }
    var timeText by remember {
        mutableStateOf(initial?.let { formatTaskTime(it.at) } ?: "")
    }
    var daily by remember { mutableStateOf(initial?.daily ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pilotColors.surface,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it; error = null },
                    label = { Text("任务内容", fontSize = 12.sp) },
                    placeholder = { Text("例如：播报天气", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; error = null },
                    label = { Text("执行时间", fontSize = 12.sp) },
                    supportingText = {
                        Text(
                            error ?: "格式 MM-dd HH:mm，例如 11-24 08:00",
                            fontSize = 11.sp,
                            color = if (error != null) colors.error else colors.onSurfaceVariant,
                        )
                    },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("每天重复", fontSize = 13.sp)
                    Switch(checked = daily, onCheckedChange = { daily = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (goal.isBlank()) {
                    error = "任务内容不能为空"
                    return@TextButton
                }
                val at = parseTaskTime(timeText)
                if (at == null) {
                    error = "时间格式不对，示例 11-24 08:00"
                    return@TextButton
                }
                onSave(goal.trim(), at, daily)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 「MM-dd HH:mm」→ epoch 毫秒；今年已过则按明年算。解析失败返回 null。 */
internal fun parseTaskTime(text: String): Long? {
    // 中文输入法常把冒号转成全角「：」，空格转全角「　」——先归一化，
    // 否则用户明明输入对了格式却提示格式错误。
    val cleaned = text.trim()
        .replace('：', ':')
        .replace('　', ' ')
        .replace(Regex("\\s+"), " ")
    // SimpleDateFormat 默认 lenient：会把 "13-45 99:99" 滚成别的日期，
    // 静默接受等于把用户输入丢掉。必须先按格式严格核对每个字段。
    val m = Regex("""^(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})$""").find(cleaned) ?: return null
    val month = m.groupValues[1].toInt()
    val day = m.groupValues[2].toInt()
    val hour = m.groupValues[3].toInt()
    val minute = m.groupValues[4].toInt()
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null

    val parsed = runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).parse(cleaned)
    }.getOrNull() ?: return null
    val cal = Calendar.getInstance(Locale.CHINA).apply { time = parsed }
    // 真实日历校验：2 月 31 日这类会被 lenient 滚到 3 月，用户看着不像自己输入的日期
    if (cal.get(Calendar.MONTH) + 1 != month || cal.get(Calendar.DAY_OF_MONTH) != day) return null
    // SimpleDateFormat 会把缺省的年份补成 1970，这里对齐到最近的未来
    val now = Calendar.getInstance(Locale.CHINA)
    if (cal.get(Calendar.YEAR) < now.get(Calendar.YEAR)) {
        cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
        if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.YEAR, 1)
    } else if (cal.timeInMillis <= now.timeInMillis) {
        // 今年同日但已过 → 顺延到明天（一次性任务给个近期时间更符合直觉）
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
}

private fun formatTaskTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
