package com.pilot.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.pilot.agent.Dbg
import com.pilot.agent.LogRedactor
import com.pilot.agent.action.CommandRoute
import com.pilot.agent.action.CommandSemantics
import com.pilot.agent.action.ScreenlessOutcome
import com.pilot.agent.action.ScreenlessTasks
import com.pilot.agent.action.ScreenRequirements
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import com.pilot.agent.assistant.AssistantFastPath
import com.pilot.agent.assistant.exec.FastPathTrace
import com.pilot.agent.assistant.exec.ResultFormatter
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.runtime.await
import com.pilot.agent.runtime.catchingNonCancel

/**
 * 后台触发入口：不开界面，直接让 agent 在当前前台应用上干活。
 *
 * 为什么需要它：agent 自己的界面在前台时，无障碍抓到的屏幕就是 agent 自己，
 * 什么也操作不了。系统级助理的正确形态是「常驻后台 + 被唤起」，而不是「开着 App 再去操作别人」。
 *
 * ## 安全（审核报告 code.md S1）
 * - manifest 在**所有构建类型**都要求 signature 级权限 `com.pilot.agent.permission.TRIGGER`
 *   （debug 包不再移除），第三方 App 无法投递；
 * - API 34+ 能拿到发送方 UID 时，再用 [TriggerAuth] 做签名比对；拿不到时不再冒充「自身 UID」；
 * - 只接受正式指令 `goal`。所有 adb 调试指令（沙箱 shell、改 Cookie、删记忆……）已迁到
 *   [DebugCommands]，只能从 debug 包的 `DebugTriggerActivity` 别名、并在显式开关打开后使用；
 * - 日志不记录用户原话（[LogRedactor]）。
 *
 * 用法（同签名 App / 系统组件）：
 *   am broadcast -a com.pilot.agent.RUN -p com.pilot.agent --es goal "打开设置"
 * adb 调试请改用：
 *   adb shell am start -n com.pilot.agent/.ui.DebugTriggerActivity --es goal "打开设置"
 *   （需先打开调试开关，见 DebugCommands）
 */
class RunReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // API 34+：sentFromUid 只有在发送方共享身份时才有值，否则是 INVALID_UID。
        // 低版本没有可靠来源（onReceive 不在发送方的 Binder 事务里，getCallingUid() 拿到的是自己）。
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid
        else Process.INVALID_UID
        val caller = TriggerAuth.classify(context, uid)
        if (caller is TriggerAuth.Caller.Rejected) {
            Dbg.w(TAG, "拒绝来自 uid=${caller.uid} 的触发请求")
            return
        }

        if (DebugCommands.hasDebugExtra(intent)) {
            Dbg.w(TAG, "正式触发入口不接受调试指令，已忽略（调试请用 debug 包的 DebugTriggerActivity）")
            return
        }

        val goal = intent.getStringExtra("goal")?.trim().orEmpty()
        if (goal.isEmpty()) {
            Dbg.w(TAG, "触发请求缺少 goal")
            return
        }
        dispatchBackgroundGoal(context, goal, "broadcast ${TriggerAuth.label(caller)}")
    }
}

/**
 * 非操作类问题的模型回答（云端优先，端侧兜底）。
 *
 * 走 AgentRuntime 里同一套提示词，保证语音路径和广播路径回答一致。
 */
