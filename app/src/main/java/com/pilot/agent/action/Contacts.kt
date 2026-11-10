package com.pilot.agent.action

import android.content.Context
import android.provider.ContactsContract
import com.pilot.agent.Dbg

/**
 * 联系人解析："给妈妈打电话" → 号码。
 *
 * 没有这层的话，用户必须报号码，而真实场景里没人记号码。
 * 匹配策略：先精确匹配 display_name，再模糊包含；多个同名时取第一个并记日志
 * （打电话场景没法弹候选让用户选——等选完电话就挂了）。
 */
object Contacts {

    private const val TAG = "Pilot"

    fun findNumber(ctx: Context, name: String): String? {
        val query = name.trim()
        if (query.isEmpty()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        // 1. 精确匹配
        runCatching {
            ctx.contentResolver.query(
                uri, projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(query), null,
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(1)?.replace(" ", "")
            }
        }.onFailure { Dbg.w(TAG, "通讯录查询失败：${it.message}") }

        // 2. 模糊包含
        runCatching {
            ctx.contentResolver.query(
                uri, projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    val num = c.getString(1)?.replace(" ", "")
                    Dbg.i(TAG, "联系人模糊匹配「$query」→「$n」$num")
                    return num
                }
            }
        }.onFailure { Dbg.w(TAG, "通讯录模糊查询失败：${it.message}") }

        return null
    }
}
