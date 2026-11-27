package com.pilot.agent.voice

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.pilot.agent.Dbg

/**
 * 这句语音指令的说话人校验结果（由 [VoiceInput] 在回调 onText 之前写入
 * [VoiceInput.lastSpeakerAuth]，同一句话的 onText 回调里读到的就是它）。
 *
 * 审核 S6 / V-07：以前"没有模板时第一句话自动注册为主人"，且"声纹不可用即放行"，
 * 上层无从区分"真的是主人"还是"根本没校验"。现在显式给出结论，由路由层决定
 * 敏感能力（读通知 / 个人数据 / 外发 / 拨号 / 终端）是否需要解锁或界面确认。
 */
enum class SpeakerAuth {
    /** 有模板且本句逐句声纹通过 */
    VERIFIED,

    /** 还没注册声纹（不会再自动注册，需要用户在界面里显式注册） */
    NOT_ENROLLED,

    /** 有模板，但这句没能校验（模型不可用 / 音频不足 0.6s），被"软放行" */
    UNVERIFIED,

    /** 用户在设置里关闭了"需要声纹"，未做校验 */
    NOT_REQUIRED,
    ;

    /** 只有 VERIFIED 才算"确认是主人"。 */
    val isOwnerVerified: Boolean get() = this == VERIFIED
}

/**
 * 设备锁屏 / 息屏状态（审核 product T5、P0 第 7 条）。
 *
 * 供路由层（ui / assistant）判断"现在能不能读出个人数据"。全部是只读系统查询，
 * 任意线程可调用，失败时按"已锁定"保守处理。
 */
object DeviceLockState {

    /**
     * 是否处于锁屏（锁屏界面正在显示，含滑动解锁这种非安全锁屏）。
     *
     * 用 [KeyguardManager.isKeyguardLocked]：设了锁屏的手机息屏后也会返回 true。
     * 查询失败按"锁定"处理——宁可多拒绝一次读通知，也不能在锁屏时念出私信。
     */
    fun isDeviceLocked(context: Context): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked ?: true
        } catch (t: Throwable) {
            Dbg.w(TAG, "查询锁屏状态失败，按已锁定处理：${t.message}")
            true
        }
    }

    /** 是否处于"安全锁定"（设了 PIN/图案/密码且当前未解锁）。 */
    fun isSecurelyLocked(context: Context): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isDeviceLocked ?: true
        } catch (t: Throwable) {
            true
        }
    }

    /** 屏幕是否熄灭（没设锁屏的手机息屏时 [isDeviceLocked] 为 false，需要单独看）。 */
    fun isScreenOff(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.let { !it.isInteractive } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private const val TAG = "PilotPrivacy"
}

/**
 * 个人数据（通知正文、记忆、日程、待办、联系人等）能否**朗读出来**的统一判定。
 *
 * 规则（"解锁或注册"）：
 * 1. 锁屏中（[DeviceLockState.isDeviceLocked]）→ **一律不朗读**，提示先解锁；
 *    （锁屏时 TTS 不朗读个人数据，与声纹无关——声纹是软门禁，不是身份认证。）
 * 2. 未锁屏但屏幕熄灭（手机没设锁屏）→ 只有本句声纹 [SpeakerAuth.VERIFIED] 才朗读，
 *    否则提示"点亮/解锁屏幕或先注册声纹"；
 * 3. 已解锁且亮屏 → 允许（用户正拿着手机，解锁本身就是授权）。
 *
 * [auth] 传 null 表示不是语音发起（界面、定时、广播），按"未校验"处理。
 */
object PersonalDataGate {

    enum class Decision {
        ALLOW,

        /** 锁屏中，需要先解锁 */
        DENY_LOCKED,

        /** 息屏且声纹未确认，需要点亮解锁或注册声纹 */
        DENY_NEED_UNLOCK_OR_ENROLL,
        ;

        val allowed: Boolean get() = this == ALLOW
    }

    fun check(context: Context, auth: SpeakerAuth?): Decision {
        if (DeviceLockState.isDeviceLocked(context)) return Decision.DENY_LOCKED
        if (DeviceLockState.isScreenOff(context) && auth?.isOwnerVerified != true) {
            return Decision.DENY_NEED_UNLOCK_OR_ENROLL
        }
        return Decision.ALLOW
    }

    /** 拒绝时给用户听/看的话（不含任何个人内容）。ALLOW 返回 null。 */
    fun denialMessage(decision: Decision, auth: SpeakerAuth? = null): String? = when (decision) {
        Decision.ALLOW -> null
        Decision.DENY_LOCKED -> "手机正在锁屏，为保护隐私我不朗读通知和个人内容，请解锁后再问我。"
        Decision.DENY_NEED_UNLOCK_OR_ENROLL ->
            if (auth == SpeakerAuth.NOT_ENROLLED) {
                "屏幕没亮，也还没注册声纹，为保护隐私我先不朗读。请解锁手机，或在设置里注册声纹。"
            } else {
                "屏幕没亮，没能确认是你本人，为保护隐私我先不朗读。请解锁手机后再问我。"
            }
    }

    /**
     * 文本是否明显是个人数据朗读（兜底识别，供 [Speaker] 在调用方没标记时使用）。
     *
     * 目前只认通知读取的固定开头（`NotificationQueryTool` 输出"最近的通知（最多 N 条）："）。
     * 更可靠的做法是让 query/tool 的结果带隐私标记、调用方直接用 [Speaker.speakPersonal]。
     */
    fun looksPersonal(text: String): Boolean {
        val t = text.trimStart()
        return PERSONAL_PREFIXES.any { t.startsWith(it) }
    }

    private val PERSONAL_PREFIXES = listOf("最近的通知")
}
