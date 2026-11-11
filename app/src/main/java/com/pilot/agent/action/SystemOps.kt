package com.pilot.agent.action

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.pilot.agent.Dbg

/**
 * 系统能力直调：不经界面、不点屏幕，直接把事办了。
 *
 * ## 这才是「执行力」
 * 「打开音量设置页」只是导航；「把音量调大 20%」是执行。两者差一个数量级。
 * 凡是有系统 API 的，一律走这里，连 Intent 都不发。
 *
 * ## 边界
 * 只有**系统级能力**能这么干。App 内部的功能（发微信、查快递）没有公开 API，
 * 只能走界面自动化——那是另一条链路，成功率完全不同。
 */
/** 音量/亮度的调节意图：相对加减，还是设定到某个绝对值。 */
sealed interface LevelChange {
    data class Delta(val percent: Int) : LevelChange
    data class Absolute(val percent: Int) : LevelChange
}

class SystemOps(private val ctx: Context) {

    private val audio: AudioManager by lazy { ctx.getSystemService(AudioManager::class.java) }

    // ------------------------------------------------------------ 音量

    /**
     * 相对调音量。
     *
     * 播报要**说清幅度**：「音量 40% 调到 60%（+20%）」。
     * 只说「调到 60%」用户不知道是按什么标准调的；说清 ±N% 才能对上他刚才那句话。
     * 被上下限夹住时按**实际变化**说（90% → 100% 是 +10%，不是 +20%）。
     *
     * @param deltaPercent 正数调大、负数调小
     */
    fun adjustVolume(deltaPercent: Int, stream: Int = AudioManager.STREAM_MUSIC): String? {
        val am = audio ?: return null
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        if (max <= 0) return null
        val target = (cur + Math.round(max * deltaPercent / 100f)).coerceIn(0, max)
        return runCatching {
            am.setStreamVolume(stream, target, 0)
            Dbg.i(TAG, "音量 ${cur * 100 / max}% → ${target * 100 / max}%（请求 $deltaPercent%）")
            levelMessage("音量", cur, target, max, target >= max, target <= 0)
        }.getOrNull()
    }

    /** 绝对设音量：用户说「音量 60%」「调到 60%」。 */
    fun setVolume(percent: Int, stream: Int = AudioManager.STREAM_MUSIC): String? {
        val am = audio ?: return null
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        if (max <= 0) return null
        val target = Math.round(max * percent.coerceIn(0, 100) / 100f).coerceIn(0, max)
        return runCatching {
            am.setStreamVolume(stream, target, 0)
            Dbg.i(TAG, "音量 ${cur * 100 / max}% → ${target * 100 / max}%（绝对值 $percent%）")
            levelMessage("音量", cur, target, max, target >= max, target <= 0)
        }.getOrNull()
    }

