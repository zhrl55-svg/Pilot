package com.pilot.agent.sandbox.shim

import java.io.File

/**
 * minis 的 @文件 引用索引桩。
 *
 * Pilot v1 只用它一个数据形状：PRootKernel.mountEntriesForIndex 把挂载目录
 * 转成 (name, root) 列表（原版给聊天里的 @文件名 补全用，我们没有那个 UI）。
 * 完整版（404 行）在 minis 的 data/FileMentionIndex.kt，接 UI 时再搬。
 */
class FileMentionIndex {
    data class MountEntry(val name: String, val root: File)
}

/**
 * minis 的用户环境变量仓库桩：内核只用 `allAsDict()`，且调用点全部
 * `envVarRepository?.` 判空（PRootKernel 默认环境 + 用户覆盖）。
 * Pilot 暂不提供用户自定义容器环境变量 → 留空接口。
 */
class EnvVarRepository {
    suspend fun allAsDict(): Map<String, String> = emptyMap()
}
