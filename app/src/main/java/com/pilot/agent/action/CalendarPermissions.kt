package com.pilot.agent.action

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 日历读写权限的申请入口（m-20261127-103）。
 *
 * 日历权限是运行时权限，必须在 Activity 上弹系统授权框。工具层（CalendarTool）跑在协程里，
 * 拿不到 Activity，所以这里做一个极简「询问者」注册点：
 * - MainActivity 在 onCreate 里 [attach] 自己的 ActivityResultLauncher，onDestroy 里 [detach]；
 * - CalendarTool 调用 [ensure]：已授权直接 true；否则转到主线程发起申请并等待结果；
 * - 没有任何界面（后台广播/语音触发，Activity 不在）→ 立即 false，由调用方退回 Intent 兜底。
 */
object CalendarPermissions {

    private val REQUIRED = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    @Volatile
    private var requester: ((Array<String>) -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    fun granted(ctx: Context): Boolean = REQUIRED.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** MainActivity 注册：把手头的 RequestMultiplePermissions launcher 转成函数。 */
    fun attach(request: (Array<String>) -> Unit) {
        requester = request
    }

    fun detach() {
        requester = null
    }

    /**
     * 确保已授权。@return true=已授权；false=用户拒绝、界面不在或等待超时。
     */
    suspend fun ensure(ctx: Context, timeoutMs: Long = 20_000L): Boolean {
        if (granted(ctx)) return true
        val launch = requester ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        return try {
            withContext(Dispatchers.Main.immediate) { launch(REQUIRED) }
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            pending = null
        }
    }

    /** ActivityResult 回调：两个权限都授予才算成功。 */
    fun onResult(grants: Map<String, Boolean>) {
        pending?.complete(REQUIRED.all { grants[it] == true })
    }
}
