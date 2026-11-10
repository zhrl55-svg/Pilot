package com.pilot.agent.sandbox

import android.content.Context
import com.pilot.agent.sandbox.shim.MountedFoldersStore

/** 沙箱侧进程内单例仓（每个进程各持一份，:sandbox 进程自己 init 时也会建）。 */
object SandboxStores {
    @Volatile private var store: MountedFoldersStore? = null

    fun mountedFolders(context: Context): MountedFoldersStore =
        store ?: synchronized(this) {
            store ?: MountedFoldersStore(context.applicationContext).also { store = it }
        }
}
