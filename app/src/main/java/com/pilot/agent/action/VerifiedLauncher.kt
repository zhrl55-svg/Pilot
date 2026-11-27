package com.pilot.agent.action

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.pilot.agent.Dbg
import kotlinx.coroutines.delay

/**
 * 启动 Activity 的结果。
 *
 * - [Ok]：有证据表明目标界面已到前台（无障碍回查到目标包 / 窗口指纹变化 / 本进程界面被盖住）。
 * - [Blocked]：系统明确拒绝（SecurityException），或可以回查但整个等待期内前台**完全没变化**
 *   （HarmonyOS / Android 10+ 后台启动拦截是静默的，这就是它的唯一可观测迹象；
 *   冷启动极慢时也可能出现，文案请写「可能被系统拦截」）。
 * - [Unknown]：没有回查能力（无障碍未连接且本 App 不在前台），或前台变到了非目标包。
 *   **不得**当成功播报，要如实告诉用户「结果未知，请看一下手机」。
 * - [Failed]：没有 Activity 能处理 / 启动抛异常。
 */
sealed class LaunchOutcome {
    object Ok : LaunchOutcome() {
        override fun toString(): String = "Ok"
    }

    data class Blocked(val reason: String) : LaunchOutcome()

    object Unknown : LaunchOutcome() {
        override fun toString(): String = "Unknown"
    }

    data class Failed(val error: Throwable) : LaunchOutcome()

    val isOk: Boolean get() = this === Ok
}

/**
 * 带回查的 Activity 启动器（审核 D-01 / P0-5）。
 *
 * ★ HarmonyOS / EMUI / Android 10+ 后台拦截 Activity 启动时 `startActivity` **不抛异常**，
 *   `runCatching { startActivity }.isSuccess` 永远是 true——这是「谎报已打开 / 已设闹钟 / 已拨号」的根源。
 *   所有「启动成功」判定都必须走这里：启动后通过无障碍前台窗口 / 事件流回查，确认不了就返回 [LaunchOutcome.Unknown]。
 *
 * 本方法**只启动一次、不重试**：拨号、支付类 Intent 重复启动会产生重复副作用。
 * 需要降级（手势、悬浮窗豁免）的调用方自行决定，见 [DeviceActions] / [BackgroundLauncher]。
 */
object VerifiedLauncher {

    private const val TAG = "PilotLaunch"
    private const val POLL_MS = 100L

    /** 目标出现在前台后，需再稳定停留这么久才算真正打开（防 HarmonyOS “闪一下就撤”）。 */
    private const val STABLE_MS = 600L

    /**
     * [LaunchOutcome.Blocked.reason] 的前缀：系统以 SecurityException 明确拒绝（未导出、缺签名权限）。
     * 这种情况重试/降级都没有意义，调用方可据此直接放弃。
     */
    const val REASON_DENIED = "系统拒绝了这个启动请求"

    /**
     * @param expectPackage 期望落到的包；null 时用 resolveActivity 推断，推断不出则只要求「前台变了」。
     * @param timeoutMs 回查等待时长。冷启动的大 App（设置、微信）焦点切换可能要 5 秒，打开 App 时建议传 6000。
     */
    suspend fun launch(
        ctx: Context,
        intent: Intent,
        expectPackage: String?,
        timeoutMs: Long = 1500,
    ): LaunchOutcome {
        val target = expectPackage?.takeIf { it.isNotBlank() } ?: inferPackage(ctx, intent)
        val self = ctx.packageName
        val probe = Probe()
        val beforePkg = probe.foreground()
        val beforeWindow = probe.windowFingerprint()
        val selfWasForeground = probe.selfForeground()

        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: SecurityException) {
            Dbg.w(TAG, "启动被系统拒绝：${e.message}")
            return LaunchOutcome.Blocked("$REASON_DENIED（${e.message ?: "SecurityException"}）")
        } catch (e: ActivityNotFoundException) {
            Dbg.w(TAG, "没有 Activity 能处理：${intent.action ?: intent.component}")
            return LaunchOutcome.Failed(e)
        } catch (t: Throwable) {
            Dbg.w(TAG, "启动异常 ${t.javaClass.simpleName}: ${t.message}")
            return LaunchOutcome.Failed(t)
        }