    fun currentVolumePercent(stream: Int = AudioManager.STREAM_MUSIC): Int? {
        val am = audio ?: return null
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) return null
        return am.getStreamVolume(stream) * 100 / max
    }

    // ------------------------------------------------------------ 亮度

    /**
     * 调亮度。
     *
     * 需要 `WRITE_SETTINGS`（特殊权限，只能用户手动开）。
     * 没权限时返回 [NEED_WRITE_SETTINGS]，由上层提示并拉起授权页——
     * 不要静默失败，用户会以为「亮度不听话」。
     */
    fun adjustBrightness(deltaPercent: Int): String? =
        writeBrightness { cur -> (cur + Math.round(255 * deltaPercent / 100f)).toFloat() }

    /** 绝对设亮度：用户说「亮度 60%」。 */
    fun setBrightness(percent: Int): String? =
        writeBrightness { _ -> 255f * percent.coerceIn(0, 100) / 100f }

    private fun writeBrightness(newValue: (Int) -> Float): String? {
        if (!Settings.System.canWrite(ctx)) {
            Dbg.w(TAG, "亮度：缺 WRITE_SETTINGS")
            return NEED_WRITE_SETTINGS
        }
        val resolver = ctx.contentResolver
        val cur = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val target = newValue(cur).toInt().coerceIn(MIN_BRIGHTNESS, 255)
        return runCatching {
            // 先切手动模式，否则自动亮度会立刻把值改回去
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
            Dbg.i(TAG, "亮度 ${cur * 100 / 255}% → ${target * 100 / 255}%")
            levelMessage("亮度", cur, target, 255, target >= 255, target <= MIN_BRIGHTNESS)
        }.getOrNull()
    }

    /**
     * 统一的话术：说清「从多少到多少、变了多少」，并在到顶/到底时说明。
     * 例：`音量 40% 调到 60%（+20%）` / `音量 90% 调到 100%（+10%，已到最大）`
     */
    private fun levelMessage(
        name: String,
        fromRaw: Int,
        toRaw: Int,
        maxRaw: Int,
        atMax: Boolean,
        atMin: Boolean,
    ): String {
        val from = fromRaw * 100 / maxRaw
        val to = toRaw * 100 / maxRaw
        val delta = to - from
        if (delta == 0) {
            val msg = when {
                atMax -> "$name 已经是最大（$to%）"
                atMin -> "$name 已经是最小（$to%）"
                else -> "$name 保持在 $to%"
            }
            Dbg.i(TAG, "播报：$msg")
            return msg
        }
        val sign = if (delta > 0) "+" else ""
        val limit = when {
            atMax -> "，已到最大"
            atMin -> "，已到最小"
            else -> ""
        }
        val msg = "$name $from% 调到 $to%（$sign$delta%$limit）"
        Dbg.i(TAG, "播报：$msg")
        return msg
    }

    fun openWriteSettingsPage() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ------------------------------------------------------------ 打电话

    /**
     * 直接拨号（不是打开拨号盘）。
     * 需要 `CALL_PHONE` 运行时权限；没有就退化成 ACTION_DIAL（只填号码，等用户按拨出）。
     */
    fun call(number: String): String? {
        val tel = "tel:${number.trim()}"
        val direct = Intent(Intent.ACTION_CALL, Uri.parse(tel)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(direct) }.isSuccess) {
            return "正在呼叫 $number"
        }
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse(tel)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { ctx.startActivity(dial) }.isSuccess) {
            "已填好号码 $number，需要你按一下拨出（缺通话权限）"
        } else {
            null
        }
    }

    // ------------------------------------------------------------ 闹钟 / 计时器

    fun setAlarm(hour: Int, minute: Int, label: String = "手机助手"): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    fun setTimer(seconds: Int, label: String = "手机助手"): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    companion object {
        private const val TAG = "Pilot"
        const val NEED_WRITE_SETTINGS = "__need_write_settings__"

        /** 屏幕最低亮度别调到 0，那样就黑屏了，用户以为死机 */
        private const val MIN_BRIGHTNESS = 15

        /** 没说程度时的默认步长。用户说「增大音量」就是 ±20%，不是 ±25%。 */
        const val DEFAULT_STEP = 20

        /**
         * 自然语言 → 调节意图。
         *
         * 关键区别（旧版混在一起，导致「音量调到 60%」被当成 +60%）：
         * - **绝对**：调到 / 设成 / 变成 / 改成 60%  → Absolute(60)
         * - **相对**：增大 20% / 减小 20% / 音量 60%（裸百分比按"设成"处理）
         * - **方向词**：大一点 → Delta(+10)、大很多 → Delta(+40)、只给方向 → Delta(±20)
         */
        fun parseLevelChange(text: String): LevelChange? {
            val t = text

            // 1) 绝对：调到/设成/变成/改成 N%
            Regex("(?:调到|调至|调成|设成|设为|设置到|变成|变到|改成|改为|转到|到)\\s*(\\d{1,3})\\s*%")
                .find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    return LevelChange.Absolute(it.coerceIn(0, 100))
                }

            // 2) 相对：增大/减小 … N%
            Regex("(?:增大|增加|加大|调大|提高|升高|上调|大)\\s*(\\d{1,3})\\s*%").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.let {
                    return LevelChange.Delta(it.coerceIn(0, 100))
                }
            Regex("(?:减小|减少|降低|调小|下调|小)\\s*(\\d{1,3})\\s*%").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.let {
                    return LevelChange.Delta(-it.coerceIn(0, 100))
                }

            // 3) 裸百分比 = 设成这个值（"音量 60%" 的直觉就是设到 60%）
            Regex("(\\d{1,3})\\s*%").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return LevelChange.Absolute(it.coerceIn(0, 100))
            }

            // 4) 最大 / 最小
            if (t.contains("最大") || t.contains("最响") || t.contains("最亮")) return LevelChange.Delta(100)
            if (t.contains("最小") || t.contains("最轻") || t.contains("最暗")) return LevelChange.Delta(-100)

            // 5) 只给方向
            val up = t.contains("大") || t.contains("高") || t.contains("响") || t.contains("亮")
            val down = t.contains("小") || t.contains("低") || t.contains("轻") || t.contains("暗")
            if (!up && !down) return null
            val step = when {
                t.contains("一点") || t.contains("一些") || t.contains("稍微") -> 10
                t.contains("很多") || t.contains("大幅") || t.contains("大量") || t.contains("一大") -> 40
                else -> DEFAULT_STEP
            }
            return LevelChange.Delta(if (up) step else -step)
        }
    }
}
