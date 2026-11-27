package com.pilot.agent.sandbox

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.pilot.agent.Dbg
import com.pilot.agent.PilotApp
import com.pilot.agent.action.LaunchOutcome
import com.pilot.agent.action.VerifiedLauncher
import com.pilot.agent.agent.AgentAction
import com.pilot.agent.agent.Risk
import com.pilot.agent.perception.ScreenSnapshot
import com.pilot.agent.runtime.AgentRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主进程反向通道实现（挂在 ISandboxService.attachHost 上传给 :sandbox）。
 *
 * ## 修复（审核 arch D2 / E1 / 依赖表 HostBridge.kt:29-33）
 * 旧实现在 **binder 线程** 上 `runBlocking { AgentRuntime.actions.openApp(app) }`：
 * 阻塞 binder 池、沙箱能力反向驱动 UI 自动化（含桌面手势兜底）、不经过 Guard、不留审计。
 * 现在：
 * - `call()` 只处理快调用（idleRecycle），立即返回；屏幕/启动类请求一律拒绝；
 * - `callAsync()` 是 oneway：binder 线程只做来源校验后立即返回，
 *   真正的工作在 [scope] 协程里完成，整体带 [OPEN_TIMEOUT_MS] 超时，
 *   结果经 [IHostCallback] 回推；
 * - 打开应用前先过 [com.pilot.agent.agent.Guard.riskOf]，非 NONE 一律拒绝
 *   （沙箱没有用户确认通道），每次请求都写 Guard 审计；
 * - 只用 Intent 启动 + [VerifiedLauncher] 回查，**不**做无障碍点击/手势兜底；
 *   未能确认前台时返回“结果未知”，不声称成功；
 * - 串行 + 最小间隔限流，防止沙箱脚本循环拉起应用。
 */
object HostBridge {

    private const val TAG = "PilotSandbox"

    /** 主进程处理一次异步请求的总上限（:sandbox 侧等待 8s，这里更短，保证先回推“未知”）。 */
    private const val OPEN_TIMEOUT_MS = 6_000L

    /** VerifiedLauncher 回查前台的等待时间。 */
    private const val LAUNCH_VERIFY_MS = 2_000L

    /** 两次沙箱发起的打开应用之间的最小间隔。 */
    private const val MIN_OPEN_INTERVAL_MS = 2_000L

    private const val MAX_APP_NAME_LEN = 128

    /** 审计里的任务来源标记（Guard.audit 本身会隐藏 goal 原文）。 */
    private const val AUDIT_GOAL = "sandbox:android-open"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val openMutex = Mutex()
    @Volatile private var lastOpenAt = 0L

    val impl = object : IHostBridge.Stub() {
        override fun call(req: String?): Bundle {
            if (!fromSelf()) return err("拒绝：调用方不是本应用")
            val r = req.orEmpty()
            Dbg.i(TAG, "host call: ${r.substringBefore(':')}")
            return when {
                // 沙箱要空闲回收了 → 主进程必须解绑，否则 BIND_AUTO_CREATE
                // 会让系统立刻把服务再拉起来，回收白做。（快调用）
                r == "idleRecycle" -> {
                    SandboxClient.disconnectForIdle()
                    ok()
                }
                // 屏幕/启动类操作不允许在 binder 线程同步执行
                r.startsWith("open:") -> err("打开应用必须走异步通道（callAsync）")
                else -> err("unknown req")
            }
        }

        override fun callAsync(req: String?, cb: IHostCallback?) {
            if (cb == null) return
            // Binder.getCallingUid 只在 binder 线程上有效，必须在切协程前取
            if (!fromSelf()) {
                reply(cb, err("拒绝：调用方不是本应用"))
                return
            }
            val r = req.orEmpty()
            Dbg.i(TAG, "host callAsync: ${r.substringBefore(':')}")
            scope.launch {
                val result = try {
                    withTimeoutOrNull(OPEN_TIMEOUT_MS) { handleAsync(r) }
                        ?: unknown("主进程 ${OPEN_TIMEOUT_MS / 1000} 秒内未完成，无法确认是否已打开")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Dbg.w(TAG, "host callAsync 异常：${t.javaClass.simpleName}")
                    err("宿主处理异常（${t.javaClass.simpleName}）")
                }
                reply(cb, result)
            }
        }
    }