private suspend fun answerByModel(text: String): String {
        val s = com.pilot.agent.agent.AgentSettings.load()
        val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val memory = AgentRuntime.localTools.memory.promptContext(text, 5)
        val memoryPrompt = if (memory.isBlank()) "" else "\n相关长期记忆：\n$memory"
        // 云端（可取消：点停止会立刻断开底层请求，不再吞取消信号）
        if (s.cloudReady) {
            catchingNonCancel {
                val body = org.json.JSONObject().apply {
                    put("model", s.model)
                    put("stream", false)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "你是手机助手 Pilot，运行在这台安卓手机上。现在是 $now。" +
                                    "用一到两句话回答，简短口语化，不要用列表。" +
                                    "你能开关应用、查天气、股票和火车余票、播音乐、跑 shell 命令、做导航。" +
                                    com.pilot.agent.agent.IntentGate.SELF_KNOWLEDGE +
                                    memoryPrompt,
                            )
                        })
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        })
                    })
                }.toString()
                val req = okhttp3.Request.Builder()
                    .url(s.baseUrl.trimEnd('/') + "/chat/completions")
                    .addHeader("Authorization", "Bearer ${s.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                    .build()
                com.pilot.agent.agent.CloudHttp.client.newCall(req).await().use { resp ->
                    if (resp.isSuccessful) {
                        org.json.JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("message")?.optString("content")
                    } else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim().take(200) }
        }
        // 端侧兜底
        val llm = AgentRuntime.localLlm
        if (!llm.ensureReady()) return "我暂时没法回答。"
        val noThink = if (llm.wantsNoThink) "/no_think" else ""
        return llm.generate(
            "system\n你是手机助手 Pilot，运行在这台安卓手机上，现在是 $now。" +
                "能开关应用、查天气股票和火车余票、播音乐、跑命令、做导航。" +
                com.pilot.agent.agent.IntentGate.SELF_KNOWLEDGE +
                memoryPrompt +
                "回答简短口语化，不超过 25 个字。$noThink\n" +
                "user\n$text\nassistant\n"
        )?.trim()?.take(200) ?: "我暂时没法回答。"
}

/**
 * 后台执行一条自然语言指令（无人确认）。
 *
 * 广播入口与后台语音共用这一条链，避免两套行为不一致：
 * 快路径 → 只读本地工具 → 意图门（查询/闲聊交给模型）→ Agent 循环。
 *
 * @param callerLabel 仅用于日志/追踪，标明是谁触发的
 */
