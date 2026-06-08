# Maximum obfuscation for Thieves Trap
# Copyright © 2026 ILINE TECH BY FERAK ALADDIN

-optimizationpasses 5
-allowaccessmodification
-dontskipnonpubliclibraryclassmembers
-dontusemixedcaseclassnames

# Obfuscate everything except Android framework
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.admin.DeviceAdminReceiver
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver {
    public *;
}

# Keep entry points
-keep class com.thievestrap.MainActivity { *; }
-keep class com.thievestrap.BootReceiver { *; }
-keep class com.thievestrap.DeviceAdminReceiver { *; }

# Protect license logic from reverse engineering
-keep,allowobfuscation class com.thievestrap.LicenseManager { *; }
-repackageclasses 'x'
-flattenpackagehierarchy

# Keep R class
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Security Crypto
-keep class androidx.security.crypto.** { *; }

# Camera2
-keep class android.hardware.camera2.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
