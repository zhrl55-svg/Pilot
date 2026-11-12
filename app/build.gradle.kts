import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pilot.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pilot.agent"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("zh", "en")
        // 高德 Key（密钥，放 local.properties 不入库）。
        // ★ local.properties 的键**不会**自动变成 gradle project property
        //   （那是 Android Studio 的行为，命令行构建拿不到）——
        //   必须自己读 Properties 文件，否则 manifestPlaceholder 是空串，
        //   高德直接报 USERKEY 错误（踩过：value="" 在 aapt2 dump 里看出来的）。
        // 高德 Key：优先用 pilot 专属的（AMAP_KEY_PILOT），没配就回退老的
        // （老 Key 绑在 nav-agent 包名下，SDK 算路会被拒，但 POI/定位还能用）
        manifestPlaceholders["AMAP_KEY"] =
            localProp("AMAP_KEY_PILOT").ifBlank { localProp("AMAP_KEY") }

        // ★ 只打 arm64-v8a。
        //   原来四个架构全打：arm64 52MB + x86 14MB + x86_64 14MB + v7a 11MB（压缩后），
        //   后三个加起来 39MB 纯粹是给模拟器和 32 位老机用的，真机一个字节都用不上。
        //   需要模拟器调试时加 -PallAbis=true 打全架构。
        if (providers.gradleProperty("allAbis").orNull != "true") {
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    signingConfigs {
        // 自研 ROM 的关键一步：平台签名才能拿 signature|privileged 权限。
        // 不把平台密钥放进仓库，构建机通过 keystore/platform.jks + 环境变量注入。
        val platformStore = file("../keystore/platform.jks")
        if (platformStore.exists()) {
            create("platform") {
                storeFile = platformStore
                storePassword = System.getenv("PILOT_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("PILOT_KEY_ALIAS") ?: "platform"
                keyPassword = System.getenv("PILOT_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 不加 applicationIdSuffix：debug/release 同包名 com.pilot.agent。
            //
            // 理由：高德 Android 平台 Key 注册的是**包名**，一个包名一个 Key。
            // 如果 debug 带 .debug 后缀，就得在高德控制台注册两个 Key
            // （com.pilot.agent 和 com.pilot.agent.debug），维护成本翻倍。
            // 同包名后只需要注册一次，区分环境靠 versionNameSuffix。
            //
            // 注：包名不是 22 INVALID_USER_SCODE 的原因。
            // 那个错误的真因是**安全码格式**——高德控制台要求带冒号的
            // SHA1（26:F6:15:CD:...），填不带冒号的会被判为无效
            // （2026-11-10 老板实测确认）。
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("platform") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true   // 沙箱跨进程接口（ISandboxService/ResultCallback/IHostBridge）
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // sherpa-onnx 与 MNN 的 .so 只带 arm64-v8a / armeabi-v7a，x86_64 模拟器上没有对应实现。
        // 这里不裁剪 ABI：模拟器仍能安装并运行（端侧模型加载失败后走降级路径），
        // 真机（arm64）则直接生效。
        // ★ 沙箱 W^X：proot 二进制伪装成 libproot.so 放 jniLibs，必须从
        //   nativeLibraryDir 里的**真实文件** exec。useLegacyPackaging=false 时
        //   AGP 会忽略 manifest 的 extractNativeLibs=true，so 留在 APK 内不解包
        //   → nativeLibraryDir 里拿不到可执行文件。必须 true（与 manifest 保持一致）。
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        // rootfs tar 与 proot 备份不能被 aapt 压缩：RootfsManager 用
        // assets.openFd 直接 mmap 读 tar（minis 同款配置，踩过）。
        noCompress += listOf("tar", "tar.gz", "proot-aarch64")
    }
}

dependencies {
    implementation(files("libs/tailscale-bridge.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
      implementation(libs.jsch)
    implementation(libs.androidx.media3.exoplayer)

    // 端侧语音识别：sherpa-onnx（SenseVoice-Small）
    implementation(files("libs/sherpa-onnx.aar"))

    // 导航模块（高德 SDK 87MB so 在 :navigation 里，见 NAV_MODULE_PLAN.md）
    implementation(project(":navigation"))
}

/**
 * 从 local.properties 读一个值（读不到返回空串）。
 *
 * ★ local.properties 的键**不会**自动变成 gradle project property
 *   （那是 Android Studio 的行为，命令行构建拿不到）——
 *   必须自己读 Properties 文件，否则 manifestPlaceholder 是空串，
 *   高德直接报 USERKEY 错误（这个坑踩过：aapt2 dump 里 value=""）。
 */
fun localProp(key: String): String {
    val f = rootProject.file("local.properties")
    if (!f.isFile) return ""
    val props = Properties()
    f.inputStream().use { props.load(it) }
    return props.getProperty(key) ?: ""
}
