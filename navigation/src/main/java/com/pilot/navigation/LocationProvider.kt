package com.pilot.navigation

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener

/**
 * 当前位置提供者（双通道）。
 *
 * ## 为什么是双通道
 * 导航必须有一个**持续的**位置流（只取一次的话，导航中位置不更新会走偏）。
 * 实测（P40 Pro / HarmonyOS 4.0）：
 * - 高德 `AMapLocationClient`：精度好、还带 `city`（地点搜索的城市偏置要用），
 *   但**在 :navigation 副进程里回调很慢/可能不来**（APSService 起来了但
 *   onLocationChanged 不触发）
 * - 系统 `LocationManager`：精度差些、没有 city，但**一定拿得到**（GNSS/NETWORK 直连）
 *
 * 所以两个都跑，**谁先来用谁**，city 只在高德那条通道补。
 *
 * ## 对外契约
 * - [lat] / [lon] / [city]：当前最佳位置
 * - [onLocation]：每次位置更新都回调（引导播报靠它推进路线）
 * - [ready]：是否已经拿到过有效坐标
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "PilotNavLoc"
    }

    // ------------------------------------------------------------ 对外状态

    @Volatile
    var lat: Double = 0.0
        private set

    @Volatile
    var lon: Double = 0.0
        private set

    /** 城市名（只有高德通道能提供，用于地点搜索的 city 偏置）。 */
    @Volatile
    var city: String? = null
        private set

    /** 当前朝向（度，正北 = 0，顺时针）。没有方向数据时保留上一次的值。 */
    @Volatile
    var bearing: Float = 0f
        private set

    /** 是否已经拿到过有效坐标。 */
    @Volatile
    var ready = false
        private set

    /** 位置更新回调（引导播报靠它推进路线）。 */
    @Volatile
    var onLocation: ((Double, Double) -> Unit)? = null

    // ------------------------------------------------------------ 内部状态

    private var amapClient: AMapLocationClient? = null
    private var systemLocationManager: android.location.LocationManager? = null
    private var systemListener: android.location.LocationListener? = null

    /** 已经用过系统兜底坐标（防止系统的粗坐标覆盖高德的准坐标）。 */
    @Volatile
    private var usedSystemFallback = false

    /** 高德通道是否已经给过坐标（有的话系统坐标只做兜底，不覆盖）。 */
    @Volatile
    private var hasAmapFix = false

    // ------------------------------------------------------------ 生命周期

    fun start() {
        if (amapClient != null || systemLocationManager != null) return
        startAmapLocation()
        startSystemLocation()
    }

    fun stop() {
        runCatching { amapClient?.stopLocation() }
        amapClient = null
        val sl = systemListener
        if (sl != null) runCatching { systemLocationManager?.removeUpdates(sl) }
        systemListener = null
        systemLocationManager = null
    }

    // ------------------------------------------------------------ 通道 1：高德

    private fun startAmapLocation() {
        runCatching {
            AmapNaviEngine.agreePrivacy(context)
            val option = AMapLocationClientOption().apply {
                setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                setOnceLocation(false)
                setInterval(2000L)
                // 要 city 做 POI 搜索的城市偏置
                setNeedAddress(true)
            }
            amapClient = AMapLocationClient(context).apply {
                setLocationOption(option)
                setLocationListener(object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation?) {
                        val l = location ?: return
                        if (l.latitude == 0.0 || l.longitude == 0.0) return
                        // 高德坐标更准，**无条件覆盖**（包括系统兜底的值）
                        hasAmapFix = true
                        usedSystemFallback = false
                        lat = l.latitude
                        lon = l.longitude
                        if (l.hasBearing()) bearing = l.bearing
                        l.city?.takeIf { it.isNotBlank() }?.let { city = it }
                        if (!ready) {
                            ready = true
                            NLog.i(TAG, "高德定位就绪 $lat,$lon city=$city")
                        }
                        onLocation?.invoke(lat, lon)
                    }
                })
                startLocation()
                NLog.i(TAG, "高德定位已启动")
            }
        }.onFailure {
            NLog.e(TAG, "高德定位启动失败", it)
        }
    }

    // ------------------------------------------------------------ 通道 2：系统兜底

    private fun startSystemLocation() {
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            systemLocationManager = lm

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) {
                    if (loc.latitude == 0.0 || loc.longitude == 0.0) return
                    // 高德已经给过准坐标：系统这条只保留兜底职能，不覆盖
                    if (hasAmapFix) return
                    if (usedSystemFallback) {
                        lat = loc.latitude
                        lon = loc.longitude
                        if (loc.hasBearing()) bearing = loc.bearing
                        onLocation?.invoke(loc.latitude, loc.longitude)
                        return
                    }
                    usedSystemFallback = true
                    lat = loc.latitude
                    lon = loc.longitude
                    if (loc.hasBearing()) bearing = loc.bearing
                    ready = true
                    NLog.i(TAG, "系统定位兜底就绪 $lat,$lon")
                    onLocation?.invoke(loc.latitude, loc.longitude)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            systemListener = listener

            // GPS + NETWORK 都注册，谁先来用谁
            for (p in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            )) {
                runCatching {
                    if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, 1000L, 5f, listener, context.mainLooper)
                    }
                }
            }

            // 立刻取一次缓存位置（有权限时立刻就有，省得等 GNSS 冷启动）
            runCatching {
                val cached = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (cached != null && cached.latitude != 0.0 && !usedSystemFallback) {
                    usedSystemFallback = true
                    lat = cached.latitude
                    lon = cached.longitude
                    if (cached.hasBearing()) bearing = cached.bearing
                    ready = true
                    NLog.i(TAG, "系统定位缓存 $lat,$lon")
                    onLocation?.invoke(lat, lon)
                }
            }
            NLog.i(TAG, "系统定位兜底已启动")
        }.onFailure {
            NLog.w(TAG, "系统定位启动失败 ${it.message}")
        }
    }
}
