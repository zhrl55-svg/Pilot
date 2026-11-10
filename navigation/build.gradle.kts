import java.util.Properties

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // 设置面板用 Compose：需要 Compose 编译器插件（Kotlin 2.0 起强制要求）
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pilot.navigation"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        // 高德 so 只有 arm64-v8a，模拟器装不上这个模块（与 app 同理）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 高德 Web Key（地点搜索用，不绑包名）。密钥放 local.properties 不入库。
        // ★ 同 app 模块：local.properties 的键不会自动进 project property，
        //   必须自己读 Properties 文件。
        buildConfigField("String", "AMAP_WEB_KEY", "\"${localProp("AMAP_WEB_KEY")}\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Compose：设置面板要跟主界面用同一套 Material3 组件
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // 高德 SDK：jar（class + assets）与 so 分开带
    implementation(files("libs/AMap3DMap_11.3.100_AMapNavi_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101_20260924.jar"))
}

/** 从 local.properties 读一个值（读不到返回空串）。 */
fun localProp(key: String): String {
    val f = rootProject.file("local.properties")
    if (!f.isFile) return ""
    val props = Properties()
    f.inputStream().use { props.load(it) }
    return props.getProperty(key) ?: ""
}
