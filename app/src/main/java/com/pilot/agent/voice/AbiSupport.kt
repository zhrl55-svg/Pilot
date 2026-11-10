package com.pilot.agent.voice

import android.os.Build

/**
 * 设备是不是原生 ARM。
 *
 * 不能只看 `Build.SUPPORTED_ABIS` 里有没有 arm64 —— x86_64 模拟器的 abilist 是
 * `x86_64,arm64-v8a`，那是 ARM 翻译层对外声称的支持，真加载 arm64 的 .so 要么走
 * 翻译慢一个数量级，要么直接 UnsatisfiedLinkError。
 *
 * 主 ABI（`SUPPORTED_ABIS[0]`）才是这个设备真正原生执行的指令集。
 */
internal fun isNativeArm(): Boolean {
    val primary = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    return primary == "arm64-v8a" || primary == "armeabi-v7a" || primary == "armeabi"
}

/** 给 UI 显示的一句话说明 */
internal fun abiDiagnostic(): String =
    "主 ABI=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}，需 arm64 真机"

/**
 * sherpa-onnx 的 AAR 覆盖到的 ABI。带 x86_64，所以模拟器上 ASR 能原生跑。
 */
internal fun isSupportedAbi(): Boolean {
    val primary = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    return primary == "arm64-v8a" || primary == "armeabi-v7a" ||
        primary == "armeabi" || primary == "x86_64" || primary == "x86"
}

/**
 * 能不能尝试加载 ARM 的 .so。
 *
 * x86 模拟器的 abilist 里会列出 arm64-v8a（ARM 翻译层），但只有 APK 里**没有**
 * 对应 x86_64 库时才会走翻译；一旦同时存在 x86_64 库（比如 sherpa），
 * PackageManager 就定 primaryCpuAbi=x86_64，ARM 库根本不会被加载。
 * 因此这个函数只用于诊断，不作为可用性判断依据。
 */
internal fun canAttemptArmLibs(): Boolean {
    if (isNativeArm()) return true
    val translated = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    return translated && com.pilot.agent.BuildConfig.DEBUG
}
