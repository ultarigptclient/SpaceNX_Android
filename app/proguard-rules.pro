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

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ── LiveKit + WebRTC ──
-keep class io.livekit.** { *; }
-keep class livekit.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-dontwarn io.livekit.**

# ── JS Bridge ──
-keepclassmembers class net.spacenx.messenger.ui.bridge.WebAppBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ── kwik QUIC ──
-keep class tech.kwik.** { *; }
-dontwarn tech.kwik.**
-dontwarn at.favre.lib.hkdf.**
-dontwarn com.io7m.repackage.io.whitfin.**