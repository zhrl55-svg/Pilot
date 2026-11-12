package com.pilot.navigation

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 导航地图的日夜判断。
 *
 * 高德自带的 `isAutoNaviViewNightMode` 在真机上并不可靠：
 * 刚进导航界面时定位还没就绪，它先按白天渲染，之后也不会纠正
 * （实测 20:10 地图仍然是白底）。
 *
 * 所以改成自己算：
 * 1. 有定位坐标时，按经纬度和当天日期算**当地日出/日落**；
 * 2. 没有定位（或极昼/极夜）时，退回 18:00–06:00。
 *
 * 只做纯计算，不碰 SDK，方便单独核对。
 */
object NavDayNight {

    /** 没有定位时的兜底：18:00 后算夜间。 */
    private const val FALLBACK_NIGHT_HOUR = 18

    /** 没有定位时的兜底：06:00 前算夜间。 */
    private const val FALLBACK_DAY_HOUR = 6

    /** 太阳中心低于地平线 0.833°（含大气折射）时算夜晚。 */
    private const val SUNRISE_ZENITH_DEG = 90.833

    /**
     * 现在是夜间吗？
     *
     * @param lat 纬度（0,0 表示还没有定位）
     * @param lon 经度
     * @param now 设备当前时间（用系统时区）
     */
    fun isNight(lat: Double, lon: Double, now: Calendar = Calendar.getInstance()): Boolean {
        val hasLocation = lat.isFinite() && lon.isFinite() &&
            abs(lat) <= 90.0 && abs(lon) <= 180.0 && (lat != 0.0 || lon != 0.0)
        val window = if (hasLocation) sunWindowMinutes(lat, lon, now) else null
        if (window == null) return isFallbackNight(now)

        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60.0 + now.get(Calendar.MINUTE)
        val (sunrise, sunset) = window
        return minutes < sunrise || minutes >= sunset
    }

    private fun isFallbackNight(now: Calendar): Boolean {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return hour >= FALLBACK_NIGHT_HOUR || hour < FALLBACK_DAY_HOUR
    }

    /**
     * 当地日出/日落（本地时间，分钟）。
     *
     * 用 NOAA 的简化太阳位置公式：
     * - gamma：一年中的日角
     * - eqTime：均时差（分钟）
     * - decl：太阳赤纬
     * - ha：日出/日落时角
     *
     * 极昼/极夜时 acos 参数超出 [-1,1]，返回 null 交给兜底。
     */
    private fun sunWindowMinutes(lat: Double, lon: Double, now: Calendar): Pair<Double, Double>? {
        val dayOfYear = now.get(Calendar.DAY_OF_YEAR)
        val gamma = 2.0 * Math.PI / 365.0 *
            (dayOfYear - 1 + (now.get(Calendar.HOUR_OF_DAY) - 12) / 24.0)

        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) -
                0.040849 * sin(2 * gamma)
            )

        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) +
            0.00148 * sin(3 * gamma)

        val latRad = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(SUNRISE_ZENITH_DEG)) /
            (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null
        val haDeg = Math.toDegrees(acos(cosHa))

        // NOAA：日出/日落为 UTC 分钟；加时区偏移得到本地分钟。
        val tzMinutes = now.timeZone.getOffset(now.timeInMillis) / 60_000.0
        val sunrise = normalizeMinutes(720.0 - 4.0 * (lon + haDeg) - eqTime + tzMinutes)
        val sunset = normalizeMinutes(720.0 - 4.0 * (lon - haDeg) - eqTime + tzMinutes)
        if (sunrise == sunset) return null
        return sunrise to sunset
    }

    private fun normalizeMinutes(minutes: Double): Double {
        val m = minutes % 1440.0
        return if (m < 0) m + 1440.0 else m
    }
}
