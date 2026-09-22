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
    private boolean healthyStable;
    private long lastProbeWrite;
    private ScheduledFuture<?> pingFuture;
    private int pingFailures;
    private static volatile boolean httpPingEnabled = true;
    private static volatile int httpPingInterval = 3;
    private static volatile String httpPingUrl = "http://connectivitycheck.gstatic.com/generate_204";
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { scheduleHealthCheck(); }
    };

    /** Apply HTTP ping settings from prefs (called by MainActivity after saving Pengaturan). */
    public static void applyHttpPing(SharedPreferences prefs) {
        httpPingEnabled = prefs.getBoolean("http_ping", true);
        httpPingInterval = prefs.getInt("http_ping_interval", 3);
        String url = prefs.getString("http_ping_url", null);
        httpPingUrl = url == null || url.isEmpty() ? "http://connectivitycheck.gstatic.com/generate_204" : url;
        listenerStateRefresh = true; // service reschedules on next ping tick (loop lama dicancel + dibuat baru)
    }
    private static volatile boolean listenerStateRefresh;

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
        try {
            SetupOptions options = new SetupOptions();
            options.setBasePath(getFilesDir().getAbsolutePath());
            Libbox.setup(options);
        } catch (Exception error) { Log.e("VpnService", "libbox setup", error); }
        heartbeat.scheduleAtFixedRate(this::writeHeartbeat, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
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
            heartbeat.scheduleAtFixedRate(this::writeHeartbeat, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
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
            VlessConfig original = VlessParser.parse(uri);
            String dialAddress = resolveIpv4(original.address);
            VlessConfig config = new VlessConfig(dialAddress, original.port, original.uuid, original.path, original.sni, original.host, original.allowInsecure);
            String json = SingboxConfig.build(config, getFilesDir().getAbsolutePath());
            Libbox.checkConfig(json);
            closeCore();
            service = Libbox.newService(json, new Platform());
            service.start();
            synchronized (lifecycleLock) {
                if (!connecting) { // user pressed DISCONNECT while we were building: honor it
                    closeCore();
                    return;
                }
                connecting = false; running = true; terminalFailure = false; failedProbes = 0; autoReconnects = 0;
            }
            updateNotification("Checking internet…");
            setState("Checking internet…");
            String failure = awaitHealthyTunnel();
            synchronized (lifecycleLock) { if (!running) { closeCore(); return; } } // disconnected while checking
            if (failure != null) { fail(failure); return; }
            updateNotification("Connected");
            setState("Connected");
            resetMeter();
            synchronized (lifecycleLock) { healthyStable = false; pingFailures = 0; }
            scheduleProbe();
            scheduleHttpPing();
        } catch (Exception error) {
            Log.e("VpnService", "connect", error);
            fail(connectionFailure(error));
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
                if (tun == null) Log.e("VpnService", "establish() returned null — VPN consent revoked or another VPN active");
            } catch (Exception error) {
                Log.e("VpnService", "establish() failed", error);
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
        @Override public void writeLog(String message) { Log.i("libbox", message); }
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

    private void setState(String value) {
        statusPrefs().edit().putString(KEY_STATE, value).putLong(KEY_LAST_SEEN, running ? System.currentTimeMillis() : 0).apply();
        state(value);
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
        statusPrefs().edit().putLong("session_rx", 0).putLong("session_tx", 0).apply();
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
        String failure = pingUrl(httpPingUrl);
        long now = System.currentTimeMillis();
        if (failure == null) {
            pingFailures = 0;
            pushPingLine("HTTP ping ok " + android.text.format.DateFormat.format("HH:mm:ss", now));
            return;
        }
        pingFailures++;
        pushPingLine("HTTP ping gagal (" + pingFailures + ") " + android.text.format.DateFormat.format("HH:mm:ss", now));
        if (pingFailures >= 3) { pushPingLine("Percobaan koneksi ulang otomatis…"); reconnectTunnel(); }
    }

    /** HTTP ping status lines, shown in the app status box under "Connected". Max 5, then cleared. */
    private static final java.util.Deque<String> pingLines = new java.util.ArrayDeque<>();
    private static void pushPingLine(String line) {
        synchronized (pingLines) {
            pingLines.addLast(line);
            while (pingLines.size() > 5) pingLines.pollFirst();
            if (pingLines.size() >= 5) pingLines.clear(); // full batch → clear and start over
        }
        state("Connected\n" + String.join("\n", pingLines));
    }

    private String pingUrl(String target) {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", SingboxConfig.PROXY_PORT));
            connection = (HttpURLConnection) new URL(target).openConnection(proxy);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) return null;
            return "HTTP " + code;
        } catch (Exception error) {
            return String.valueOf(error.getMessage());
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
        if (reconnect) reconnectTunnel();
        else fail("Cannot connect: check server, port, path, SNI, and Host");
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
            return "Internet check failed: server returned HTTP " + code;
        } catch (SocketTimeoutException error) {
            return "Internet check timed out";
        } catch (java.net.ConnectException error) {
            return "VPN proxy unavailable";
        } catch (Exception error) {
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
            if (last == null) { synchronized (lifecycleLock) { failedProbes = 0; } return null; }
        }
        // Non-blocking: tunnel is up; truthfulness is handled by the periodic checkTunnel.
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
        updateNotification("Reconnecting…");
        setState("Reconnecting…");
        worker.execute(() -> connect(uri));
    }

    private void fail(String reason) {
        synchronized (lifecycleLock) { connecting = false; running = false; terminalFailure = true; }
        closeCore();
        statusPrefs().edit().remove(KEY_URI).putString(KEY_STATE, reason).putLong(KEY_LAST_SEEN, 0).putLong(KEY_LAST_PROBE, 0).apply();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        state(reason);
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
        statusPrefs().edit().remove(KEY_URI).putString(KEY_STATE, "Disconnected").putLong(KEY_LAST_SEEN, 0).putLong(KEY_LAST_PROBE, 0).apply();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        state("Disconnected");
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
