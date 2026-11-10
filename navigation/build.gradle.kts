plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
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
    }

    buildFeatures {
        aidl = true
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

    // 高德 SDK：jar（class + assets）与 so 分开带
    implementation(files("libs/AMap3DMap_11.3.100_AMapNavi_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101_20260924.jar"))
}
