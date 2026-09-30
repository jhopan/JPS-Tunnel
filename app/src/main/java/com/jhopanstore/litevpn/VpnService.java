package com.jhopanstore.litevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.jhopanstore.litevpn.core.SingboxConfig;
import com.jhopanstore.litevpn.core.VlessConfig;
import com.jhopanstore.litevpn.core.VlessParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import io.github.sagernet.libbox.libbox.BoxService;
import io.github.sagernet.libbox.libbox.InterfaceUpdateListener;
import io.github.sagernet.libbox.libbox.Libbox;
import io.github.sagernet.libbox.libbox.NetworkInterfaceIterator;
import io.github.sagernet.libbox.libbox.PlatformInterface;
import io.github.sagernet.libbox.libbox.SetupOptions;
import io.github.sagernet.libbox.libbox.TunOptions;
import io.github.sagernet.libbox.libbox.WIFIState;

public final class VpnService extends android.net.VpnService {
    public interface Listener { void onState(String state); }
    private static volatile Listener listener;
    private static final String ACTION_STOP = "com.jhopanstore.litevpn.STOP";
    private static final String EXTRA_URI = "uri";
    private static final String STATUS_PREFS = "vpn_status";
    private static final String KEY_URI = "uri";
    private static final String KEY_STATE = "state";
    private static final String KEY_LAST_SEEN = "last_seen";
    private static final String KEY_LAST_PROBE = "last_probe_success";
    private static final int NOTIFICATION_ID = 7;
    private static final String CHANNEL = "vpn";
    private static final long HEARTBEAT_MS = 15_000;
    private static final long STABLE_PROBE_MS = 90_000;
    private static final long RECOVER_PROBE_MS = 30_000;
    private static final long PROBE_WRITE_THROTTLE_MS = 60_000;
    private static final String HEALTH_URL = "https://www.gstatic.com/generate_204";
    private static final int PROBE_FAIL_LIMIT = 3;
    private static final int MAX_AUTO_RECONNECTS = 3;
    private static final int PROBE_TIMEOUT_MS = 8_000;
    private static final int PROBE_ATTEMPTS = 2;
    private static final long PROBE_GAP_MS = 2_000;

    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final Object lifecycleLock = new Object();
    private BoxService service;
    private ParcelFileDescriptor tun;
    private InterfaceUpdateListener interfaceListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean connecting;
    private boolean running;
    private int autoReconnects;
    private boolean terminalFailure;
    private int failedProbes;
    /** Result of the last initial health probe; non-null means the tunnel came up but was not verified. */
    private String lastProbeFailure;
    private boolean healthyStable;
    private long lastProbeWrite;
    private ScheduledFuture<?> pingFuture;
    private int pingFailures;
    private static volatile boolean httpPingEnabled = true;
    private static volatile int httpPingInterval = 3;
    /**
     * Default keep-alive target. HTTPS is only a preference, not a requirement: cleartext is
     * permitted via res/xml/network_security_config.xml, so a user-entered http:// URL works too.
     * (Before that config existed, an http:// default failed with "Cleartext HTTP traffic ...
     * not permitted", tripped the reconnect logic and reported "Cannot connect" on a healthy tunnel.)
     */
    public static final String DEFAULT_PING_URL = "https://connectivitycheck.gstatic.com/generate_204";
    private static volatile String httpPingUrl = DEFAULT_PING_URL;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { scheduleHealthCheck(); }
    };

    /** Apply HTTP ping settings from prefs (called by MainActivity after saving Pengaturan). */
    public static void applyHttpPing(SharedPreferences prefs) {
        httpPingEnabled = prefs.getBoolean("http_ping", true);
        httpPingInterval = prefs.getInt("http_ping_interval", 3);
        String url = prefs.getString("http_ping_url", null);
        // Any scheme is fine: cleartext is permitted via res/xml/network_security_config.xml,
        // so a user-supplied http:// endpoint (local router, LAN health page) works as-is.
        httpPingUrl = (url == null || url.isEmpty()) ? DEFAULT_PING_URL : url;
        listenerStateRefresh = true; // service reschedules on next ping tick (loop lama dicancel + dibuat baru)
    }
    private static volatile boolean listenerStateRefresh;

    /**
     * Debug mode: mirror every connection step (API info, dial target, DNS, TUN, core log,
     * probe result) into the status box under the state line. Toggled in Pengaturan.
     */
    private static volatile boolean debugMode;
    private static final java.util.Deque<String> debugLines = new java.util.ArrayDeque<>();
    private static final int DEBUG_MAX_LINES = 16;
    private static final long CORE_LOG_THROTTLE_MS = 200;
    private long lastCoreLog;
    /** Canonical first line of the status box; debug/ping lines are appended below it. */
    private String baseState = "Disconnected";

    /** Apply debug-mode setting from prefs (called by MainActivity after saving Pengaturan). */
    public static void applyDebugMode(SharedPreferences prefs) {
        debugMode = prefs.getBoolean("debug_mode", false);
        if (!debugMode) synchronized (debugLines) { debugLines.clear(); }
    }
    public static boolean isDebugMode() { return debugMode; }

    public static void setListener(Listener value) { listener = value; }
    public static void start(Context context, String uri) {
        Intent intent = new Intent(context, VpnService.class).putExtra(EXTRA_URI, uri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
    }
    public static void stop(Context context) { context.startService(new Intent(context, VpnService.class).setAction(ACTION_STOP)); }
    private static void state(String value) { if (listener != null) listener.onState(value); }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        applyDebugMode(getSharedPreferences("vpn", MODE_PRIVATE));
        try {
            SetupOptions options = new SetupOptions();
            options.setBasePath(getFilesDir().getAbsolutePath());
            Libbox.setup(options);
        } catch (Exception error) { Log.e("VpnService", "libbox setup", error); }
        heartbeat.scheduleWithFixedDelay(this::writeHeartbeat, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        scheduleProbe();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        if (heartbeat.isShutdown()) { // reused instance after stopSelf(): rebuild executors
            heartbeat = Executors.newSingleThreadScheduledExecutor();
            worker = Executors.newSingleThreadExecutor();
            heartbeat.scheduleWithFixedDelay(this::writeHeartbeat, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        }
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { fail("No network: turn on Wi-Fi or mobile data"); return START_NOT_STICKY; }
        String uri = intent == null ? statusPrefs().getString(KEY_URI, null) : intent.getStringExtra(EXTRA_URI);
        if (uri == null) { setState("Disconnected"); stopSelf(); return START_NOT_STICKY; }
        synchronized (lifecycleLock) {
            if (running || connecting) { closeCore(); running = false; connecting = false; failedProbes = 0; }
            connecting = true;
        }
        statusPrefs().edit().putString(KEY_URI, uri).apply();
        clearSteps(); // fresh user-initiated attempt: start the log clean
        clearPings();
        startForeground(NOTIFICATION_ID, notification("Connecting…"));
        setState("Connecting…");
        worker.execute(() -> {
            try { connect(uri); }
            catch (Exception error) { Log.e("VpnService", "connect task", error); fail(connectionFailure(error)); }
        });
        return START_STICKY;
    }

    private void connect(String uri) {
        try {
            logStep("API: libbox sing-box " + coreVersion() + " • stack " + SingboxConfig.STACK
                + " • proxy 127.0.0.1:" + SingboxConfig.PROXY_PORT);
            VlessConfig original = VlessParser.parse(uri);
            logStep("Menghubungkan ke " + original.address + ":" + original.port);
            logStep("uuid " + clip(original.uuid, 8) + " • path " + original.path
                + " • sni " + original.sni + " • host " + original.host);
            String dialAddress = resolveIpv4(original.address);
            if (!dialAddress.equals(original.address)) logStep("DNS " + original.address + " → " + dialAddress);
            VlessConfig config = new VlessConfig(dialAddress, original.port, original.uuid, original.path, original.sni, original.host, original.allowInsecure);
            String json = SingboxConfig.build(config, getFilesDir().getAbsolutePath(), debugMode);
            Libbox.checkConfig(json);
            logStep("Config sing-box valid");
            closeCore();
            service = Libbox.newService(json, new Platform());
            service.start();
            logStep("Core sing-box jalan");
            synchronized (lifecycleLock) {
                if (!connecting) { // user pressed DISCONNECT while we were building: honor it
                    logStep("Dibatalkan pengguna");
                    closeCore();
                    return;
                }
                connecting = false; running = true; terminalFailure = false; failedProbes = 0; autoReconnects = 0;
            }
            updateNotification("Checking internet…");
            setState("Checking internet…");
            String failure = awaitHealthyTunnel();
            synchronized (lifecycleLock) { if (!running) { closeCore(); return; } } // disconnected while checking
            if (failure != null) { logStep("Gagal: " + failure); fail(failure); return; }
            updateNotification("Connected");
            // The step log is a diagnostic for the dial, not something to stare at forever. Once the
            // tunnel is up AND the probe really got through, drop it so the box settles to a clean
            // "Connected" + keep-alive ping lines. If the probe did NOT get through, keep every step —
            // that is precisely the case worth reading afterwards.
            if (lastProbeFailure == null) clearSteps();
            else logStep("Tunnel jalan tapi probe belum tembus: " + lastProbeFailure);
            setState("Connected");
            resetMeter();
            synchronized (lifecycleLock) { healthyStable = false; pingFailures = 0; }
            scheduleProbe();
            scheduleHttpPing();
        } catch (Exception error) {
            Log.e("VpnService", "connect", error);
            String reason = connectionFailure(error);
            logStep("Gagal: " + reason);
            fail(reason);
        }
    }

    private String resolveIpv4(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address instanceof Inet4Address) return address.getHostAddress();
            }
        } catch (Exception error) { Log.w("VpnService", "DNS resolve failed for " + host, error); }
        return host;
    }

    private final class Platform implements PlatformInterface {
        @Override public int openTun(TunOptions options) {
            Builder builder = new Builder().setSession("JPS Tunnel").setMtu(options.getMTU());
            builder.addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").addDnsServer("8.8.8.8");
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
            try {
                tun = builder.establish();
                if (tun == null) {
                    Log.e("VpnService", "establish() returned null — VPN consent revoked or another VPN active");
                    logStep("TUN gagal: izin VPN dicabut / VPN lain aktif");
                } else {
                    logStep("TUN 172.19.0.1/30 mtu " + options.getMTU() + " fd " + tun.getFd());
                }
            } catch (Exception error) {
                Log.e("VpnService", "establish() failed", error);
                logStep("TUN gagal: " + error.getClass().getSimpleName());
            }
            return tun == null ? -1 : tun.getFd();
        }
        @Override public void autoDetectInterfaceControl(int fd) { if (!protect(fd)) Log.w("VpnService", "protect failed: " + fd); }
        @Override public void clearDNSCache() {}
        @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener value) { stopNetworkMonitor(); }
        @Override public int findConnectionOwner(int protocol, String source, int sourcePort, String destination, int destinationPort) { return 0; }
        @Override public NetworkInterfaceIterator getInterfaces() { return new InterfaceIterator(Collections.emptyList()); }
        @Override public boolean includeAllNetworks() { return false; }
        @Override public String packageNameByUid(int uid) { return ""; }
        @Override public WIFIState readWIFIState() { return null; }
        @Override public void sendNotification(io.github.sagernet.libbox.libbox.Notification value) {}
        @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener value) { startNetworkMonitor(value); }
        @Override public int uidByPackageName(String packageName) { return 0; }
        @Override public boolean underNetworkExtension() { return false; }
        @Override public boolean usePlatformAutoDetectInterfaceControl() { return true; }
        @Override public boolean useProcFS() { return false; }
        @Override public void writeLog(String message) {
            Log.i("libbox", message);
            if (!debugMode || message == null) return;
            long now = System.currentTimeMillis();
            if (now - lastCoreLog < CORE_LOG_THROTTLE_MS) return; // debug level is chatty
            String lower = message.toLowerCase();
            // WebSocket 1006 (abnormal closure) is how a VLESS/WS stream normally ends; the core logs
            // it at ERROR but it is expected, and surfacing it would flood the box. Still in logcat.
            if (lower.contains("ws closed: 1006")) return;
            boolean failure = (lower.contains("error") && !lower.contains("noerror"))
                || lower.contains("warn") || lower.contains("fatal")
                || lower.contains("refused") || lower.contains("timeout") || lower.contains("reset by peer");
            // While the tunnel is still being built, dial/handshake detail is exactly what explains a
            // failure, so accept it too. Once connected it is dropped: steady-state traffic would
            // otherwise flush the connection steps straight out of the status box.
            boolean handshake = connecting && (lower.contains("outbound") || lower.contains("dial")
                || lower.contains("vless") || lower.contains("handshake") || lower.contains("websocket")
                || lower.contains("tls") || lower.contains("proxy") || lower.contains("tun"));
            if (!failure && !handshake) return;
            lastCoreLog = now;
            logStep("core: " + clip(stripAnsi(message), 110));
        }
    }

    private synchronized void startNetworkMonitor(InterfaceUpdateListener value) {
        stopNetworkMonitor();
        interfaceListener = value;
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { reportNetwork(network); scheduleHealthCheck(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { reportNetwork(network); scheduleHealthCheck(); }
        };
        try { manager.registerDefaultNetworkCallback(networkCallback); manager.getActiveNetwork(); Network current = manager.getActiveNetwork(); if (current != null) reportNetwork(current); }
        catch (Exception error) { Log.w("VpnService", "network callback", error); }
    }

    private synchronized void stopNetworkMonitor() {
        if (networkCallback != null) {
            try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        networkCallback = null; interfaceListener = null;
    }

    private void reportNetwork(Network network) {
        InterfaceUpdateListener target = interfaceListener;
        if (target == null) return;
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            LinkProperties link = manager.getLinkProperties(network);
            if (caps == null || link == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || link.getInterfaceName() == null) return;
            java.net.NetworkInterface item = java.net.NetworkInterface.getByName(link.getInterfaceName());
            boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            target.updateDefaultInterface(link.getInterfaceName(), item == null ? 0 : item.getIndex(), metered, false);
        } catch (Exception error) { Log.w("VpnService", "report network", error); }
    }

    private static final class InterfaceIterator implements NetworkInterfaceIterator {
        private final List<io.github.sagernet.libbox.libbox.NetworkInterface> values; private int position;
        InterfaceIterator(List<io.github.sagernet.libbox.libbox.NetworkInterface> values) { this.values = values; }
        @Override public boolean hasNext() { return position < values.size(); }
        @Override public io.github.sagernet.libbox.libbox.NetworkInterface next() { return values.get(position++); }
    }

    private SharedPreferences statusPrefs() { return getSharedPreferences(STATUS_PREFS, MODE_PRIVATE); }

    /**
     * Sets the canonical first line of the status box. Always goes through here (never straight to
     * {@code state()}) so debug/ping lines can be appended underneath without corrupting the prefix
     * that MainActivity.onVpnState matches on.
     */
    private void setState(String value) {
        baseState = value == null ? "Disconnected" : value;
        emit(baseState);
    }

    /** Appends a step to the debug log and repaints. No-op unless debug mode is on. */
    private void logStep(String line) {
        if (!debugMode || line == null || line.isEmpty()) return;
        synchronized (debugLines) {
            debugLines.addLast(line);
            while (debugLines.size() > DEBUG_MAX_LINES) debugLines.pollFirst();
        }
        emit(baseState);
    }

    /**
     * Drops the step log without repainting (the caller's next {@link #setState} does that). Used at
     * the start of a fresh attempt and once a connection is confirmed healthy, so the box shows only
     * the state line plus the HTTP ping lines.
     */
    private void clearSteps() {
        synchronized (debugLines) { debugLines.clear(); }
    }

    /**
     * Drops the keep-alive lines. Without this a stale "HTTP ping ok" from the previous session keeps
     * showing under "Disconnected", which reads as if the tunnel were still up.
     */
    private void clearPings() {
        synchronized (pingLines) { pingLines.clear(); }
    }

    /** Composes the status text: canonical state, then debug steps, then HTTP ping lines. */
    private void emit(String value) {
        String full = value == null ? "Disconnected" : value;
        StringBuilder extra = new StringBuilder();
        if (debugMode) {
            synchronized (debugLines) { for (String line : debugLines) extra.append('\n').append(line); }
        }
        synchronized (pingLines) {
            for (String line : pingLines) extra.append('\n').append(line);
        }
        if (extra.length() > 0) full = full + extra;
        statusPrefs().edit().putString(KEY_STATE, full).putLong(KEY_LAST_SEEN, running ? System.currentTimeMillis() : 0).apply();
        state(full);
    }

    /** Shortens a value for display: first {@code keep} chars of the first line. */
    private static String clip(String value, int keep) {
        if (value == null) return "-";
        String one = value.replace('\n', ' ').trim();
        if (one.isEmpty()) return "-";
        return one.length() <= keep ? one : one.substring(0, keep) + "…";
    }

    /** sing-box log lines carry ANSI colour codes, which render as garbage inside a TextView. */
    private static String stripAnsi(String value) {
        return value == null ? "" : value.replaceAll("\u001B?\\[[0-9;]*m", "");
    }

    /** sing-box version baked into the bundled libbox AAR (Libbox.version() -> Go C.Version). */
    private static String coreVersion() {
        try {
            String value = Libbox.version();
            return value == null || value.trim().isEmpty() ? "?" : value.trim();
        } catch (Throwable ignored) { return "?"; }
    }

    private void writeHeartbeat() {
        if (running) statusPrefs().edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply();
    }

    private long meterBaseRx = -1, meterBaseTx = -1;

    private void resetMeter() {
        long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        meterBaseRx = rx < 0 ? 0 : rx;
        meterBaseTx = tx < 0 ? 0 : tx;
        // Publish the baseline so MainActivity can derive a live session total from TrafficStats on its
        // own 2 s tick. writeMeter() only runs on probe ticks (30–90 s), so relying on session_rx alone
        // made the counters sit still between probes.
        statusPrefs().edit()
            .putLong("session_rx", 0).putLong("session_tx", 0)
            .putLong("meter_base_rx", meterBaseRx).putLong("meter_base_tx", meterBaseTx)
            .apply();
    }

    private void writeMeter() {
        if (!running || meterBaseRx < 0) return;
        if (!getSharedPreferences("vpn", MODE_PRIVATE).getBoolean("show_traffic", true)) return; // meter off: skip sampling
        long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        if (rx < 0 || tx < 0) return;
        long usedRx = Math.max(0, rx - meterBaseRx), usedTx = Math.max(0, tx - meterBaseTx);
        statusPrefs().edit().putLong("session_rx", usedRx).putLong("session_tx", usedTx).apply();
    }

    private void scheduleProbe() {
        if (heartbeat.isShutdown()) return;
        long delay = healthyStable ? STABLE_PROBE_MS : RECOVER_PROBE_MS;
        try { heartbeat.schedule(this::checkTunnel, delay, TimeUnit.MILLISECONDS); }
        catch (Exception ignored) {}
    }

    private void scheduleHealthCheck() {
        if (heartbeat.isShutdown()) return;
        try { heartbeat.schedule(this::checkTunnel, 2, TimeUnit.SECONDS); }
        catch (Exception ignored) {}
    }

    private void scheduleHttpPing() {
        if (pingFuture != null) { pingFuture.cancel(false); pingFuture = null; }
        listenerStateRefresh = false;
        if (!httpPingEnabled) return;
        int seconds = Math.max(1, httpPingInterval);
        try { pingFuture = heartbeat.scheduleWithFixedDelay(this::runHttpPing, seconds, seconds, TimeUnit.SECONDS); }
        catch (Exception ignored) {}
    }

    private void runHttpPing() {
        synchronized (lifecycleLock) { if (!running || connecting) return; }
        if (listenerStateRefresh) { scheduleHttpPing(); return; } // settings changed: reschedule with new values
        long started = System.currentTimeMillis();
        PingOutcome outcome = pingUrl(httpPingUrl);
        long elapsed = System.currentTimeMillis() - started;
        String time = android.text.format.DateFormat.format("HH:mm:ss", started).toString();
        if (outcome.failure == null) {
            pingFailures = 0;
            // Report the real status line, e.g. "HTTP ping 204 No Content (86 ms) 08:35:12".
            pushPingLine("HTTP ping " + outcome.code + (outcome.phrase.isEmpty() ? "" : " " + outcome.phrase)
                + " (" + elapsed + " ms) " + time);
            return;
        }
        pingFailures++;
        pushPingLine("HTTP ping gagal (" + pingFailures + ") " + time + " — " + clip(outcome.failure, 60)
            + " (" + elapsed + " ms)");
        if (pingFailures >= 3) { pushPingLine("Percobaan koneksi ulang otomatis…"); reconnectTunnel(); }
    }

    /** HTTP ping status lines, shown in the app status box under "Connected". Rolling window of 5. */
    private static final java.util.Deque<String> pingLines = new java.util.ArrayDeque<>();
    private static final int PING_MAX_LINES = 5;
    private void pushPingLine(String line) {
        synchronized (pingLines) {
            pingLines.addLast(line);
            // Keep the newest PING_MAX_LINES. Do NOT wipe the whole batch when it fills up: doing that
            // used to blank the box for one full ping interval every 5 pings, so the status appeared to
            // lose its keep-alive lines right after connecting.
            while (pingLines.size() > PING_MAX_LINES) pingLines.pollFirst();
        }
        emit(baseState);
    }

    /** One keep-alive probe: HTTP status (code + reason phrase) plus a failure reason when it failed. */
    private static final class PingOutcome {
        final int code;        // -1 when the request produced no response at all
        final String phrase;   // "OK", "No Content", … ("" when unknown)
        final String failure;  // null when the probe succeeded
        PingOutcome(int code, String phrase, String failure) {
            this.code = code; this.phrase = phrase; this.failure = failure;
        }
    }

    private PingOutcome pingUrl(String target) {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", SingboxConfig.PROXY_PORT));
            connection = (HttpURLConnection) new URL(target).openConnection(proxy);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            String phrase = connection.getResponseMessage();
            if (phrase == null) phrase = "";
            if (code >= 200 && code < 400) return new PingOutcome(code, phrase, null);
            return new PingOutcome(code, phrase, "HTTP " + code + (phrase.isEmpty() ? "" : " " + phrase));
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
            return new PingOutcome(-1, "", message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void checkTunnel() {
        synchronized (lifecycleLock) { if (!running || connecting) return; }
        String failure = verifyTunnel();
        if (failure == null) {
            synchronized (lifecycleLock) { failedProbes = 0; healthyStable = true; autoReconnects = 0; }
            long now = System.currentTimeMillis();
            if (now - lastProbeWrite > PROBE_WRITE_THROTTLE_MS) {
                lastProbeWrite = now;
                statusPrefs().edit().putLong(KEY_LAST_PROBE, now).apply();
            }
            writeMeter();
            scheduleProbe();
            return;
        }
        synchronized (lifecycleLock) {
            if (!running || connecting) return; // user disconnected or reconnect in progress: never override status
            healthyStable = false;
        }
        boolean reconnect;
        synchronized (lifecycleLock) {
            reconnect = ++failedProbes >= PROBE_FAIL_LIMIT && autoReconnects < MAX_AUTO_RECONNECTS;
            if (reconnect) { running = false; connecting = true; failedProbes = 0; autoReconnects++; }
            else { failedProbes = 0; }
        }
        if (reconnect) {
            logStep("Reconnect otomatis " + autoReconnects + "/" + MAX_AUTO_RECONNECTS + "…");
            reconnectTunnel();
        } else {
            logStep("Gagal setelah " + PROBE_FAIL_LIMIT + "× probe berturut-turut");
            fail("Cannot connect: check server, port, path, SNI, and Host");
        }
    }

    private String verifyTunnel() {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", SingboxConfig.PROXY_PORT));
            connection = (HttpURLConnection) new URL(HEALTH_URL).openConnection(proxy);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NO_CONTENT || code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_ACCEPTED) return null;
            logStep("Probe " + HEALTH_URL + " → HTTP " + code);
            return "Internet check failed: server returned HTTP " + code;
        } catch (SocketTimeoutException error) {
            logStep("Probe timeout setelah " + PROBE_TIMEOUT_MS + " ms");
            return "Internet check timed out";
        } catch (java.net.ConnectException error) {
            logStep("Proxy 127.0.0.1:" + SingboxConfig.PROXY_PORT + " belum siap");
            return "VPN proxy unavailable";
        } catch (Exception error) {
            logStep("Probe error: " + error.getClass().getSimpleName());
            return "Internet check failed";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String awaitHealthyTunnel() {
        String last = "Internet check failed";
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(PROBE_GAP_MS); } catch (InterruptedException error) { return "Connection interrupted"; }
            }
            synchronized (lifecycleLock) { if (!running || connecting) return null; }
            updateNotification("Checking internet… (" + (attempt + 1) + "/" + PROBE_ATTEMPTS + ")");
            last = verifyTunnel();
            if (last == null) {
                synchronized (lifecycleLock) { failedProbes = 0; }
                lastProbeFailure = null;
                return null;
            }
            logStep("Probe " + (attempt + 1) + "/" + PROBE_ATTEMPTS + " gagal: " + last);
        }
        // Non-blocking: tunnel is up; truthfulness is handled by the periodic checkTunnel.
        lastProbeFailure = last;
        return null;
    }

    private static String connectionFailure(Exception error) {
        if (error instanceof java.net.UnknownHostException) return "DNS failed: server name could not be resolved";
        String text = String.valueOf(error.getMessage()).toLowerCase();
        if (text.contains("tls") || text.contains("certificate")) return "TLS failed: check SNI or allowInsecure";
        if (text.contains("websocket") || text.contains("ws ")) return "WebSocket failed: check path or Host";
        return "Connection failed: check server, port, path, SNI, and Host";
    }

    private void reconnectTunnel() {
        String uri = statusPrefs().getString(KEY_URI, null);
        if (uri == null) { disconnect(); return; }
        // Must mirror onStartCommand: connect() aborts itself ("Dibatalkan pengguna") when `connecting`
        // is false as the core comes up, so an auto-reconnect that skipped this used to close the very
        // tunnel it just started, leaving the proxy dead and the next probe failing.
        synchronized (lifecycleLock) {
            if (running || connecting) closeCore();
            running = false;
            connecting = true;
            failedProbes = 0;
        }
        updateNotification("Reconnecting…");
        setState("Reconnecting…");
        worker.execute(() -> connect(uri));
    }

    private void fail(String reason) {
        synchronized (lifecycleLock) { connecting = false; running = false; terminalFailure = true; }
        closeCore();
        statusPrefs().edit().remove(KEY_URI).putLong(KEY_LAST_PROBE, 0).apply();
        setState(reason); // keeps the debug log attached so the failure stays readable afterwards
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void closeCore() {
        stopNetworkMonitor();
        try { if (service != null) service.close(); } catch (Exception ignored) {}
        service = null;
        try { if (tun != null) tun.close(); } catch (IOException ignored) {}
        tun = null;
    }
    private void disconnect() {
        synchronized (lifecycleLock) { connecting = false; running = false; terminalFailure = false; }
        if (pingFuture != null) { pingFuture.cancel(false); pingFuture = null; }
        writeMeter();
        closeCore();
        statusPrefs().edit().remove(KEY_URI).putLong(KEY_LAST_PROBE, 0).apply();
        clearPings(); // no stale "HTTP ping ok" under "Disconnected"
        setState("Disconnected");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy() { boolean failed; synchronized (lifecycleLock) { running = false; connecting = false; failed = terminalFailure; } closeCore(); heartbeat.shutdownNow(); worker.shutdownNow(); try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {} if (!failed) statusPrefs().edit().putString(KEY_STATE, "Disconnected").putLong(KEY_LAST_SEEN, 0).putLong(KEY_LAST_PROBE, 0).apply(); super.onDestroy(); }
    @Override public void onRevoke() { disconnect(); super.onRevoke(); }
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (running && !connecting) { // user swiped app away: keep VPN alive
            Intent restart = new Intent(getApplicationContext(), VpnService.class)
                .putExtra(EXTRA_URI, statusPrefs().getString(KEY_URI, null));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restart);
            else startService(restart);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "VPN aktif", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Status koneksi VPN");
            channel.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private Notification notification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("JPS Tunnel").setContentText(text).setSmallIcon(R.drawable.ic_vpn_key).setContentIntent(content).addAction(new Notification.Action.Builder(null, "Disconnect", stop).build()).setOngoing(true).build();
    }
    private void updateNotification(String text) { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text)); }
}
