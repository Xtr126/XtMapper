# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html


# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class xtr.keymapper.**  { *; }
-keep class xtr.keymapper.**$* { *; }

# JNI entry point resolved by name from the native diagnostics bridge.
-keepclasseswithmembers class xtr.keymapper.log.XtLogger {
    public static int logNative(int, int, java.lang.String);
}
