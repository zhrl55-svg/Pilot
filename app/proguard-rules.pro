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
# ★★ 高德 SDK 的 native 库（libapssdk.so 等）在 JNI_OnLoad 里用
#   FindClass("com/amap/location/...") 按**原始全限定名**找类；
#   R8 把类改名后 FindClass 返回 null → GetStaticMethodID 收到 null
#   → JNI DETECTED ERROR / SIGABRT，整个 :navigation 进程直接崩。
#   真机实测：release 包点导航即崩，配不配 Key 都崩（debug 不混淆则正常）。
#   所以 com.amap.** 全包（含 location/a/a/*、navi、api）必须 keep 类名和方法名。
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
# ★ 高德导航内置语音（NUI）依赖阿里 idst 库，native 层按原始方法名回调 Java
#   （如 NativeNui.onFileTransEventCallback_）。R8 混淆方法名后 native 找不到
#   → SIGABRT（真机实测 tombstone: NoSuchMethodError ... NativeNui）。整个包原样保留。
-dontwarn com.alibaba.idst.**
-keep class com.alibaba.idst.** { *; }

# ---------------------------------------------------------------- MNN / sherpa（JNI 反射调用）
-keep class com.alibaba.mnnllm.android.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ---------------------------------------------------------------- 沙箱 AIDL
-keep class com.pilot.agent.sandbox.** { *; }
-keep class com.pilot.agent.navigation.** { *; }
