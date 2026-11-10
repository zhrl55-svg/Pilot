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
