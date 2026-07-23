# Proguard rules for App Updater

# Keep WebView JavascriptInterfaces intact so JS to Kotlin communication works
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the AndroidJSInterface inside MainActivity
-keepclassmembers class com.example.appupdater.MainActivity$AndroidJSInterface {
    <methods>;
}

# Keep our main activity and services
-keep class com.example.appupdater.** { *; }

# Keep apksig and bouncycastle cryptography classes
-keep class com.android.apksig.** { *; }
-keep class org.bouncycastle.** { *; }

# Ignore warnings for missing JVM classes not available on Android
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
-dontwarn com.android.apksig.**

# Keep Compose/Kotlin metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Allow optimization and shrinking
-optimizationpasses 5
-dontpreverify

