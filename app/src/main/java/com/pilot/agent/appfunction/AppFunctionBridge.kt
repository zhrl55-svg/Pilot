package com.pilot.agent.appfunction

import android.content.Context
import android.util.Log

/**
 * AppFunctions 调用方桥接。
 *
 * 平台 API（android.app.appfunctions）在 2026 年仍是 experimental，类名和方法签名
 * 在不同预览版本之间变过。所以这里**不硬编码方法名**——硬编码的结果是系统升级后
 * 静默失效，比直接不可用更糟。这里只做能力探测，把真实调用留到 ROM 侧确认签名后接入：
 *
 * 接入清单（三件事缺一不可）：
 * 1. priv-app 白名单里授予 android.permission.EXECUTE_APP_FUNCTIONS；
 * 2. 设备是 Android 16+，且平台侧 AppFunctions 特性已启用；
 * 3. 目标 App 已用 androidx.appfunctions 注册了 AppFunctionService。
 *
 * 接通之后，AgentLoop 里 "app_func" 分支就可以走结构化调用，
 * 不再需要靠节点树去猜按钮——这是把成功率从 ~66% 拉到 95% 以上的关键一步。
 */
object AppFunctionBridge {

    private const val TAG = "AppFunctions"

    /** 平台类是否存在 + 系统服务是否可获取。 */
    fun isAvailable(ctx: Context): Boolean = runCatching {
        Class.forName("android.app.appfunctions.AppFunctionManager")
        serviceName()?.let { ctx.getSystemService(it) } != null
    }.getOrDefault(false)

    /** 探测 Context 里 AppFunctions 系统服务的名字，拿不到就返回 null。 */
    fun serviceName(): String? = runCatching {
        val field = Context::class.java.getField("APP_FUNCTION_SERVICE")
        field.get(null) as? String
    }.getOrNull()

    /**
     * 真实调用入口。返回 null 表示当前设备/权限不满足，上层应回落到 UI 自动化。
     *
     * 实现示例（签名确认后取消注释）：
     * ```
     * val mgr = ctx.getSystemService(Context.APP_FUNCTION_SERVICE) as AppFunctionManager
     * val response = mgr.executeAppFunction(pkg, functionId, params, cancellationSignal)
     * ```
     */
    fun invoke(ctx: Context, pkg: String, functionId: String, argsJson: String): String? {
        if (!isAvailable(ctx)) {
            Log.i(TAG, "AppFunctions 不可用，回落到 UI 自动化（pkg=$pkg, fn=$functionId）")
            return null
        }
        // 见上方说明：签名确认前不做反射调用，避免静默失效。
        Log.i(TAG, "AppFunctions 可用，但调用签名待 ROM 侧确认：pkg=$pkg fn=$functionId args=$argsJson")
        return null
    }

    /** 诊断信息，显示在设置页里，方便确认 ROM 集成是否生效。 */
    fun diagnostics(ctx: Context): Map<String, String> = mapOf(
        "平台类存在" to runCatching {
            Class.forName("android.app.appfunctions.AppFunctionManager"); "是"
        }.getOrElse { "否" },
        "服务名" to (serviceName() ?: "未探测到"),
        "可调用" to if (isAvailable(ctx)) "是" else "否",
    )
}
