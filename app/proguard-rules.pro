# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# The shell-side helper. Nothing in this app calls it — `app_process` does, by
# class name, in another process — so R8 has no reason to think it is used and
# would delete it from a release build. Its `main` is the entry point, and the
# name is what the Shizuku command line spells out; renaming either makes the
# probe (and, later, the background screen's server) fail with a message that
# blames the platform.
-keep class net.kuafuai.andee.helper.** { *; }

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile