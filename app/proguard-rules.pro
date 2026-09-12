# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.zxeb.ccusage.model.** {
    *** Companion;
}
-keepclasseswithmembers class dev.zxeb.ccusage.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 小组件 Provider 由系统按类名反射实例化，不能混淆
-keep class dev.zxeb.ccusage.widget.** { *; }
