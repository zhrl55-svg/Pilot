package com.pilot.agent.query

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pilot.agent.Dbg
import com.pilot.agent.PilotApp
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 一次读取的结果：区分「没有存过」和「存了但现在读不出来」（审核报告 code.md E1）。 */
sealed interface VaultRead {
    object Absent : VaultRead
    data class Ok(val value: String) : VaultRead
    data class Failed(val error: Throwable) : VaultRead
}

/**
 * 该条目上一次读取失败（Keystore 抖动等），为防止「读失败 = 空数据 → 下次保存覆盖」，
 * 在成功读到一次之前拒绝普通写入。
 */
class VaultUnavailableException(val name: String, cause: Throwable? = null) :
    IllegalStateException("secure storage unavailable: $name", cause)

/**
 * Small AndroidKeyStore-backed secret storage for optional service tokens.
 *
 * ## 数据完整性约定（E1 修复）
 * - [read] 区分 Absent / Ok / Failed。
 * - **瞬时失败**（KeyStoreException、ProviderException 等）：原密文原样保留；该条目被标记为
 *   「读失败」，之后的 [put] 抛 [VaultUnavailableException]、[putChecked] 返回 false，
 *   直到再次成功读取（或调用方以 `force = true` 明确写入用户新输入的值）。
 * - **永久损坏**（AEADBadTagException / Base64 损坏 / 长度不足，即密钥已更换或密文被破坏）：
 *   先把原密文备份到 `<name>__unreadable_backup`（只保留第一份），再按 Absent 处理，
 *   避免功能永久不可用。备份可在密钥恢复后人工取回。
 * - [get] 保持旧签名（失败返回 ""），但同样会设置「读失败」标记，旧调用方写回时会被拦下。
 * - 密钥对象缓存在内存，减少每次 `KeyStore.load()` 的跨进程开销；绝不在别名已存在时重新生成密钥。
 *
 * 日志只记条目名与异常类型，不记明文/密文。
 */
object SecretVault {
    private const val TAG = "SecretVault"
    private const val PREFS = "secure_service_secrets"
    private const val ALIAS = "pilot_service_secrets_v1"
    private const val BACKUP_SUFFIX = "__unreadable_backup"
    private const val IV_LEN = 12

    /** 最近一次读取失败、尚未恢复的条目。 */
    private val failedReads: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** 写入成功后通知（AgentSettings 用来失效内存缓存）。 */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * 仅供 JVM 单测注入：真实设备上恒为 null / false。
     *
     * 单元测试没有 AndroidKeyStore，也无法构造 [PilotApp.instance]。测试通过 [installForTest]
     * 传入 FakeContext 并切到明文模式，从而验证的是**业务逻辑**（如提醒取消语义），
     * 而不是本类在真实设备上的加解密行为（那部分留到真机验证）。
     */
    @Volatile
    internal var testContext: android.content.Context? = null

    @Volatile
    internal var testPlaintext: Boolean = false

    internal fun installForTest(context: android.content.Context) {
        testContext = context.applicationContext ?: context
        testPlaintext = true
        cachedKey = null
        failedReads.clear()
    }

    internal fun resetForTest() {
        testContext = null
        testPlaintext = false
        cachedKey = null
        failedReads.clear()
    }

    private fun prefs(): SharedPreferences =
        (testContext ?: PilotApp.instance.applicationContext).getSharedPreferences(PREFS, 0)

