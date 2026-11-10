package com.pilot.navigation

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import java.io.File

/**
 * ROM 预置素材的挂载。
 *
 * ## 要解决什么
 * 高德 SDK 用 `context.getAssets()` 读它自己的素材（导航音色 8.6MB、
 * 离线地图包 11MB、图标、多语言文案…共 34MB）。塞进 APK 就让主包
 * 从 89MB 涨到 165MB。
 *
 * 自研 ROM 路线下这些是**资源不是代码**，放 `/system/vendor/amap/assets/`
 * 是免费的——APK 只留 class。
 *
 * ## 为什么不能用继承 AssetManager 的办法
 * `AssetManager` 是 **final**，构造器是包私有，继承不了。
 * 一开始想写个代理类转发 open/openFd/list——行不通。
 *
 * ## 正确做法：AssetManager.addAssetPath
 * 系统提供了公开 API：`AssetManager.addAssetPath(String path)`
 * ——给 AssetManager **追加一个资源搜索路径**。
 * 加进去之后，SDK 读 `tts/xxx` 时会先在 APK 找、找不到就去 ROM 找。
 *
 * 零拷贝（不复制文件），一行 API。
 *
 * ## 用法
 * ```kotlin
 * val ctx = NavContext(base)   // getAssets() 里做了 addAssetPath
 * AMapServices.initialize(ctx, key)
 * ```
 */
object RomAssetManager {

    /** ROM 里的高德素材根。刷了 ROM 才存在。 */
    const val ROM_ASSETS_ROOT = "/system/vendor/amap/assets"

    /** ROM 里的高德 so 根。 */
    const val ROM_LIBS_ROOT = "/system/vendor/amap/lib"

    /** 已经挂载过就不要再挂（addAssetPath 会重复添加路径）。 */
    @Volatile private var mounted = false

    /**
     * 给 [base] 的 AssetManager 追加 ROM 资源路径。
     * @return true = ROM 素材可用
     *
     * ★ `addAssetPath` 是**隐藏 API**（AOSP 有、SDK 里没有），
     *   所以用反射调。系统 ROM 上一定存在这个方法；
     *   万一哪天没了就退回「素材全在 APK 里」的老路（功能不减，只是包大）。
     */
    fun mount(base: AssetManager): Boolean {
        if (mounted) return true
        synchronized(this) {
            if (mounted) return true
            val root = File(ROM_ASSETS_ROOT)
            if (!root.isDirectory) return false
            return try {
                val m = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                val cookie = m.invoke(base, ROM_ASSETS_ROOT) as? Int ?: 0
                mounted = cookie != 0
                android.util.Log.i("PilotNav", "ROM 素材挂载 cookie=$cookie path=$ROM_ASSETS_ROOT")
                mounted
            } catch (t: Throwable) {
                android.util.Log.w("PilotNav", "ROM 素材挂载失败（addAssetPath 不可用）：${t.message}")
                false
            }
        }
    }
}

/**
 * 给导航 SDK 用的 Context：assets 里挂上了 ROM 素材路径。
 */
class NavContext(base: Context) : ContextWrapper(base) {
    override fun getAssets(): AssetManager {
        val a = super.getAssets()
        RomAssetManager.mount(a)
        return a
    }
}
