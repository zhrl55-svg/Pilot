package com.pilot.agent.appfunction

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

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
        if (Build.VERSION.SDK_INT < 36) return false
        Class.forName("android.app.appfunctions.AppFunctionManager")
        serviceName()?.let { ctx.getSystemService(it) } != null
    }.getOrDefault(false)

    /** 探测 Context 里 AppFunctions 系统服务的名字，拿不到就返回 null。 */
    fun serviceName(): String? = runCatching {
        val field = Context::class.java.getField("APP_FUNCTION_SERVICE")
        field.get(null) as? String
    }.getOrNull()

    /** API 36+ reflective call keeps API-35 builds compatible. Null means fall back to UI automation. */
    fun invoke(ctx: Context, pkg: String, functionId: String, argsJson: String): String? {
        if (!isAvailable(ctx)) {
            Log.i(TAG, "AppFunctions 不可用，回落到 UI 自动化（pkg=$pkg, fn=$functionId）")
            return null
        }
        return try {
            val parameters = genericDocument(JSONObject(argsJson.ifBlank { "{}" }))
            val requestClass = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest")
            val builderClass = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest\$Builder")
            val builder = builderClass.getConstructor(String::class.java, String::class.java)
                .newInstance(pkg, functionId)
            val setParameters = builderClass.methods.firstOrNull { it.name == "setParameters" && it.parameterCount == 1 }
            if (setParameters != null) setParameters.invoke(builder, parameters)
            val request = builderClass.getMethod("build").invoke(builder)
            val manager = ctx.getSystemService(serviceName()!!) ?: return null
            val method = manager.javaClass.methods.firstOrNull {
                it.name == "executeAppFunction" && it.parameterCount == 4 && it.parameterTypes[0].isInstance(request)
            } ?: return null

            val callbackType = method.parameterTypes[3]
            val latch = CountDownLatch(1)
            var response: Any? = null
            var failure: Throwable? = null
            val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, m, args ->
                when (m.name) {
                    "onResult" -> response = args?.firstOrNull()
                    "onError" -> failure = args?.firstOrNull() as? Throwable
                }
                if (m.name == "onResult" || m.name == "onError") latch.countDown()
                null
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                method.invoke(manager, request, executor, CancellationSignal(), callback)
                if (!latch.await(30, TimeUnit.SECONDS)) return null
            } finally {
                executor.shutdownNow()
            }
            if (failure != null) {
                Log.w(TAG, "AppFunction failed: ${failure?.javaClass?.simpleName}")
                return null
            }
            val resultDoc = response?.javaClass?.methods?.firstOrNull { it.name == "getResultDocument" && it.parameterCount == 0 }
                ?.invoke(response)
            Log.i(TAG, "AppFunction executed: $pkg/$functionId")
            "应用工具已执行：$functionId" + (resultDoc?.let { "\n结果：${it.toString().take(1200)}" } ?: "")
        } catch (t: Throwable) {
            Log.w(TAG, "AppFunction 调用失败：${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}")
            null
        }
    }

    private fun genericDocument(values: JSONObject): Any {
        val builderClass = Class.forName("android.app.appsearch.GenericDocument\$Builder")
        val builder = builderClass.getConstructor(String::class.java, String::class.java)
            .newInstance("pilot-agent", "${System.currentTimeMillis()}")
        for (key in values.keys()) {
            val value = values.opt(key) ?: continue
            val methodName = when (value) {
                is String -> "setPropertyString"
                is Boolean -> "setPropertyBoolean"
                is Int, is Long -> "setPropertyLong"
                is Number -> "setPropertyDouble"
                else -> continue
            }
            val elementType = when (methodName) {
                "setPropertyString" -> String::class.java
                "setPropertyBoolean" -> Boolean::class.javaPrimitiveType!!
                "setPropertyLong" -> Long::class.javaPrimitiveType!!
                else -> Double::class.javaPrimitiveType!!
            }
            val setter = builderClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isArray
            } ?: continue
            val coerced = when (value) {
                is Int -> value.toLong()
                is Number -> if (methodName == "setPropertyDouble") value.toDouble() else value
                else -> value
            }
            val array = java.lang.reflect.Array.newInstance(elementType, 1)
            java.lang.reflect.Array.set(array, 0, coerced)
            setter.invoke(builder, key, array)
        }
        return builderClass.getMethod("build").invoke(builder)
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