internal fun dispatchBackgroundGoal(
    context: Context,
    goal: String,
    callerLabel: String = "background",
) {
    // 助理快路径：规则选能力 → 确定性抽参 → 本机执行，不调云端。
    // 接管了就不走下面的遗留匹配器，避免同一个请求被执行两遍。
    // 需要端侧模型补值的请求在无界面场景不处理（保持确定性），照旧走原链路。
    Dbg.i(TAG, "dispatch plan begin ${LogRedactor.text("goal", goal)} caller=$callerLabel")
    val fastPlan = AssistantFastPath.plan(context, goal, callerLabel)
    Dbg.i(
        TAG,
        "dispatch plan result=${fastPlan?.let { "${it.capability}/${it.action}" } ?: "null"}",
    )
    val fastInvocation = fastPlan?.invocation
    if (fastPlan != null && fastInvocation != null) {
        // 破坏性能力（HIGH / 路由强制确认）在后台默认不执行，
        // 但用户原话已明确说出操作对象、执行器又标了 explicitAuthorized 的能力
        // （记住/忘掉/建提醒/建定时）例外 —— 再让用户「去界面说一次」等于
        // 把同一条明确指令说两遍，实测会被当成没听懂。文件覆盖/删除没有这个标记，
        // 仍然保持后台拒绝。
        if (fastPlan.needsConfirmation) {
            if (!fastInvocation.explicitAuthorized) {
                Dbg.w(TAG, "后台无人确认，拒绝破坏性快路径：${fastPlan.capability}/${fastPlan.action}")
                AgentRuntime.speaker.speak("这个操作需要你先确认，请在界面里说一次。")
                return
            }
            Dbg.i(TAG, "后台执行已明确授权的破坏性操作：${fastPlan.capability}/${fastPlan.action}")
        }
        val capability = fastPlan.capability
        val action = fastPlan.action
        val args = fastPlan.args
        Thread {
            val outcome = runCatching {
                runBlocking {
                    AgentRuntime.localTools.execute(fastInvocation) { question, _ ->
                        Dbg.w(TAG, "快路径无人确认，已拒绝：${LogRedactor.text("question", question)}")
                        false
                    }
                }
            }
            val result = outcome.getOrNull()
            if (result != null) {
                val reply = ResultFormatter.format(capability, action, args, result)
                FastPathTrace.handled(
                    context,
                    capability,
                    action,
                    result.status.name,
                    reply.text.length,
                )
                AgentRuntime.speaker.speak(reply.spoken)
            } else {
                // 结果未知：可能执行了一半，不能说成功也不能说没做。
                Dbg.e(
                    TAG,
                    "快路径没有返回结果：${capability}/${action} " +
                        "err=${LogRedactor.error(outcome.exceptionOrNull())}",
                )
                AgentRuntime.speaker.speak(UNKNOWN_RESULT_SPOKEN)
            }
        }.apply { name = "fast-path"; isDaemon = true }.start()
        return
    }

    // 本地工具兜底：只读类（网络/定位/蓝牙/使用时长、列目录等）在后台无人确认
    // 也可以安全执行；写/删/分享仍按原链路处理，绝不在后台静默执行。
    val localInvocation = AgentRuntime.localTools.match(goal)
    if (localInvocation != null &&
        AgentRuntime.localTools.policyFor(localInvocation.group) ==
        com.pilot.agent.tool.ToolPolicy.ALLOW
    ) {
        Thread {
            val outcome = runCatching {
                runBlocking {
                    AgentRuntime.localTools.execute(localInvocation) { question, _ ->
                        Dbg.w(TAG, "本地工具无人确认，已拒绝：${LogRedactor.text("question", question)}")
                        false
                    }
                }
            }
            val result = outcome.getOrNull()
            if (result != null) {
                AgentRuntime.speaker.speak(result.spoken)
            } else {
                Dbg.e(
                    TAG,
                    "本地工具没有返回结果：${localInvocation.name} " +
                        "err=${LogRedactor.error(outcome.exceptionOrNull())}",
                )
                AgentRuntime.speaker.speak(UNKNOWN_RESULT_SPOKEN)
            }
        }.apply { name = "local-tool"; isDaemon = true }.start()
        return
    }

    // 自动路由：快路径有规则就本机直执行；没有规则的请求交给 Agent，
    // 由模型决定回答还是执行。遗留匹配器不再插在中间。

    // ★ 意图判定：和界面侧保持一致，先让云端模型判断「回答还是执行」，
    //   关键词 IntentGate 只在云端不可用时兜底。否则「需要执行」的话被关键词
    //   误判成闲聊，就只回一句话、什么都不做。
    //   判定要联网，必须在后台线程跑，不能阻塞广播回调（10 秒 ANR）。
    Thread {
        val kind = com.pilot.agent.agent.IntentGate.classify(goal)
        // ★ 强查询（新闻/资讯/网页搜索，且未指定 App、未要求“给我看”）优先走查询通道，
        //   不让模型的「回答/执行」判定把它变成打开浏览器去搜。规则优先。
        if (com.pilot.agent.agent.IntentGate.isStrongQuery(goal)) {
            Dbg.i(TAG, "强查询优先走查询通道，跳过模型判定：${LogRedactor.text("goal", goal)}")
            val answer = runCatching {
                runBlocking { com.pilot.agent.query.QueryEngine.handle(goal) }
            }.getOrNull()
                ?: "没能查到「$goal」的最新结果，可能是网络不可用或暂时没有相关内容。"
            val summary = com.pilot.agent.query.QueryEngine.speechSummary(answer)
            if (summary != null) AgentRuntime.speaker.speakExpanded(summary)
            else AgentRuntime.speaker.speak(answer)
            return@Thread
        }
        val settings = com.pilot.agent.agent.AgentSettings.load()
        val decision = if (settings.cloudReady) {
            val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date())
            val memory = runCatching {
                AgentRuntime.localTools.memory.promptContext(goal, 5)
            }.getOrDefault("")
            runCatching {
                runBlocking {
                    com.pilot.agent.agent.AgentIntentRouter {
                        com.pilot.agent.agent.AgentSettings.load()
                    }.decide(goal, emptyList(), memory, now)
                }
            }.getOrNull()
        } else {
            null
        }

        if (decision != null) {
            if (decision.execute) {
                Dbg.i(TAG, "后台路由 → 执行 ${LogRedactor.text("thought", decision.thought)}")
                executeBackgroundTask(context, goal, callerLabel)
                return@Thread
            }
            val answer = decision.answer?.trim().orEmpty()
            if (answer.isNotBlank()) {
                Dbg.i(TAG, "后台路由 → 回答 chars=${answer.length} ${LogRedactor.text("thought", decision.thought)}")
                AgentRuntime.speaker.speak(answer)
                return@Thread
            }
        }

        Dbg.i(TAG, "后台路由云端不可用或未给出结论（$kind），关键词兜底")
        answerOrExecuteByKeyword(context, goal, kind, callerLabel)
    }.apply { name = "background-route"; isDaemon = true }.start()
}

