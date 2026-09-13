-keep class com.honestfamily.dashboard.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
