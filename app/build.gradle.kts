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
            applicationIdSuffix = ".debug"
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

    // 端侧语音识别：sherpa-onnx（SenseVoice-Small）
    implementation(files("libs/sherpa-onnx.aar"))
}
