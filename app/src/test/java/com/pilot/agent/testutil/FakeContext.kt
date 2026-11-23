package com.pilot.agent.testutil

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files

/**
 * 纯 JVM 单测用的最小 Context。
 *
 * AGP 的 mockable android.jar 把所有方法体替换成默认值，所以这里只需要补上
 * 被测代码真正读取的两样东西：applicationContext 与 SharedPreferences。
 * 其它系统服务仍走 mockable 默认值（null / 0），这正是纯 JVM 测试的边界。
 */
class FakeContext : ContextWrapper(null) {

    private val memoryPrefs = FakeSharedPreferences()
    private val root: File = Files.createTempDirectory("pilot-test-context").toFile()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = memoryPrefs

    override fun getFilesDir(): File = root

    override fun getCacheDir(): File = root

}

private class FakeEditor(private val owner: FakeSharedPreferences) : SharedPreferences.Editor {

    private val pending = linkedMapOf<String, Any?>()
    private val removals = linkedSetOf<String>()
    private var clearRequested = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
        apply { pending[key.orEmpty()] = values }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

    override fun remove(key: String?): SharedPreferences.Editor = apply { removals.add(key.orEmpty()) }

    override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        owner.mutate(pending, removals, clearRequested)
        pending.clear()
        removals.clear()
        clearRequested = false
    }
}

private class FakeSharedPreferences : SharedPreferences {

    private val values = linkedMapOf<String, Any?>()

    fun mutate(pending: Map<String, Any?>, removals: Set<String>, clear: Boolean) {
        if (clear) values.clear()
        removals.forEach { values.remove(it) }
        pending.forEach { (key, value) -> values[key] = value }
    }

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}