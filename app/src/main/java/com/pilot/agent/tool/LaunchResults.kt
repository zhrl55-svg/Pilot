package com.pilot.agent.tool

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.pilot.agent.Dbg
import com.pilot.agent.action.LaunchOutcome
import com.pilot.agent.action.VerifiedLauncher

/**
 * 把“打开某个页面”的结果如实翻译成 [ToolResult]。
 *
 * 所有 startActivity 都经由 [VerifiedLauncher]：它启动后回查前台/事件流，
 * 没确认就返回 Unknown。这里绝不把 Unknown 说成“已打开”。
 */
object LaunchResults {

    private const val TAG = "PilotLaunch"

    /** 系统选择器 / 解析器的包名：它们出现在前台不代表目标 App 打开了，期望包名置空交给前台变化判断。 */
    private val RESOLVER_PACKAGES = setOf("android", "com.android.intentresolver", "com.huawei.android.internal.app")

    /**
     * 解析这个 Intent 实际会落到哪个包；解析不到或是系统选择器时返回 null。
     */
    fun expectedPackage(context: Context, intent: Intent): String? {
        intent.`package`?.let { return it }
        intent.component?.packageName?.let { return it }
        val resolved = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (t: Throwable) {
            null
        } ?: return null
        val pkg = resolved.activityInfo?.packageName ?: return null
        return pkg.takeUnless { it in RESOLVER_PACKAGES }
    }

    /**
     * @param label 用户能听懂的目标名，例如“日历新建页面”“设置”
     * @param okMessage 已确认打开时的文案
     * @param okSpoken 已确认打开时的播报
     */
    suspend fun launch(
        context: Context,
        intent: Intent,
        label: String,
        okMessage: String,
        okSpoken: String = okMessage,
        expectPackage: String? = expectedPackage(context, intent),
        timeoutMs: Long = 1_500L,
    ): ToolResult {
        if ((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val outcome = VerifiedLauncher.launch(context, intent, expectPackage, timeoutMs)
        return toResult(outcome, label, okMessage, okSpoken)
    }

    fun toResult(
        outcome: LaunchOutcome,
        label: String,
        okMessage: String,
        okSpoken: String = okMessage,
    ): ToolResult = when (outcome) {
        is LaunchOutcome.Ok -> ToolResult.success(okMessage, okSpoken)
        is LaunchOutcome.Blocked -> {
            Dbg.w(TAG, "打开「$label」被拦截：${outcome.reason}")
            ToolResult.error(
                "系统拦下了打开「$label」（${outcome.reason.take(60)}）。请先回到 Pilot 或解锁手机后再试一次。",
            )
        }
        is LaunchOutcome.Failed -> {
            Dbg.w(TAG, "打开「$label」失败：${outcome.error.javaClass.simpleName}")
            if (outcome.error is ActivityNotFoundException) {
                ToolResult.error("没有找到能打开「$label」的应用。")
            } else {
                ToolResult.error("打开「$label」失败：${outcome.error.message?.take(80) ?: outcome.error.javaClass.simpleName}")
            }
        }
        is LaunchOutcome.Unknown -> ToolResult.unknown(
            "已发出打开「$label」的请求，但没能确认它已经出现在屏幕上（可能被系统的后台限制拦下）。如果没看到，请解锁手机或回到 Pilot 后再说一次。",
            "已经请求打开$label，但我没能确认它打开了，没看到的话请再说一次。",
        )
        else -> ToolResult.unknown(
            "已发出打开「$label」的请求，结果未确认。",
            "已经请求打开$label，结果还没确认。",
        )
    }
}