/**
 * 关键词兜底：QUERY/CHAT 交给模型回答，TASK 交给 Agent 执行。
 *
 * 「你是谁」这类问题进 loop 会去乱点屏幕（真机实测过一句话触发 3 次乱点），
 * 所以闲聊必须先在这里拦下。QUERY 类（查上证指数）优先走 QueryEngine：更快、数据更准。
 * 注意：本函数运行在后台线程，内部可以放心使用 runBlocking。
 */
private fun answerOrExecuteByKeyword(
    context: Context,
    goal: String,
    kind: com.pilot.agent.agent.IntentGate.Kind,
    callerLabel: String,
) {
    if (kind == com.pilot.agent.agent.IntentGate.Kind.QUERY) {
        val answer = runCatching {
            runBlocking { com.pilot.agent.query.QueryEngine.handle(goal) }
        }.getOrNull()
        if (!answer.isNullOrBlank()) {
            val spokenSummary = com.pilot.agent.query.QueryEngine.speechSummary(answer)
            if (spokenSummary != null) AgentRuntime.speaker.speakExpanded(spokenSummary)
            else AgentRuntime.speaker.speak(answer)
        } else {
            AgentRuntime.speaker.speak(
                runCatching { runBlocking { answerByModel(goal) } }
                    .getOrDefault("我暂时没法回答。"),
            )
        }
        return
    }
    if (kind != com.pilot.agent.agent.IntentGate.Kind.TASK) {
        Dbg.i(TAG, "非操作指令（$kind），交给模型回答：${LogRedactor.text("goal", goal)}")
        val reply = runCatching { runBlocking { answerByModel(goal) } }
            .getOrDefault("我暂时没法回答。")
        AgentRuntime.speaker.speak(reply)
        return
    }
    executeBackgroundTask(context, goal, callerLabel)
}

/**
 * 后台通道执行一条命令（m-20261127-106）。
 *
 * 与 UI 共用 [ScreenRequirements.route] 的判定：
 * - NONE 类（日程/提醒/待办/查询/音乐/B 类）直接本机办，回执必须播报出去；
 * - C 类（命令本身做不到）如实说明，不赖无障碍；
 * - SCREEN 类才因缺操作权限被拒；
 * - 权限就绪时才交给 Agent 循环（后台无人确认，ui=false）。
 */
