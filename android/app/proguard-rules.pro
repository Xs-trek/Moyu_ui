-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.easytier.jni.EasyTierJNI { *; }
-keepclassmembers class com.moyu.remote.MainActivity$MoyuHostBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn org.conscrypt.**

