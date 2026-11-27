package com.pilot.agent.sandbox

import android.content.Context
import com.pilot.agent.Dbg

/**
 * 沙箱终端门面（Pilot 侧唯一入口）。
 *
 * ## 只有一条执行路径：`:sandbox` 独立进程
 * 命令一律经 [SandboxClient] 交给 SandboxService（AIDL 异步回推）执行——
 * guest 里坏命令把 native 层搞炸只崩沙箱进程，语音 agent 不受牵连。
 *
 * 以前远端失败/超时会**降级到主进程内**再跑一遍（runLocal），这有两个问题
 * （审核 arch E1 / func X-01）：
 * 1. 远端超时时命令可能已经执行（甚至还在执行），主进程再跑一次会让副作用翻倍
 *    （发两次消息、push 两次）；
 * 2. proot/rootfs/约 200MB 内存落到常驻的语音主进程，破坏进程隔离。
 * 所以现在：
 * - 远端**未启动**（bind 失败、startCommand 抛错）→ [Status.UNAVAILABLE]，明确告知“命令未执行”；
 * - 远端**已启动但超时/断连** → [Status.UNKNOWN]，“结果未知”，**绝不重跑**；
 * - 主进程里不再初始化 ExecutionCoordinator / PRootKernel。
 *
 * 首次调用会在 :sandbox 里：解包 alpine rootfs（~30 秒）→ 建 bind mounts → 起持久 shell，
 * 之后同 session 的命令都是热壳（<100ms 启动）。
 *
 * ## W^X 提醒（HANDOFF 坑 #19）
 * Android 10+ 禁止 exec 私有目录里的文件，proot 二进制靠
 * `extractNativeLibs=true` + `useLegacyPackaging=true` 落到 nativeLibraryDir
 * 的 libproot.so 被执行（RootfsManager.prootBinary 写死这条路径）。
 */
object SandboxTerminal {

    private const val TAG = "PilotSandbox"
    const val SESSION = "pilot-agent"

    /**
     * 默认单步超时 60 秒（审核 X-02）：Agent/语音场景下用户不能干等 5 分钟。
     * 需要更久的调用方（终端页、MCP 冷启动）显式传入，但不超过 [MAX_TIMEOUT_MS]。
     */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /** 任何调用方单条命令的硬上限（7 分钟，覆盖 apk add / MCP 冷启动）。 */
    const val MAX_TIMEOUT_MS = 420_000L

    private const val MIN_TIMEOUT_MS = 1_000L

    /** 沙箱不可用、命令未执行。 */
    const val EXIT_UNAVAILABLE = -4

    /** 结果未知（已提交但超时/断连）。沿用 timeout(1) 的 124。 */
    const val EXIT_UNKNOWN = 124

    enum class Status {
        /** 远端执行完毕，exitCode 是命令真实退出码。 */
        DONE,

        /** 沙箱不可用，命令**没有**执行。 */
        UNAVAILABLE,

        /** 命令已提交但没拿到结果（超时/进程中断），可能已执行或部分执行。 */
        UNKNOWN,
    }

    data class Result(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val status: Status = Status.DONE,
    ) {
        val ok get() = status == Status.DONE && exitCode == 0

        /** 结果未知：上层必须如实告知，不得当作成功，也不要自动重试。 */
        val unknown get() = status == Status.UNKNOWN

        /** 给播报/回模型用的截断摘要 */
        fun brief(max: Int = 800): String =
            output.trim().take(max).ifEmpty { "(无输出)" }
    }

    /** :sandbox 进程内是否已初始化执行内核。 */
    @Volatile private var serviceReady = false

    /**
     * 主进程侧初始化：只记录 Application context，**不碰** PRoot / rootfs。
     * 保留这个入口是为了兼容现有调用方（AgentLoop / TerminalActivity / McpClient）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) {
        // 主进程无需任何状态；真正的执行内核只在 :sandbox 进程里装配（见 initInService）。
    }

    /** :sandbox 进程专用：装配执行内核。勿在主进程调用。 */
    internal fun initInService(context: Context) {
        if (serviceReady) return
        synchronized(this) {
            if (serviceReady) return
            ExecutionCoordinator.init(context)
            // 挂载目录存储注入（UI 添加的 SAF/POSIX 目录 → PRootKernel bind mounts）。
            // boot 是首条命令时才发生（ExecutionCoordinator 自动引导），这里注入来得及。
            if (PRootKernel.mountedFoldersStore == null) {
                PRootKernel.mountedFoldersStore = SandboxStores.mountedFolders(context)
            }
            serviceReady = true
            Dbg.i(TAG, "SandboxTerminal(:sandbox) 就绪（rootfs 未解包，首条命令触发）")
        }
    }

