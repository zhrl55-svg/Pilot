package com.pilot.agent.sandbox

import android.content.Context
import com.pilot.agent.Dbg

/**
 * 沙箱终端门面（Pilot 侧唯一入口）。
 *
 * 两条执行路径，接口一致：
 * 1. **优先** `:sandbox` 独立进程（SandboxService，AIDL 异步回推）——
 *    guest 里坏命令把 native 层搞炸只崩沙箱进程，语音 agent 不受牵连；
 * 2. 降级 主进程内执行（bind 失败/服务进程连崩三次时）。
 *
 * 首次调用会：解包 alpine rootfs（~30 秒）→ 建 bind mounts → 起持久 shell，
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
    @Volatile private var ready = false
    @Volatile private var currentContext: Context? = null
    @Volatile private var remoteDisabled = false

    data class Result(val output: String, val exitCode: Int, val durationMs: Long) {
        val ok get() = exitCode == 0
        /** 给播报/回模型用的截断摘要 */
        fun brief(max: Int = 800): String =
            output.trim().take(max).ifEmpty { "(无输出)" }
    }

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            currentContext = context.applicationContext
            ExecutionCoordinator.init(context)
            // 挂载目录存储注入（UI 添加的 SAF/POSIX 目录 → PRootKernel bind mounts）。
            // boot 是首条命令时才发生（ExecutionCoordinator 自动引导），这里注入来得及。
            if (PRootKernel.mountedFoldersStore == null) {
                PRootKernel.mountedFoldersStore = SandboxStores.mountedFolders(context)
            }
            ready = true
            Dbg.i(TAG, "SandboxTerminal 就绪（rootfs 未解包，首条命令触发）")
        }
    }

    /**
     * 执行一条 shell 命令（挂起）。优先走 :sandbox 独立进程；
     * 远端任何不可用（超时/DeadObject/连崩）自动降级进程内执行一次，
     * 连续失败再把 remoteDisabled 置位。
     */
    suspend fun run(context: Context, command: String, timeoutMs: Long = 300_000L): Result {
        if (!remoteDisabled) {
            val r = SandboxClient.runRemote(context, command, timeoutMs)
            if (r != null) return r
        }
        return runLocal(command, timeoutMs)
    }

    /** 进程内直跑（v1 路径，也是降级路径）。 */
    suspend fun run(command: String, timeoutMs: Long = 300_000L): Result =
        runLocal(command, timeoutMs)

    private suspend fun runLocal(command: String, timeoutMs: Long): Result {
        if (!ready) return Result("沙箱未初始化", -1, 0)
        Dbg.i(TAG, "run(local): $command")
        // 挂载热更新：UI 改了挂载表之后，本进程的热壳也要重建
        currentContext?.let { syncMountsIfChanged(it) }
        val r = ExecutionCoordinator.execute(
            sessionId = SESSION,
            command = command,
            timeout = timeoutMs,
        )
        Dbg.i(TAG, "done exit=${r.exitCode} ${r.durationMs}ms out=${r.output.take(120)}")
        return Result(r.output, r.exitCode, r.durationMs)
    }

    /** :sandbox 进程里跑（SandboxService 专用，勿在主进程调）。 */
    suspend fun runInService(context: Context, session: String, command: String, timeoutMs: Long): Triple<String, Int, Long> {
        if (!ready) init(context)
        // ★ 跨进程热挂载：挂载表存在 SharedPreferences（主进程写、:sandbox 读），
        //   所以 :sandbox 侧靠「指纹变了没有」判断要不要重建热壳。
        syncMountsIfChanged(context)
        val r = ExecutionCoordinator.execute(
            sessionId = session, command = command, timeout = timeoutMs,
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
            Dbg.w(TAG, "挂载同步失败：${t.message}")
        }
    }

    /** rootfs 状态一行字（status 通道用）。 */
    fun rootfsState(context: Context): String {
        val dir = java.io.File(context.filesDir, "alpine-rootfs")
        return if (dir.isDirectory) "已解包(${java.io.File(dir, "bin").listFiles()?.size ?: 0} bins)" else "未解包"
    }

    /** 当前是否已经具备热壳（自检用）。 */
    fun isWarm(): Boolean = false // ExecutionCoordinator 未暴露查询口，留待需要时加

    /** 服务进程连续崩溃后禁用远端（由 SandboxClient 回调）。 */
    internal fun markRemoteBroken(reason: String) {
        remoteDisabled = true
        Dbg.w(TAG, "沙箱远端进程禁用：$reason（后续走进程内降级）")
    }
}