private fun executeBackgroundTask(context: Context, goal: String, callerLabel: String) {
    val loopReady = AgentRuntime.loop != null
    when (ScreenRequirements.route(goal, loopReady)) {
        CommandRoute.IMPOSSIBLE -> {
            val cmd = CommandSemantics.classify(goal)
            val text = cmd?.let { CommandSemantics.needsUserText(it, askGuide = false) }
                ?: "这个操作我做不到。"
            Dbg.i(TAG, "后台 C 类命令如实说明：${LogRedactor.text("goal", goal)}")
            AgentRuntime.speaker.speak(text)
            return
        }

        CommandRoute.LOCAL -> {
            val outcome = if (ScreenlessTasks.canRun(goal)) {
                runCatching { runBlocking { ScreenlessTasks.run(context, goal) } }.getOrNull()
            } else {
                null
            }
            when (outcome) {
                is ScreenlessOutcome.Done -> {
                    Dbg.i(TAG, "后台本机执行 → 回执：${LogRedactor.text("goal", goal)}")
                    speakBackgroundReceipt(goal, outcome.message)
                    postBackgroundReceipt(context, goal, outcome.message)
                    return
                }
                is ScreenlessOutcome.NeedsGuide -> {
                    Dbg.i(TAG, "后台本机执行 → 缺权限说明：${LogRedactor.text("goal", goal)}")
                    AgentRuntime.speaker.speak(outcome.message)
                    postBackgroundReceipt(context, goal, outcome.message)
                    return
                }
                null -> Unit // 本机接不了，按原链路继续
            }
            if (!loopReady) {
                Dbg.e(TAG, "后台本机接不了且无障碍未连接：${LogRedactor.text("goal", goal)}")
                AgentRuntime.speaker.speak("这条命令这次没能在后台执行，请检查 Pilot 的运行状态后再试。")
                return
            }
        }

        CommandRoute.NEEDS_SCREEN -> {
            Dbg.e(TAG, "后台要读屏但无障碍未连接：${LogRedactor.text("goal", goal)}")
            AgentRuntime.speaker.speak(
                "这个动作要读屏幕，Pilot 的操作权限没有开启，后台没法执行。开启后再试。",
            )
            return
        }

        CommandRoute.AGENT -> Unit
    }

    val loop = AgentRuntime.loop
    if (loop == null) {
        // route 已兜过缺权限，这里只防御竞态（判定后服务刚好断开）。
        Dbg.e(TAG, "无障碍服务未连接，无法执行：${LogRedactor.text("goal", goal)}")
        AgentRuntime.speaker.speak("这个动作暂时没法在后台执行，请检查 Pilot 的操作权限后再试。")
        return
    }
    Dbg.i(TAG, "background run ${LogRedactor.text("goal", goal)} caller=$callerLabel")
    loop.start(
        AgentRuntime.scope,
        goal,
        ui = false,
        executionMode = com.pilot.agent.agent.AssistantMode.AGENT,
    )
}

/** 后台回执落点：查询类长答案走扩展播报，其余直接播；不能静默成功。 */
private fun speakBackgroundReceipt(goal: String, message: String) {
    val summary = if (com.pilot.agent.query.QueryEngine.canHandle(goal)) {
        com.pilot.agent.query.QueryEngine.speechSummary(message)
    } else {
        null
    }
    if (summary != null) AgentRuntime.speaker.speakExpanded(summary)
    else AgentRuntime.speaker.speak(message)
}

/**
 * 后台回执的持久落点（m-20261127-106）：播报可能被静音/锁屏错过，
 * 再用一条低优先级通知把「做了什么、结果是什么」留在通知栏。
 */
private fun postBackgroundReceipt(context: Context, goal: String, message: String) {
    runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (manager.getNotificationChannel(CHANNEL_RECEIPT) == null) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_RECEIPT,
                    "后台回执",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        manager.notify(
            goal.hashCode(),
            androidx.core.app.NotificationCompat.Builder(context, CHANNEL_RECEIPT)
                .setSmallIcon(com.pilot.agent.R.drawable.ic_pilot)
                .setContentTitle("Pilot 已完成")
                .setContentText(message.take(120))
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText("$goal\n$message"),
                )
                .setAutoCancel(true)
                .build(),
        )
    }.onFailure { Dbg.w(TAG, "后台回执通知失败：${LogRedactor.error(it)}") }
}

/** 供后台语音（界面已销毁、没有 ViewModel）复用同一套执行链。 */
fun runBackgroundGoal(context: Context, goal: String, callerLabel: String = "voice") =
    dispatchBackgroundGoal(context, goal, callerLabel)

private const val TAG = "Pilot"

/** 后台回执通知渠道（低优先级，不打扰）。 */
private const val CHANNEL_RECEIPT = "pilot_background_receipt"

/** 后台执行拿不到结果时的如实播报：不声称成功，也不声称没做。 */
private const val UNKNOWN_RESULT_SPOKEN = "这次操作没有拿到执行结果，不确定是否已经完成，请看一下手机确认。"
