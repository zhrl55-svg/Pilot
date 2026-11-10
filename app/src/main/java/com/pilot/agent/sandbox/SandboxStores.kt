package com.pilot.agent.sandbox

import android.content.Context
import com.pilot.agent.Dbg
import com.pilot.agent.sandbox.shim.MountedFoldersStore

/**
 * 沙箱侧进程内单例仓（每个进程各持一份，:sandbox 进程自己 init 时也会建）。
 */
object SandboxStores {

    private const val TAG = "PilotSandbox"
    @Volatile private var store: MountedFoldersStore? = null

    fun mountedFolders(context: Context): MountedFoldersStore {
        val existing = store
        if (existing != null) return existing
        return synchronized(this) {
            store ?: MountedFoldersStore(context.applicationContext).also { s ->
                store = s
                // ★ 热挂载（minis 原版 T219-5 同款）：
                //   proot 的 `-b` 在 spawn 时冻结，改快照对活着的热壳无效，
                //   必须 apply 快照 + 杀掉热壳，下条命令重建时带上新 bind。
                s.onChange = {
                    try {
                        PRootKernel.applyMountedFoldersSnapshot(context.applicationContext)
                        // 无 session 参数 = 重启**所有**热壳（不是用户按的 Stop，
                        // 所以不动任何会话的 stopEpoch）
                        ExecutionCoordinator.stopCurrentCommand()
                        Dbg.i(TAG, "挂载变更：快照已更新，热壳已重启")
                    } catch (t: Throwable) {
                        Dbg.w(TAG, "挂载变更处理失败：${t.message}")
                    }
                }
            }
        }
    }
}
