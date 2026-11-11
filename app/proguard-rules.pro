# Pilot agent
-keep class com.pilot.agent.agent.** { *; }
-keep class com.pilot.agent.perception.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pilot.agent.**$$serializer { *; }
-keepclassmembers class com.pilot.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.pilot.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------------------------------------------------------------- 高德 SDK
# R8 扫到两个可选类不存在（SDK 里是软引用，运行时才会用）：
#   com.amap.ams.gnss.GnssSoftLocator / net.jafama.FastMath
# 不加 -dontwarn 整个 release 包都打不出来（踩过：一直只能用 debug 包）。
-dontwarn com.amap.**
-dontwarn com.autonavi.**
-dontwarn net.jafama.**
# 高德靠反射找入口，类名不能被改
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.amap.navi.** { *; }

# ---------------------------------------------------------------- MNN / sherpa（JNI 反射调用）
-keep class com.alibaba.mnnllm.android.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ---------------------------------------------------------------- 沙箱 AIDL
-keep class com.pilot.agent.sandbox.** { *; }
-keep class com.pilot.agent.navigation.** { *; }