    /** 把调用方给的超时夹到 [MIN_TIMEOUT_MS, MAX_TIMEOUT_MS]。 */
    fun clampTimeout(timeoutMs: Long): Long = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /**
     * 执行一条 shell 命令（挂起，可取消）。只走 :sandbox 独立进程，失败不降级、不重跑。
     *
     * - 调用方协程被取消（用户点停止）→ 同时请求远端中止该命令，再抛出取消；
     * - 远端未启动 → [Status.UNAVAILABLE]；
     * - 已启动但超时/断连 → [Status.UNKNOWN]（如实告知“结果未知”）。
     */
    suspend fun run(context: Context, command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val timeout = clampTimeout(timeoutMs)
        return when (val outcome = SandboxClient.runRemote(context, command, timeout)) {
            is SandboxClient.RemoteOutcome.Done -> outcome.result
            is SandboxClient.RemoteOutcome.NotStarted -> Result(
                output = "沙箱暂不可用（${outcome.reason}），命令未执行。可以稍后再试。",
                exitCode = EXIT_UNAVAILABLE,
                durationMs = 0L,
                status = Status.UNAVAILABLE,
            )
            is SandboxClient.RemoteOutcome.Lost -> Result(
                output = "执行结果未知：${outcome.reason}。命令可能已经执行或部分执行；" +
                    "为避免重复副作用，没有自动重试，请先确认结果再决定是否重新执行。",
                exitCode = EXIT_UNKNOWN,
                durationMs = outcome.elapsedMs,
                status = Status.UNKNOWN,
            )
        }
    }

    /** :sandbox 进程里跑（SandboxService 专用，勿在主进程调）。 */
    suspend fun runInService(context: Context, session: String, command: String, timeoutMs: Long): Triple<String, Int, Long> {
        initInService(context)
        // ★ 跨进程热挂载：挂载表存在 SharedPreferences（主进程写、:sandbox 读），
        //   所以 :sandbox 侧靠「指纹变了没有」判断要不要重建热壳。
        syncMountsIfChanged(context)
        val r = ExecutionCoordinator.execute(
            sessionId = session, command = command, timeout = clampTimeout(timeoutMs),
        )
        return Triple(r.output, r.exitCode, r.durationMs)
    }

    /**
     * 挂载指纹（名字+路径的哈希）。变了就要重建 proot 的 bind 参数。
     */
    @Volatile private var mountsFingerprint: String = ""

    private fun syncMountsIfChanged(context: Context) {
        try {
            val store = SandboxStores.mountedFolders(context)
            // 跨进程：挂载表是主进程写的，:sandbox 侧的内存副本是旧的，
            // 必须每次重新从磁盘读（SharedPreferences 跨进程缓存不可靠）。
            store.reloadFromDisk()
            val fp = store.entries.value
                .map { "${it.name}=${it.resolvedHostPath.orEmpty()}" }
                .sorted()
                .joinToString("|")
            if (fp == mountsFingerprint) return
            val first = mountsFingerprint.isEmpty()
            mountsFingerprint = fp
            PRootKernel.applyMountedFoldersSnapshot(context.applicationContext)
            if (!first) {
                // 指纹变了 = 挂载集合变了 → 热壳的 -b 参数已过期，杀掉重建
                ExecutionCoordinator.stopCurrentCommand()
                Dbg.i(TAG, "检测到挂载变化（:sandbox），热壳已重建")
            }
        } catch (t: Throwable) {
            Dbg.w(TAG, "挂载同步失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** rootfs 状态一行字（status 通道用）。 */
    fun rootfsState(context: Context): String {
        val dir = java.io.File(context.filesDir, "alpine-rootfs")
        return if (dir.isDirectory) "已解包(${java.io.File(dir, "bin").listFiles()?.size ?: 0} bins)" else "未解包"
    }

    /** 当前是否已经具备热壳（自检用）。 */
    fun isWarm(): Boolean = false // ExecutionCoordinator 未暴露查询口，留待需要时加
}
