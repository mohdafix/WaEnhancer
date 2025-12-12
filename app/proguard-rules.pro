# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep attributes needed for reflection
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ===== CRITICAL: Don't obfuscate - Xposed needs original class names =====
-dontobfuscate

# ===== Keep all your app classes (Xposed module) =====
-keep class com.wmods.wppenhacer.** { *; }

# ===== Keep Xposed entry points =====
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public void handleLoadPackage(...);
}
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit {
    public void initZygote(...);
}
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources {
    public void handleInitPackageResources(...);
}

# ===== Keep Application class =====
-keep class com.wmods.wppenhacer.App { *; }

# ===== Keep BridgeService and HookProvider =====
-keep class com.wmods.wppenhacer.xposed.bridge.service.BridgeService { *; }
-keep class com.wmods.wppenhacer.xposed.bridge.providers.HookProvider { *; }

# ===== Keep all Activities =====
-keep class * extends android.app.Activity { *; }
-keep class * extends androidx.appcompat.app.AppCompatActivity { *; }
-keep class * extends androidx.fragment.app.Fragment { *; }

# ===== Keep all BroadcastReceivers =====
-keep class * extends android.content.BroadcastReceiver { *; }

# ===== Keep all Services =====
-keep class * extends android.app.Service { *; }

# ===== Keep all ContentProviders =====
-keep class * extends android.content.ContentProvider { *; }

# ===== Keep Xposed Framework =====
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# ===== Keep DexKit =====
-keep class org.luckypray.dexkit.** { *; }

# ===== Keep CSS Parser (jstyleparser) implementation classes and API =====
# Keep css parser impl classes and API classes
-keep class cz.vutbr.web.csskit.** { *; }
-keep class cz.vutbr.web.css.** { *; }

# Keep public constructors and static initializer for SPI impl
-keepclassmembers class cz.vutbr.web.csskit.** {
    public <init>(...);
    <clinit>();
}

# Suppress warnings for W3C DOM traversal classes (not available on Android)
-dontwarn org.w3c.dom.traversal.**

# Note: Service files (META-INF/services/*) are kept via build.gradle.kts packaging options

# ===== Keep native methods =====
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===== Keep enums =====
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== Keep Parcelable =====
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ===== Suppress warnings for missing classes =====
-dontwarn com.google.auto.value.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.slf4j.**
-dontwarn lombok.**
-dontwarn com.whatsapp.**
-dontwarn com.whatsapp.w4b.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlin.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ===== Ignore all notes and warnings =====
-dontnote **
-ignorewarnings