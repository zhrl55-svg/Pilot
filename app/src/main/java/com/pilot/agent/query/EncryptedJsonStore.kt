package com.pilot.agent.query

import com.pilot.agent.Dbg
import org.json.JSONArray

/**
 * 提醒 / 待办 / 定时任务共用的「加密 JSON 数组」读写层（审核报告 code.md E1、M4）。
 *
 * 规则：
 * - 没存过 → 空数组；
 * - Keystore 读失败 → 抛 [VaultUnavailableException]，调用方必须中止本次「读→改→写」，
 *   并如实告诉用户「这次没有做任何修改」，**绝不**把空列表写回去；
 * - 解密成功但 JSON 损坏 → 先把原文加密备份到 `<name>_corrupt`（只保留第一份），再按空数组继续，
 *   这样损坏数据不会被下一次保存悄悄覆盖掉。
 *
 * 写入走 [SecretVault.putChecked]（commit 落盘），失败抛 [VaultUnavailableException]。
 */
object EncryptedJsonStore {
    private const val TAG = "EncryptedJsonStore"
    private const val CORRUPT_SUFFIX = "_corrupt"

    /** 给用户的统一说法（不以「已」开头，调用方据此判定失败）。 */
    const val UNAVAILABLE_MESSAGE = "本机加密存储暂时读不出来，这次没有做任何修改，请稍后再试。"

    @Throws(VaultUnavailableException::class)
    fun readArray(name: String): JSONArray {
        val raw = when (val r = SecretVault.read(name)) {
            VaultRead.Absent -> return JSONArray()
            is VaultRead.Failed -> throw VaultUnavailableException(name, r.error)
            is VaultRead.Ok -> r.value
        }
        if (raw.isBlank()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (t: Throwable) {
            Dbg.e(TAG, "JSON 损坏，已备份原文后按空列表处理：$name ${t.javaClass.simpleName}")
            backupCorrupt(name, raw)
            JSONArray()
        }
    }

    @Throws(VaultUnavailableException::class)
    fun writeArray(name: String, array: JSONArray) {
        if (!SecretVault.putChecked(name, array.toString())) {
            throw VaultUnavailableException(name)
        }
    }

    private fun backupCorrupt(name: String, raw: String) {
        val backupKey = name + CORRUPT_SUFFIX
        // 已有备份就不覆盖（保留最早的一份现场）。
        if (SecretVault.read(backupKey) is VaultRead.Ok) return
        if (!SecretVault.putChecked(backupKey, raw, force = true)) {
            Dbg.e(TAG, "备份损坏数据失败：$name")
        }
    }
}
