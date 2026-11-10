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
class SystemOps(private val ctx: Context) {

    private val audio: AudioManager by lazy { ctx.getSystemService(AudioManager::class.java) }

    // ------------------------------------------------------------ 音量

    /**
     * 调音量。
     * @param deltaPercent 正数调大、负数调小；0 表示不调
     * @return 给用户看的描述
     */
    fun adjustVolume(deltaPercent: Int, stream: Int = AudioManager.STREAM_MUSIC): String? {
        val am = audio ?: return null
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        if (max <= 0) return null
        val target = (cur + Math.round(max * deltaPercent / 100f)).coerceIn(0, max)
        return runCatching {
            am.setStreamVolume(stream, target, 0)
            val from = cur * 100 / max
            val to = target * 100 / max
            Dbg.i(TAG, "音量 $from% → $to%（stream=$stream）")
            "音量 $from% 调到 $to%"
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
    fun adjustBrightness(deltaPercent: Int): String? {
        if (!Settings.System.canWrite(ctx)) {
            Dbg.w(TAG, "亮度：缺 WRITE_SETTINGS")
            return NEED_WRITE_SETTINGS
        }
        val resolver = ctx.contentResolver
        val cur = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val target = (cur + Math.round(255 * deltaPercent / 100f)).coerceIn(MIN_BRIGHTNESS, 255)
        return runCatching {
            // 先切手动模式，否则自动亮度会立刻把值改回去
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
            val from = cur * 100 / 255
            val to = target * 100 / 255
            Dbg.i(TAG, "亮度 $from% → $to%")
            "亮度 $from% 调到 $to%"
        }.getOrNull()
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

        /**
         * 把"大一点/小一点/调到 50%"翻译成百分比增量。
         *
         * 这是执行力的关键一环——用户不会说"调 20%"，他说"声音大一点"。
         */
        fun parseDelta(text: String): Int? {
            val t = text
            // 显式百分比 / 数字
            Regex("(\\d{1,3})\\s*%").find(t)?.let { m ->
                val target = m.groupValues[1].toIntOrNull() ?: return null
                return target
            }
            // 最大 / 最小
            if (t.contains("最大") || t.contains("最响") || t.contains("最亮")) return 100
            if (t.contains("最小") || t.contains("最轻") || t.contains("最暗")) return -100
            // 方向 + 程度
            val up = t.contains("大") || t.contains("高") || t.contains("响") || t.contains("亮")
            val down = t.contains("小") || t.contains("低") || t.contains("轻") || t.contains("暗")
            if (!up && !down) return null
            val step = when {
                t.contains("一点") || t.contains("一些") || t.contains("稍微") -> 15
                t.contains("很多") || t.contains("大幅") || t.contains("大量") -> 40
                else -> 25
            }
            return if (up) step else -step
        }
    }
}
