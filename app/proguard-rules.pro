-keep class io.github.sagernet.libbox.** { *; }
-keep class go.** { *; }
-keep class com.jhopanstore.litevpn.VpnService { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
# NOTE: Log.i is deliberately NOT stripped — VpnService.Platform#writeLog forwards the whole
# sing-box core log through it, which is the only way to see outbound/dial/TLS failures via
# `adb logcat -s libbox` in a release build.