    private fun fromSelf(): Boolean = Binder.getCallingUid() == Process.myUid()

    private suspend fun handleAsync(r: String): Bundle = when {
        r.startsWith("open:") -> openFromSandbox(r.removePrefix("open:").trim())
        else -> err("unknown req")
    }

    private suspend fun openFromSandbox(app: String): Bundle {
        if (app.isEmpty() || app.length > MAX_APP_NAME_LEN) return err("应用名无效")
        val action = AgentAction(action = "open", target = app, summary = "沙箱请求打开应用")
        // 审计里标出来源，便于回溯哪些操作是沙箱发起的
        val auditAction = action.copy(target = "$app（沙箱发起）")
        val guard = AgentRuntime.guard
        val risk = guard.riskOf(action, ScreenSnapshot())
        if (risk != Risk.NONE) {
            guard.audit(AUDIT_GOAL, auditAction, risk, false)
            return err("该操作涉及${guard.riskLabel(risk)}，沙箱没有确认通道，已拒绝")
        }
        return openMutex.withLock { launchLocked(app, auditAction, risk) }
    }

    private suspend fun launchLocked(app: String, auditAction: AgentAction, risk: Risk): Bundle {
        val guard = AgentRuntime.guard
        val now = System.currentTimeMillis()
        if (now - lastOpenAt < MIN_OPEN_INTERVAL_MS) {
            guard.audit(AUDIT_GOAL, auditAction, risk, false)
            return err("打开应用过于频繁，已拒绝")
        }
        lastOpenAt = now
        val ctx: Context = PilotApp.instance
        val pkg = resolvePackage(ctx, app)
        if (pkg == null) {
            guard.audit(AUDIT_GOAL, auditAction, risk, false)
            return err("没有找到应用「$app」")
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            guard.audit(AUDIT_GOAL, auditAction, risk, false)
            return err("「$app」没有可启动的界面")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        guard.audit(AUDIT_GOAL, auditAction, risk, true)
        val outcome = VerifiedLauncher.launch(ctx, intent, pkg, LAUNCH_VERIFY_MS)
        return when (outcome) {
            is LaunchOutcome.Blocked -> err("系统拦截了启动：${outcome.reason}")
            is LaunchOutcome.Failed -> err("启动失败（${outcome.error.javaClass.simpleName}）")
            LaunchOutcome.Ok -> ok()
            else -> unknown("已发出启动请求，但未能确认「$app」是否已在前台")
        }
    }

    /**
     * 名字 → 包名。无障碍已连接时复用 DeviceActions 的解析（含别名）；
     * 否则只用 PackageManager（包名精确匹配 → 启动器标签精确匹配 → 包含匹配）。
     */
    private fun resolvePackage(ctx: Context, app: String): String? {
        AgentRuntime.actions?.let { actions ->
            runCatching { actions.resolvePackage(app) }.getOrNull()?.let { return it }
        }
        val pm = ctx.packageManager
        if (runCatching { pm.getLaunchIntentForPackage(app) }.getOrNull() != null) return app
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val labeled = runCatching {
            pm.queryIntentActivities(launcher, 0).map { info ->
                info.activityInfo.packageName to info.loadLabel(pm).toString()
            }
        }.getOrDefault(emptyList())
        return labeled.firstOrNull { it.second.equals(app, ignoreCase = true) }?.first
            ?: labeled.firstOrNull { it.second.contains(app, ignoreCase = true) }?.first
    }

    private fun reply(cb: IHostCallback, data: Bundle) {
        try {
            cb.onHostResult(data)
        } catch (e: Exception) {
            Dbg.w(TAG, "回推沙箱结果失败：${e.javaClass.simpleName}")
        }
    }

    private fun ok(): Bundle = Bundle().apply { putBoolean("ok", true) }

    private fun err(msg: String): Bundle = Bundle().apply {
        putBoolean("ok", false)
        putString("err", msg)
    }

    private fun unknown(msg: String): Bundle = Bundle().apply {
        putBoolean("ok", false)
        putBoolean("unknown", true)
        putString("err", msg)
    }
}
