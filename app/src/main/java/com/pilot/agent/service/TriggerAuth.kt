package com.pilot.agent.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.pilot.agent.BuildConfig

/**
 * 外部触发入口（RunReceiver / TriggerActivity）的调用方校验（审核报告 code.md S1）。
 *
 * ## 两道防线
 * 1. **系统强制**：两个导出组件在所有构建类型都声明了 signature 级权限
 *    `com.pilot.agent.permission.TRIGGER`（见 AndroidManifest；debug 包不再移除）。
 *    没有同签名的第三方 App 根本投递不进来——这是唯一可靠的防线。
 * 2. **能拿到真实调用方 UID 时再核一次**：API 34+ 的 `sentFromUid` / `launchedFromUid`。
 *    拿到的 UID 必须是本应用、system，或与本应用签名一致；shell/root 只在 debug 包里接受。
 *
 * 旧实现的问题：API<34 时 `Binder.getCallingUid()` 在 onReceive 里拿到的是**自己**的 UID、
 * Activity 直接用 `applicationInfo.uid` 充当调用方 —— 等于「无条件可信」。
 * 现在拿不到 UID 时返回 [Caller.Unknown]：不冒充可信，只说明「已由系统权限把关」。
 */
internal object TriggerAuth {

    sealed interface Caller {
        /** 拿到了 UID 且校验通过。 */
        data class Verified(val uid: Int) : Caller
        /** 拿到了 UID 但不可信：必须拒绝。 */
        data class Rejected(val uid: Int) : Caller
        /** 系统没给 UID（API<34，或发送方没有共享身份）：只能依赖 manifest 的 signature 权限。 */
        object Unknown : Caller
    }

    fun classify(ctx: Context, uid: Int): Caller {
        if (uid == Process.INVALID_UID || uid < 0) return Caller.Unknown
        return if (isTrustedUid(ctx, uid)) Caller.Verified(uid) else Caller.Rejected(uid)
    }

    private fun isTrustedUid(ctx: Context, uid: Int): Boolean {
        val self = ctx.applicationInfo.uid
        if (uid == self) return true
        if (uid == Process.SYSTEM_UID) return true
        if (BuildConfig.DEBUG && (uid == Process.SHELL_UID || uid == Process.ROOT_UID)) return true
        // 签名比对，不依赖 permission 的 protectionLevel 配置。
        return runCatching {
            ctx.packageManager.checkSignatures(self, uid) == PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)
    }

    fun label(caller: Caller): String = when (caller) {
        is Caller.Verified -> "uid=${caller.uid}"
        is Caller.Rejected -> "rejected uid=${caller.uid}"
        Caller.Unknown -> "perm-gated"
    }
}