    fun addChangeListener(listener: (String) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    /** 该条目当前是否因读取失败而处于写保护状态。 */
    fun isReadBlocked(name: String): Boolean = name in failedReads

    fun read(name: String): VaultRead {
        val encoded = prefs().getString(name, null)
        if (encoded == null) {
            failedReads.remove(name)
            return VaultRead.Absent
        }
        if (testPlaintext) {
            failedReads.remove(name)
            return VaultRead.Ok(encoded)
        }
        return try {
            val value = decrypt(encoded)
            failedReads.remove(name)
            VaultRead.Ok(value)
        } catch (t: Throwable) {
            if (isPermanentCorruption(t)) {
                backupUnreadable(name, encoded)
                Dbg.e(TAG, "密文无法解密（密钥已更换或数据损坏），已备份后按空处理：$name ${t.javaClass.simpleName}")
                runCatching { prefs().edit().remove(name).commit() }
                failedReads.remove(name)
                VaultRead.Absent
            } else {
                if (t is java.security.InvalidKeyException) cachedKey = null
                failedReads.add(name)
                Dbg.e(TAG, "读取失败（保留原密文，暂停写入）：$name ${t.javaClass.simpleName}")
                VaultRead.Failed(t)
            }
        }
    }

    /** 旧接口：没有或读不出来都返回 ""。读不出来时该条目会进入写保护。 */
    fun get(name: String): String = when (val r = read(name)) {
        is VaultRead.Ok -> r.value
        VaultRead.Absent -> ""
        is VaultRead.Failed -> ""
    }

    /**
     * 旧接口：写入失败抛异常。若该条目处于读失败写保护，抛 [VaultUnavailableException]，
     * 保证不会用「读失败得到的空数据」覆盖原密文。
     */
    fun put(name: String, value: String) {
        if (name in failedReads) throw VaultUnavailableException(name)
        write(name, value, commit = false)
    }

    /**
     * 写入并确认落盘（commit）。
     * @param force true = 调用方确认这是用户新输入的值，即使之前读失败也允许覆盖。
     * @return 成功返回 true；失败（含写保护）返回 false，原数据不变。
     */
    fun putChecked(name: String, value: String, force: Boolean = false): Boolean {
        if (!force && name in failedReads) {
            Dbg.w(TAG, "条目处于读失败写保护，拒绝写入：$name")
            return false
        }
        return try {
            val ok = write(name, value, commit = true)
            if (ok) failedReads.remove(name)
            ok
        } catch (t: Throwable) {
            if (t is java.security.InvalidKeyException) cachedKey = null
            Dbg.e(TAG, "写入失败：$name ${t.javaClass.simpleName}")
            false
        }
    }

    private fun write(name: String, value: String, commit: Boolean): Boolean {
        val prefs = prefs()
        if (testPlaintext) {
            val editor = if (value.isBlank()) {
                prefs.edit().remove(name)
            } else {
                prefs.edit().putString(name, value)
            }
            val ok = if (commit) editor.commit() else { editor.apply(); true }
            if (ok) listeners.forEach { l -> runCatching { l(name) } }
            return ok
        }
        val editor = if (value.isBlank()) {
            prefs.edit().remove(name)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        val ok = if (commit) editor.commit() else { editor.apply(); true }
        if (ok) listeners.forEach { l -> runCatching { l(name) } }
        return ok
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size <= IV_LEN) throw CorruptSecretException()
        val iv = bytes.copyOfRange(0, IV_LEN)
        val encrypted = bytes.copyOfRange(IV_LEN, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private class CorruptSecretException : RuntimeException("ciphertext too short")

    /** 只有「确定解不开」才算永久损坏；Keystore 自身的异常一律按瞬时失败处理。 */
    private fun isPermanentCorruption(t: Throwable): Boolean =
        t is AEADBadTagException || t is CorruptSecretException ||
            (t is IllegalArgumentException && t.message?.contains("base-64", ignoreCase = true) == true)

    private fun backupUnreadable(name: String, encoded: String) {
        runCatching {
            val p = prefs()
            val backupKey = name + BACKUP_SUFFIX
            if (!p.contains(backupKey)) p.edit().putString(backupKey, encoded).commit()
        }.onFailure { Dbg.e(TAG, "备份不可读密文失败：$name ${it.javaClass.simpleName}") }
    }

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            // 别名存在却取不到密钥：按瞬时失败抛出，绝不重新生成（重新生成会让旧密文永久无法解密）。
            val existing = store.getKey(ALIAS, null) as? SecretKey
                ?: throw java.security.KeyStoreException("keystore alias present but key unavailable")
            cachedKey = existing
            return existing
        }
        val generated = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
        cachedKey = generated
        return generated
    }
}
