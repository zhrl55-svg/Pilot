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
        versionCode = 3
        versionName = "0.1.2"
        resourceConfigurations += listOf("zh", "en")
        // ★ 高德 Key 不再在构建期注入。
        //   公开版本不能内置作者个人的 Key（否则别人使用会消耗作者的配额）。
        //   Android / Web Key 一律由使用者在 App 内「设置 → 导航与地图」填写，
        //   运行时通过 AMapNavi / MapsInitializer / AMapLocationClient /
        //   ServiceSettings 的 setApiKey 注入（见 NavKeys.applySdkKey）。

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
        //
        // 安全（审核报告 code.md S1-c）：不再有默认密码 "android"。
        //   密码/别名按「环境变量 → local.properties（不入库）」读取：
        //     PILOT_STORE_PASSWORD / PILOT_KEY_PASSWORD / PILOT_KEY_ALIAS（别名缺省 platform）
        //   缺任何一个密码就不创建 platform 签名配置，release 任务会在执行期报错。
        //   "android" 是 AOSP 公开 testkey 的密码 —— 用公开平台密钥签名时，任何人都能签出
        //   同签名 App 拿到 TRIGGER 及全部 signature|privileged 权限，所以默认拒绝。
        val platformStore = file("../keystore/platform.jks")
        val storePass = signingSecret("PILOT_STORE_PASSWORD")
        val keyPass = signingSecret("PILOT_KEY_PASSWORD")
        val alias = signingSecret("PILOT_KEY_ALIAS").ifBlank { "platform" }
        if (platformStore.exists() && storePass.isNotBlank() && keyPass.isNotBlank()) {
            create("platform") {
                storeFile = platformStore
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        } else if (platformStore.exists()) {
            logger.warn("keystore/platform.jks 存在，但未配置 PILOT_STORE_PASSWORD / PILOT_KEY_PASSWORD（环境变量或 local.properties），release 将无法打包")
        }

        // 自签名 release（普通分发试用）：仅在显式 -PpilotSelfSignedRelease=true 且无平台密钥时启用。
        // 用本机私有 keystore/release.jks（不入库），可正常安装使用，但拿不到平台
        // signature|privileged 权限（虚拟屏、TRIGGER 等受限，自动退回）。
        // 默认行为不变：缺平台密钥仍拒绝出 release，避免静默降级。
        val selfSigned = providers.gradleProperty("pilotSelfSignedRelease").orNull == "true"
        val releaseStore = file("../keystore/release.jks")
        val relStorePass = signingSecret("PILOT_RELEASE_STORE_PASSWORD")
        val relKeyPass = signingSecret("PILOT_RELEASE_KEY_PASSWORD")
        if (selfSigned && !platformStore.exists() &&
            releaseStore.exists() && relStorePass.isNotBlank() && relKeyPass.isNotBlank()
        ) {
            create("releaseSelf") {
                storeFile = releaseStore
                storePassword = relStorePass
                keyAlias = "pilot-release"
                keyPassword = relKeyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 本地 JVM 单测覆盖率（createDebugUnitTestCoverageReport 依赖此开关）。
            enableUnitTestCoverage = true
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
            // P1：缺失平台密钥时禁止静默回退 debug 签名——
            // 否则 release 包拿不到 signature|privileged 权限且无任何报错。
            // 显式 -PpilotSelfSignedRelease=true 时允许用自签名 releaseSelf（普通分发）。
            signingConfig = signingConfigs.findByName("platform")
                ?: signingConfigs.findByName("releaseSelf")
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
    testOptions {
        // android.jar 里的桩方法默认直接抛异常。路由层只用到 org.json 与
        // Dbg（Log），开这个开关后单元测试可以跑真实逻辑。
        unitTests.isReturnDefaultValues = true
    }
}

// P1：只在真正打 release 包的执行期校验平台签名，不影响 debug 构建。
tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
    .configureEach {
        doFirst {
            // 注意：doFirst 的接收者是 Task（Task 本身也是 ExtensionAware），
            // 必须显式取 project 的扩展，否则 getByType 会在 task 扩展里找不到 AppExtension。
            val ext = project.extensions.getByType<com.android.build.gradle.AppExtension>()
            val selfSigned = project.providers.gradleProperty("pilotSelfSignedRelease").orNull == "true"
            val platform = ext.signingConfigs.findByName("platform")
                ?: ext.signingConfigs.findByName("releaseSelf")?.takeIf { selfSigned }
                ?: error(
                    "release 必须使用平台签名，禁止回退 debug：请提供 keystore/platform.jks，" +
                        "并通过环境变量或 local.properties 配置 PILOT_STORE_PASSWORD / PILOT_KEY_PASSWORD；" +
                        "普通分发试用可加 -PpilotSelfSignedRelease=true 用自签名 keystore/release.jks",
                )
            // S1-c：拒绝 AOSP 公开 testkey 的默认密码。确属私有密钥且密码恰好相同时，
            // 显式加 -PpilotAllowWeakSigningPassword=true 才放行（并请尽快更换密码）。
            val weak = setOf("android", "")
            val allowWeak = project.providers.gradleProperty("pilotAllowWeakSigningPassword").orNull == "true"
            if (!allowWeak &&
                ((platform.storePassword ?: "") in weak || (platform.keyPassword ?: "") in weak)) {
                error(
                    "平台签名密码是 AOSP 公开 testkey 的默认值 \"android\"：请确认平台密钥不是公开 testkey " +
                        "（keytool -list -v -keystore keystore/platform.jks 与 AOSP build/target/product/security/platform.x509.pem 比对），" +
                        "并更换为私有密钥/密码",
                )
            }
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

    // ── 单元测试 ────────────────────────────────────────────────────
    // 助理快路径的路由层是纯 Kotlin + org.json，单测跑纯 JVM。
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 只有桩；换成真实实现，路由层才能在 JVM 上跑。
    // 版本跟随本机 Gradle 缓存（20231013），避免离线构建时去下载。
    testImplementation("org.json:json:20231013")

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

/**
 * 签名用的机密值：优先环境变量（CI），其次 local.properties（本机，不入库）。
 * 读不到返回空串 —— **没有任何默认值**（S1-c）。
 */
fun signingSecret(key: String): String =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: localProp(key)
