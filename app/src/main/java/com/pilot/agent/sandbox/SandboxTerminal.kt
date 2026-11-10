package com.pilot.agent.sandbox

import android.content.Context
import com.pilot.agent.Dbg

/**
 * 沙箱终端门面（Pilot 侧唯一入口）。
 *
 * 把 minis 搬来的 ExecutionCoordinator（object 单例，内部管 PRootKernel /
 * RootfsManager / PersistentShell / 节流与超时）包成一次 `run(command)`。
 * 首次调用会：解包 alpine rootfs（~30 秒）→ 建 bind mounts → 起持久 shell，
 * 之后同 session 的命令都是热壳（<100ms 启动）。
 *
 * ## W^X 提醒（HANDOFF 坑）
 * Android 10+ 禁止 exec 私有目录里的文件，proot 二进制靠
 * `android:extractNativeLibs="true"` 落到 nativeLibraryDir 的 libproot.so
 * 被执行（RootfsManager.prootBinary 已写死这条路径），别改成从 assets 解压执行。
 *
 * ## 进程模型
 * ARCHITECTURE.md 规划沙箱进 `:sandbox` 独立进程；v1 先跑主进程，
 * 内存水位（rootfs 解包期 ~80MB）压出问题再拆，门面接口不变。
 */
object SandboxTerminal {

    private const val TAG = "PilotSandbox"
    private const val SESSION = "pilot-agent"
    @Volatile private var ready = false

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
            ExecutionCoordinator.init(context)
            ready = true
            Dbg.i(TAG, "SandboxTerminal 就绪（rootfs 未解包，首条命令触发）")
        }
    }

    /**
     * 执行一条 shell 命令。挂起函数，调用方负责线程。
     * 首条命令含 rootfs 解包，timeout 给足 5 分钟。
     */
    suspend fun run(command: String, timeoutMs: Long = 300_000L): Result {
        if (!ready) return Result("沙箱未初始化", -1, 0)
        Dbg.i(TAG, "run: $command")
        val r = ExecutionCoordinator.execute(
            sessionId = SESSION,
            command = command,
            timeout = timeoutMs,
        )
        Dbg.i(TAG, "done exit=${r.exitCode} ${r.durationMs}ms out=${r.output.take(120)}")
        return Result(r.output, r.exitCode, r.durationMs)
    }

    /** 当前是否已经具备热壳（自检用）。 */
    fun isWarm(): Boolean = false // v1: ExecutionCoordinator 未暴露查询口，留待需要时加
}
