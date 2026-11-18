package com.pilot.agent.query

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pilot.agent.Dbg
import com.pilot.agent.PilotApp
import com.pilot.navigation.SdkPoiSearch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 周边搜索：走高德 POI 检索（SDK），不是网页搜索。
 *
 * 「附近的药店」「周边的加油站」这类需求必须拿到**当前位置周边**的真实 POI、
 * 距离和地址；通用网页搜索给不了这些。高德 SDK 用 Android 平台 Key，
 * 在本机比 Web Key 稳（见 [SdkPoiSearch] 头注释）。
 *
 * 定位优先用系统缓存（快），没有就等一次 4 秒内的定位回调。
 * 没有定位权限 / 定位没就绪时明确告诉用户，不拿全国结果糊弄。
 */
class NearbySearchTool : QueryTool {

    override val name: String = "nearby_search"

    private val nearbyHints = listOf("附近", "周边", "周围", "最近的")

    override fun matches(goal: String): Boolean = nearbyHints.any(goal::contains)

    override suspend fun execute(goal: String): String = withContext(Dispatchers.IO) {
        val keyword = normalize(goal)
        if (keyword.isBlank()) {
            return@withContext "想找附近的什么？例如「附近的药店」。"
        }
        val ctx = PilotApp.instance
        if (!hasLocationPermission(ctx)) {
            return@withContext "周边搜索需要定位权限，请先在系统设置里允许 Pilot 定位。"
        }
        val loc = currentLocation(ctx)
            ?: return@withContext "定位还没准备好，请稍后再试。"
        Dbg.i(TAG, "高德周边搜索 keyword=$keyword at=${loc.latitude},${loc.longitude}")

        var error: String? = null
        val candidates = withTimeoutOrNull(12_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<List<SdkPoiSearch.Candidate>> { cont ->
                    val search = SdkPoiSearch(ctx)
                    search.search(
                        keyword = keyword,
                        city = null,
                        currentLat = loc.latitude,
                        currentLon = loc.longitude,
                        onResult = { list ->
                            if (cont.isActive) cont.resume(list)
                        },
                        onError = { msg ->
                            Dbg.w(TAG, "高德周边搜索失败：$msg")
                            error = msg
                            if (cont.isActive) cont.resume(emptyList())
                        },
                    )
                }
            }
        } ?: return@withContext "周边搜索超时，请稍后再试。"

        if (candidates.isEmpty()) {
            return@withContext error ?: "附近没有找到「$keyword」。"
        }
        buildString {
            append("附近搜索：").append(keyword)
                .append("（").append(candidates.size).append(" 条 · 高德）")
            candidates.take(5).forEachIndexed { i, c ->
                append("\n\n").append(i + 1).append(". ").append(c.name)
                if (c.address.isNotBlank()) append("\n   ").append(c.address)
                if (c.distance > 0) append("\n   ").append(formatDistance(c.distance))
            }
        }
    }

    /** 「搜一下周边的药店」→「药店」。 */
    private fun normalize(goal: String): String {
        var q = goal.trim()
        val prefixes = listOf(
            "帮我搜一下", "帮我搜索", "帮我找", "帮我查", "请帮我",
            "搜一下", "搜索一下", "搜索", "搜", "查一下", "查查", "查",
            "找一下", "看看", "附近的", "附近", "周边的", "周边",
            "周围的", "周围", "最近的", "最近",
        )
        prefixes.forEach { prefix ->
            if (q.startsWith(prefix)) q = q.removePrefix(prefix).trim()
        }
        val suffixes = listOf("在哪里", "在哪", "有哪些", "哪里有", "怎么走", "怎么去")
        suffixes.forEach { suffix ->
            if (q.endsWith(suffix)) q = q.removeSuffix(suffix).trim()
        }
        return q.trim(' ', '，', ',', '。', '.', '？', '?', '！', '!')
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 先取系统缓存（快）；没有就等一次定位回调（最多 4 秒）。 */
    private fun currentLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        val last = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.latitude != 0.0 && it.longitude != 0.0 }
            .maxByOrNull { it.time }
        if (last != null) return last

        val provider = providers.firstOrNull {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return null
        val holder = AtomicReference<Location>()
        val latch = CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.latitude != 0.0 && location.longitude != 0.0) {
                    holder.set(location)
                    latch.countDown()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
        runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        latch.await(4, TimeUnit.SECONDS)
        runCatching { lm.removeUpdates(listener) }
        return holder.get()
    }

    private fun formatDistance(meters: Int): String =
        if (meters < 1000) "$meters m" else String.format(java.util.Locale.CHINA, "%.1f km", meters / 1000.0)

    private companion object {
        const val TAG = "PilotNearby"
    }
}