        val canProbe = probe.available() || selfWasForeground
        var waited = 0L
        var movedElsewhere: String? = null
        while (waited < timeoutMs) {
            delay(POLL_MS) // 也是取消检查点
            waited += POLL_MS
            val now = probe.foreground()
            if (now != null && target != null && now == target) {
                if (now != beforePkg) {
                    // ★ 稳定确认：HarmonyOS 冷启动「设置主页」这类受保护页面时，会先把目标
                    //   闪到前台 ~300ms 再撤下（后台启动限制的“假放行”）。只看瞬态就回 Ok
                    //   会谎报成功，并让上层错过真正的兜底（桌面图标手势）。等一小段再确认仍在目标。
                    delay(STABLE_MS)
                    val still = probe.foreground()
                    if (still == target) {
                        Dbg.i(TAG, "前台切到 $target 并稳定 用时 ${waited + STABLE_MS}ms")
                        return LaunchOutcome.Ok
                    }
                    Dbg.w(TAG, "前台短暂出现 $target 后又变成 $still，判定未真正打开")
                    movedElsewhere = still
                    continue
                }
                // 目标本来就在前台（同包内跳页）：看窗口指纹有没有变
                val win = probe.windowFingerprint()
                if (win != null && beforeWindow != null && win != beforeWindow) {
                    Dbg.i(TAG, "同包内窗口已切换 用时 ${waited}ms")
                    return LaunchOutcome.Ok
                }
            } else if (now != null && now != beforePkg && !isTransient(now)) {
                if (target == null) {
                    Dbg.i(TAG, "前台已切换（$beforePkg → $now）用时 ${waited}ms")
                    return LaunchOutcome.Ok
                }
                movedElsewhere = now
            }
            // 没有无障碍时的旁证：启动前本 App 在前台，启动后被别的界面盖住
            if (!probe.available() && selfWasForeground && target != self && !probe.selfForeground()) {
                Dbg.i(TAG, "本 App 界面已被盖住，视为启动成功 用时 ${waited}ms")
                return LaunchOutcome.Ok
            }
        }

        return when {
            !canProbe -> {
                Dbg.w(TAG, "无法回查前台（无障碍未连接），结果未知")
                LaunchOutcome.Unknown
            }
            movedElsewhere != null -> {
                Dbg.w(TAG, "前台变成了 $movedElsewhere，不是期望的 $target，结果未知")
                LaunchOutcome.Unknown
            }
            target != null && beforePkg == target -> {
                // 目标一直在前台，看不出有没有换页
                LaunchOutcome.Unknown
            }
            else -> {
                Dbg.w(TAG, "等待 ${timeoutMs}ms 前台未变化（$beforePkg），可能被后台启动限制拦截")
                LaunchOutcome.Blocked("前台没有切换，可能被系统后台启动限制拦截")
            }
        }
    }

    private fun inferPackage(ctx: Context, intent: Intent): String? {
        intent.component?.packageName?.let { return it }
        intent.`package`?.let { return it }
        return try {
            ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
                // 解析到系统的「选择打开方式」时不能拿它当目标
                ?.takeIf { it != "android" && !it.contains("resolver", ignoreCase = true) }
        } catch (_: Throwable) {
            null
        }
    }

    /** 过渡性的前台：SystemUI 面板、输入法。它们出现不代表目标 App 起来了。 */
    private fun isTransient(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.contains("inputmethod", ignoreCase = true)

    /** 前台回查：无障碍窗口列表优先，其次无障碍事件流。 */
    private class Probe {
        private val reader: com.pilot.agent.perception.ScreenReader?
            get() = com.pilot.agent.runtime.AgentRuntime.reader

        fun available(): Boolean = reader != null

        fun foreground(): String? {
            reader?.focusedAppPackage()?.let { return it }
            if (reader == null) return null
            return com.pilot.agent.runtime.AgentRuntime.foreground.value.takeIf { it.isNotEmpty() }
        }

        fun windowFingerprint(): String? = reader?.windowFingerprint()

        /** 本进程是否有界面在最前（IMPORTANCE_FOREGROUND；前台服务是 125，不算）。 */
        fun selfForeground(): Boolean = try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (_: Throwable) {
            false
        }
    }
}
