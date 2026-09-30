package com.jhopanstore.litevpn;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.jhopanstore.litevpn.core.Installation;
import com.jhopanstore.litevpn.core.ProfileStore;
import com.jhopanstore.litevpn.core.License;
import com.jhopanstore.litevpn.core.LicenseCodec;
import com.jhopanstore.litevpn.core.VlessConfig;
import com.jhopanstore.litevpn.core.VlessParser;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_PERMISSION = 10;
    private static final int IMPORT_FILE = 11;
    private static final int EXPORT_FILE = 12;
    private static final int EXPORT_LICENSE = 14;
    private static final String JVS_MIME = "application/x-jhopanstore-vpn";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText address, uuid, path, sni, host;
    private TextView status, traffic;
    private String hwid;
    private android.view.View configFields;
    private android.widget.TextView lockBanner;
    private android.widget.TextView toggleFields;
    private boolean fieldsExpanded;
    private License license;
    private ProfileStore profileStore;
    private String activeProfileId;
    private RecyclerView profileList;
    private Button connect;
    private boolean connected;
    /** Previous "Connected" flag, so the meter resets on a real transition instead of every repaint. */
    private boolean stateWasConnected;
    private boolean showTraffic = true;
    private long totalRx, totalTx, lastRx, lastTx, lastSample;
    private boolean hasBaseline;
    private int uid;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("JPS Tunnel");
        setSupportActionBar(toolbar);
        prefs = getSharedPreferences("vpn", MODE_PRIVATE);
        profileStore = new ProfileStore(prefs);
        address = findViewById(R.id.address); uuid = findViewById(R.id.uuid); path = findViewById(R.id.path); sni = findViewById(R.id.sni); host = findViewById(R.id.host);
        status = findViewById(R.id.status); traffic = findViewById(R.id.traffic); connect = findViewById(R.id.connect);
        // status box is height-capped, so make it scrollable: in debug mode the log can exceed the cap
        status.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        configFields = findViewById(R.id.configFields); lockBanner = findViewById(R.id.lockBanner);
        toggleFields = findViewById(R.id.toggleFields);
        profileList = findViewById(R.id.profileList);
        profileList.setLayoutManager(new LinearLayoutManager(this));
        TextView version = findViewById(R.id.version);
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String installed = android.text.format.DateFormat.format("dd/MM HH:mm", info.lastUpdateTime).toString();
            // install stamp makes it obvious which APK is actually running
            version.setText("v" + info.versionName + " • terpasang " + installed);
        }
        catch (Exception ignored) { version.setVisibility(android.view.View.GONE); }
        load();
        hwid = Installation.id(this);
        loadLicense();
        applyLockState();
        uid = android.os.Process.myUid();
        totalRx = 0; totalTx = 0;
        showTraffic = prefs.getBoolean("show_traffic", true);
        traffic.setVisibility(showTraffic ? android.view.View.VISIBLE : android.view.View.GONE);
        connect.setOnClickListener(v -> { if (connected) disconnect(); else requestConnect(); });
        toggleFields.setOnClickListener(v -> toggleConfigFields());

        VpnService.setListener(value -> runOnUiThread(() -> onVpnState(value)));
        requestNotificationPermission();
        batteryGuard();
        handleSharedFile(getIntent());
    }

    private void batteryGuard() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryDone = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean autostartDone = prefs.getBoolean("autostart_done", false);
        if (batteryDone && autostartDone) return;
        if (prefs.getBoolean("battery_guard_asked", false) && !batteryDone) return;
        prefs.edit().putBoolean("battery_guard_asked", true).apply();
        if (!batteryDone) {
            new AlertDialog.Builder(this)
                .setTitle("Mode 24/7")
                .setMessage("Agar VPN tetap hidup saat layar mati, matikan penghemat daya (battery optimization) dan aktifkan Autostart untuk JPS Tunnel." )
                .setPositiveButton("Matikan penghemat daya", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception error) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNeutralButton("Aktifkan Autostart", (d, w) -> openAutostartSetting())
                .setNegativeButton("Nanti", null)
                .show();
            return;
        }
        if (!autostartDone) {
            new AlertDialog.Builder(this)
                .setTitle("Aktifkan Autostart")
                .setMessage("Satu langkah lagi untuk mode 24/7: aktifkan Autostart untuk JPS Tunnel, lalu kunci aplikasi di Recents ( Recent → tahan ikon → gembok ).")
                .setPositiveButton("Aktifkan Autostart", (d, w) -> { prefs.edit().putBoolean("autostart_done", true).apply(); openAutostartSetting(); })
                .setNegativeButton("Nanti", null)
                .show();
        }
    }

    private void openAutostartSetting() {
        try {
            startActivity(new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT));
        } catch (Exception error) {
            try {
                startActivity(new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")));
            } catch (Exception error2) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedFile(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        SharedPreferences vpnStatus = getSharedPreferences("vpn_status", MODE_PRIVATE);
        String value = vpnStatus.getString("state", "Disconnected");
        long lastSeen = vpnStatus.getLong("last_seen", 0);
        if ("Connected".equals(value) && System.currentTimeMillis() - lastSeen > 35_000) {
            value = "Disconnected";
            killedBySystemHint();
        }
        onVpnState(value);
        handler.post(trafficTask);
    }

    private void killedBySystemHint() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryDone = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean autostartDone = prefs.getBoolean("autostart_done", false);
        if (batteryDone && autostartDone) return;
        if (!batteryDone) {
            new AlertDialog.Builder(this)
                .setTitle("VPN dimatikan sistem")
                .setMessage("Android/penghemat daya mematikan VPN saat tidak dipakai. Agar tetap hidup 24/7:\n\n1. Matikan penghemat daya untuk JPS Tunnel\n2. Aktifkan Autostart\n3. Kunci aplikasi di Recents ( Recent → tahan ikon → gembok )")
                .setPositiveButton("Matikan penghemat daya", (d, w) -> openBatterySetting())
                .setNeutralButton("Aktifkan Autostart", (d, w) -> { prefs.edit().putBoolean("autostart_done", true).apply(); openAutostartSetting(); })
                .setNegativeButton("Tutup", null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Satu langkah lagi")
            .setMessage("Penghemat daya sudah nonaktif. Sekarang aktifkan Autostart agar VPN bisa hidup sendiri setelah device restart.\n\nLalu kunci aplikasi di Recents ( Recent → tahan ikon → gembok ).")
            .setPositiveButton("Aktifkan Autostart", (d, w) -> { prefs.edit().putBoolean("autostart_done", true).apply(); openAutostartSetting(); })
            .setNegativeButton("Tutup", null)
            .show();
    }

    private void openBatterySetting() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception ignored) {}
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(trafficTask);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import_clipboard) { importText(clipboard()); return true; }
        if (id == R.id.action_import_file) { openImportFile(); return true; }
        if (id == R.id.action_export_clipboard) { copy(exportLink()); return true; }
        if (id == R.id.action_export_file) { createExportFile(); return true; }
        if (id == R.id.action_export_license) { showExportLicenseDialog(); return true; }
        if (id == R.id.action_settings) { showSettings(); return true; }
        if (id == R.id.action_clear) { confirmClearConfig(); return true; }
        if (id == R.id.action_about) { showAbout(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showSettings() {
        android.view.View form = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        android.widget.CheckBox ping = form.findViewById(R.id.set_ping);
        android.widget.EditText interval = form.findViewById(R.id.set_ping_interval);
        android.widget.EditText url = form.findViewById(R.id.set_ping_url);
        android.widget.CheckBox trafficBox = form.findViewById(R.id.set_traffic);
        android.widget.CheckBox debug = form.findViewById(R.id.set_debug);
        TextView hwidView = form.findViewById(R.id.set_hwid);
        hwidView.setText(hwid);
        ping.setChecked(prefs.getBoolean("http_ping", true));
        interval.setText(String.valueOf(prefs.getInt("http_ping_interval", 3)));
        url.setText(prefs.getString("http_ping_url", VpnService.DEFAULT_PING_URL));
        trafficBox.setChecked(prefs.getBoolean("show_traffic", true));
        debug.setChecked(prefs.getBoolean("debug_mode", false));
        ping.setOnCheckedChangeListener((b, checked) -> applyPingFormState(interval, url, checked));
        applyPingFormState(interval, url, ping.isChecked()); // fields read-only while ping is off
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(form)
            .create();
        dialog.setOnShowListener(d -> {
            try { dialog.getWindow().setBackgroundDrawableResource(R.color.dialog_background); } catch (Exception ignored) {}
        });
        form.findViewById(R.id.set_copy_hwid).setOnClickListener(v -> copy(hwid));
        form.findViewById(R.id.set_cancel).setOnClickListener(v -> dialog.dismiss());
        form.findViewById(R.id.set_done).setOnClickListener(v -> {
            try {
                int seconds;
                try { seconds = Integer.parseInt(interval.getText().toString().trim()); } catch (Exception ignored) { seconds = 3; }
                if (seconds < 1) seconds = 1;
                String pingUrl = url.getText().toString().trim();
                if (pingUrl.isEmpty()) pingUrl = VpnService.DEFAULT_PING_URL;
                boolean show = trafficBox.isChecked();
                prefs.edit()
                    .putBoolean("http_ping", ping.isChecked())
                    .putInt("http_ping_interval", seconds)
                    .putString("http_ping_url", pingUrl)
                    .putBoolean("show_traffic", show)
                    .putBoolean("debug_mode", debug.isChecked())
                    .apply();
                traffic.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                if (!show) traffic.setText("");
                showTraffic = show; // live update: meter row follows the setting immediately
                hasBaseline = false;
                VpnService.applyHttpPing(prefs);
                VpnService.applyDebugMode(prefs);
                dialog.dismiss();
                show("Pengaturan disimpan");
            } catch (Exception error) {
                show("Gagal menyimpan: " + error.getClass().getSimpleName());
            }
        });
        dialog.show();
    }

    /** Ping interval/URL are only editable while HTTP ping is enabled. */
    private static void applyPingFormState(android.widget.EditText interval, android.widget.EditText url, boolean enabled) {
        interval.setEnabled(enabled); url.setEnabled(enabled);
        interval.setAlpha(enabled ? 1f : 0.4f); url.setAlpha(enabled ? 1f : 0.4f);
    }

    private void confirmClearConfig() {
        if (connected) { show("Putuskan VPN dulu sebelum clear config"); return; }
        new AlertDialog.Builder(this)
            .setTitle("Clear Config")
            .setMessage("Hapus semua config, lisensi, dan kembali ke awal?")
            .setPositiveButton("Hapus", (d, w) -> clearConfig())
            .setNegativeButton("Batal", null)
            .show();
    }

    private void clearConfig() {
        prefs.edit().remove("address").remove("uuid").remove("path").remove("sni").remove("host").remove("license_payload").apply();
        ProfileStore.Profile a = profileStore.get(activeProfileId);
        if (a != null) profileStore.put(new ProfileStore.Profile(a.id, a.name, "", "", "/", "", ""));
        license = null;
        address.setText(""); uuid.setText(""); path.setText("/"); sni.setText(""); host.setText("");
        applyLockState();
        show("Config dibersihkan");
    }

    private void showAbout() {
        try {
            android.view.View view = getLayoutInflater().inflate(R.layout.dialog_about, null);
            TextView version = view.findViewById(R.id.about_version);
            try { version.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); }
            catch (Exception ignored) {}
            androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(view)
                .create();
            dialog.setOnShowListener(d -> {
                try { dialog.getWindow().setBackgroundDrawableResource(R.color.dialog_background); } catch (Exception ignored) {}
            });
            view.findViewById(R.id.about_telegram).setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/jhopan_05"))); }
                catch (Exception error) { show("No browser"); }
            });
            view.findViewById(R.id.about_website).setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://jhopanstore.my.id"))); }
                catch (Exception error) { show("No browser"); }
            });
            dialog.show();
        } catch (Exception error) {
            show("About: " + error.getClass().getSimpleName());
        }
    }

    private void openImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(JVS_MIME).addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, IMPORT_FILE); }
        catch (Exception error) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE), IMPORT_FILE); }
    }

    private void createExportFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(JVS_MIME).putExtra(Intent.EXTRA_TITLE, "jhopanstore-vpn.jvs");
        startActivityForResult(intent, EXPORT_FILE);
    }

    private void handleSharedFile(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) readImport(intent.getData());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13);
        }
    }

    private void requestConnect() {
        if (connected) return; // guard spam-click
        ProfileStore.Profile active = activeProfileId == null ? null : profileStore.get(activeProfileId);
        if (license == null && (active == null || !ProfileStore.hasConfig(active))) {
            show(noConfigMessage(active)); // empty profile: explain instead of "Invalid VLESS address"
            return;
        }
        try {
            String uri = activeUri();
            VlessParser.parse(uri);
            Intent intent = android.net.VpnService.prepare(this);
            if (intent == null) connect(uri); else startActivityForResult(intent, VPN_PERMISSION);
        } catch (Exception error) { show(configError(error)); }
    }

    /** Tell the user which profile is empty and whether another one can be used instead. */
    private String noConfigMessage(ProfileStore.Profile active) {
        ProfileStore.Profile other = null;
        for (ProfileStore.Profile p : profileStore.all()) {
            if (ProfileStore.hasConfig(p) && (active == null || !p.id.equals(active.id))) { other = p; break; }
        }
        if (other != null) return "Profil ini kosong. Pilih profil '" + (other.name.isEmpty() ? "Untitled" : other.name) + "' atau import link vless:// dulu.";
        if (active != null && !fieldsExpanded) return "Belum ada config. Tap 'Edit config' atau import link vless:// dulu.";
        return "Belum ada config. Import link vless:// dulu.";
    }

    /** Translate parser messages into something a customer can act on. */
    private static String configError(Exception error) {
        String text = String.valueOf(error.getMessage());
        if (text.contains("Invalid VLESS address")) return "Config tidak lengkap: address atau UUID kosong/salah";
        if (text.contains("Invalid UUID")) return "UUID tidak valid";
        if (text.contains("Link must start")) return "Config harus diawali vless://";
        if (text.contains("Only VLESS")) return "Hanya VLESS + WebSocket + TLS yang didukung";
        if (text.contains("OneRing")) return "SNI OneRing tidak didukung";
        return text;
    }

    private String activeUri() {
        if (license != null) {
            if (LicenseCodec.expired(license)) throw new IllegalStateException("Lisensi kedaluwarsa");
            return license.vless;
        }
        return exportLink();
    }

    private void applyLockState() {
        boolean locked = license != null && license.lock;
        if (locked) fieldsExpanded = false; // locked license never shows the raw config
        configFields.setVisibility(fieldsExpanded ? android.view.View.VISIBLE : android.view.View.GONE);
        toggleFields.setVisibility(locked ? android.view.View.GONE : android.view.View.VISIBLE);
        toggleFields.setText(fieldsExpanded ? "Edit config ▲" : "Edit config ▼");
        lockBanner.setVisibility(locked ? android.view.View.VISIBLE : android.view.View.GONE);
        if (locked) {
            String text = license.name.isEmpty() ? "JPS Tunnel" : license.name;
            if (license.expiry > 0) text += "\nBerlaku s.d. " + SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM).format(new Date(license.expiry));
            lockBanner.setText(text);
        }
    }

    /** Expand/collapse the raw config fields. CONNECT stays visible either way. */
    private void toggleConfigFields() {
        if (license != null && license.lock) { show("Config terkunci"); return; }
        if (fieldsExpanded) saveActiveToStore(); // edits made while expanded are kept
        fieldsExpanded = !fieldsExpanded;
        configFields.setVisibility(fieldsExpanded ? android.view.View.VISIBLE : android.view.View.GONE);
        toggleFields.setText(fieldsExpanded ? "Edit config ▲" : "Edit config ▼");
    }

    private void loadLicense() {
        String stored = prefs.getString("license_payload", null);
        if (stored == null) { license = null; return; }
        try {
            license = LicenseCodec.decode(stored, hwid);
            if (LicenseCodec.expired(license)) { show("Lisensi kedaluwarsa"); prefs.edit().remove("license_payload").apply(); license = null; }
        } catch (Exception error) {
            prefs.edit().remove("license_payload").apply();
            license = null;
        }
    }

    private void showExportLicenseDialog() {
        try {
            VlessParser.parse(exportLink()); // config must be valid before selling it
        } catch (Exception error) { show(error.getMessage()); return; }
        android.view.View form = getLayoutInflater().inflate(R.layout.dialog_export_license, null);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Export Locked Config")
            .setView(form)
            .create();
        dialog.setOnShowListener(d -> {
            try { dialog.getWindow().setBackgroundDrawableResource(R.color.dialog_background); } catch (Exception ignored) {}
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setVisibility(android.view.View.GONE);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setVisibility(android.view.View.GONE);
            TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (title != null) { title.setTextColor(android.graphics.Color.WHITE); title.setTextSize(18); }
        });
        form.findViewById(R.id.lic_cancel).setOnClickListener(v -> dialog.dismiss());
        form.findViewById(R.id.lic_create).setOnClickListener(v -> {
            String name = ((EditText) form.findViewById(R.id.lic_name)).getText().toString().trim();
            String customerHwid = ((EditText) form.findViewById(R.id.lic_hwid)).getText().toString().trim().toUpperCase(Locale.US);
            String expiryText = ((EditText) form.findViewById(R.id.lic_expiry)).getText().toString().trim();
            boolean lock = ((android.widget.CheckBox) form.findViewById(R.id.lic_lock)).isChecked();
            if (customerHwid.length() != 24) { show("HWID harus 24 karakter"); return; }
            long expiry = 0;
            if (!expiryText.isEmpty()) {
                try {
                    SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
                    format.setLenient(false);
                    expiry = format.parse(expiryText).getTime() + 86_400_000L; // end of that day
                } catch (Exception error) { show("Tanggal salah (dd/mm/yyyy)"); return; }
            }
            try {
                String payload = LicenseCodec.encode(new License(exportLink(), name, customerHwid, lock, expiry));
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(JVS_MIME).putExtra(Intent.EXTRA_TITLE, "jhopanstore-locked.jvs");
                pendingExportPayload = payload;
                startActivityForResult(intent, EXPORT_LICENSE);
                dialog.dismiss();
            } catch (Exception error) { show("Export gagal: " + error.getClass().getSimpleName()); }
        });
        dialog.show();
    }
    private String pendingExportPayload;

    private void importText(String text) {
        String value = text == null ? "" : text.trim();
        if (LicenseCodec.isEncoded(value)) { importLicense(value); return; }
        boolean hadLicense = license != null;
        try {
            VlessParser.parse(value); // validate first
            if (hadLicense) { prefs.edit().remove("license_payload").apply(); license = null; applyLockState(); }
            // open editor prefilled (NekoBox style): user reviews + names + saves
            android.content.Intent it = new android.content.Intent(this, ProfileEditActivity.class);
            it.putExtra(ProfileEditActivity.EXTRA_RAW_URI, value);
            startActivityForResult(it, 77);
        } catch (Exception error) { show(error.getMessage()); }
    }

    private void importLicense(String payload) {
        try {
            License incoming = LicenseCodec.decode(payload, hwid);
            if (LicenseCodec.expired(incoming)) { show("Lisensi kedaluwarsa"); return; }
            prefs.edit().putString("license_payload", payload).apply();
            license = incoming;
            applyLockState();
            show("Lisensi terpasang" + (incoming.lock ? " (config terkunci)" : ""));
        } catch (Exception error) {
            String message = error instanceof javax.crypto.AEADBadTagException
                ? "File bukan untuk HWID device ini"
                : "Lisensi gagal: " + error.getMessage();
            show(message);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VPN_PERMISSION && result == RESULT_OK) connect(activeUri());
        if (request == IMPORT_FILE && result == RESULT_OK && data != null && data.getData() != null) readImport(data.getData());
        if (request == EXPORT_FILE && result == RESULT_OK && data != null && data.getData() != null) writeExport(data.getData());
        if (request == EXPORT_LICENSE && result == RESULT_OK && data != null && data.getData() != null) writeLicense(data.getData());
        if (request == 77 && result == RESULT_OK) {
            ProfileStore.Profile a = profileStore.active();
            activeProfileId = a == null ? null : a.id;
            if (a != null) loadFieldsFrom(a);
            renderProfileTabs();
            renderProfileList();
        }
    }

    private void connect(String uri) { save(); VpnService.start(this, uri); }
    private void disconnect() { VpnService.stop(this); }
    private void onVpnState(String value) {
        if (value == null) value = "Disconnected";
        status.setText(value);
        // status may carry extra lines (HTTP ping log) — compare by prefix, not equality
        boolean busy = value.startsWith("Connecting") || value.startsWith("Checking internet");
        connected = value.startsWith("Connected") || busy || value.startsWith("Reconnecting");
        connect.setText(connected ? "DISCONNECT" : "CONNECT");
        connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(connected ? "#E53935" : "#4CAF50")));
        connect.setEnabled(!busy);
        // The service re-emits the same state string on every HTTP ping line (~3 s apart). Resetting the
        // meter on each of those cleared the rate baseline before two samples could ever be compared, so
        // the speed read "0 B/s" forever and the text flickered between two different formats. Only a
        // genuine transition into Connected should reset it.
        boolean nowConnected = value.startsWith("Connected");
        boolean enteredConnected = nowConnected && !stateWasConnected;
        stateWasConnected = nowConnected;
        if (!connected) {
            // also covers failure reasons (no network, TLS, WebSocket): meter goes back to zero
            totalRx = 0; totalTx = 0; hasBaseline = false;
            if (showTraffic) traffic.setText("↓ 0 B   ↑ 0 B");
        } else if (enteredConnected) {
            resetTraffic();
        }
    }

    private final Runnable trafficTask = new Runnable() {
        @Override public void run() {
            if (connected && showTraffic) updateTraffic();
            handler.postDelayed(this, 2000);
        }
    };

    /**
     * Sole writer of the meter text, so the format can never alternate between repaints (it used to
     * flip between "↓ X ↑ Y" and "↓ X (0 B/s) ↑ Y (0 B/s)", which read as the meter glitching).
     */
    private void renderTraffic(long downRate, long upRate) {
        if (!showTraffic) return;
        traffic.setText("↓ " + bytes(totalRx) + " (" + bytes(downRate) + "/s)   ↑ " + bytes(totalTx) + " (" + bytes(upRate) + "/s)");
    }

    private void updateTraffic() {
        SharedPreferences vpnStatus = getSharedPreferences("vpn_status", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long rx = android.net.TrafficStats.getUidRxBytes(uid);
        long tx = android.net.TrafficStats.getUidTxBytes(uid);
        long downRate = 0, upRate = 0;
        if (rx >= 0 && tx >= 0) {
            // Derive the session total here rather than waiting for the service's session_rx, which is
            // only refreshed on probe ticks (30–90 s) and made the counters look frozen in between.
            long baseRx = vpnStatus.getLong("meter_base_rx", -1);
            long baseTx = vpnStatus.getLong("meter_base_tx", -1);
            totalRx = baseRx >= 0 && rx >= baseRx ? rx - baseRx : vpnStatus.getLong("session_rx", 0);
            totalTx = baseTx >= 0 && tx >= baseTx ? tx - baseTx : vpnStatus.getLong("session_tx", 0);
            if (!hasBaseline) {
                lastRx = rx; lastTx = tx; lastSample = now; hasBaseline = true; // first tick: seed only
            } else {
                long dRx = rx - lastRx, dTx = tx - lastTx;
                if (dRx >= 0 && dTx >= 0) {
                    long elapsed = Math.max(1, now - lastSample);
                    downRate = dRx * 1000 / elapsed; upRate = dTx * 1000 / elapsed;
                }
                lastRx = rx; lastTx = tx; lastSample = now;
            }
        }
        renderTraffic(downRate, upRate);
    }

    private void resetTraffic() {
        hasBaseline = false; // next 2 s sample seeds the rate baseline
        SharedPreferences vpnStatus = getSharedPreferences("vpn_status", MODE_PRIVATE);
        totalRx = vpnStatus.getLong("session_rx", 0);
        totalTx = vpnStatus.getLong("session_tx", 0);
        renderTraffic(0, 0);
    }
    private static String bytes(long value) { return value < 1024 ? value + " B" : value < 1048576 ? String.format("%.1f KB", value / 1024d) : String.format("%.2f MB", value / 1048576d); }

    private String exportLink() {
        // Read from ProfileStore (source of truth), not UI fields (may be hidden)
        ProfileStore.Profile p = activeProfileId == null ? null : profileStore.get(activeProfileId);
        if (p == null) return "";
        String raw = p.address; int divider = raw.lastIndexOf(':');
        String server = divider > 0 ? raw.substring(0, divider) : raw;
        int port = divider > 0 ? parsePort(raw.substring(divider + 1)) : 443;
        String serverName = p.sni.isEmpty() ? server : p.sni;
        String wsHost = p.host.isEmpty() ? serverName : p.host;
        return VlessParser.export(new VlessConfig(server, port, p.uuid, p.path.isEmpty() ? "/" : p.path, serverName, wsHost, true));
    }

    private static int parsePort(String value) { try { int port = Integer.parseInt(value); return port > 0 && port < 65536 ? port : 443; } catch (Exception ignored) { return 443; } }
    private static String text(EditText field) { return field.getText().toString().trim(); }
    private void load() {
        ProfileStore.Profile p = profileStore.ensureDefault();
        activeProfileId = p.id;
        address.setText(p.address); uuid.setText(p.uuid); path.setText(p.path.isEmpty() ? "/" : p.path); sni.setText(p.sni); host.setText(p.host);
        renderProfileTabs();
    }

    private void renderProfileTabs() {
        if (profileList == null) profileList = findViewById(R.id.profileList);
        renderProfileList();
        // active profile marker shown on the list cards (strip color)
    }

    /** RecyclerView adapter for profile cards (NekoBox style). */
    private void renderProfileList() {
        if (profileList.getLayoutManager() == null) profileList.setLayoutManager(new LinearLayoutManager(this));
        java.util.List<ProfileStore.Profile> list = profileStore.all();
        java.util.List<ProfileStore.Profile> rows = new java.util.ArrayList<>(list);
        rows.add(null); // "+ add" row
        profileList.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                android.view.View v = getLayoutInflater().inflate(R.layout.item_profile_card, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                android.view.View v = h.itemView;
                android.view.View marker = v.findViewById(R.id.card_marker);
                android.widget.TextView name = v.findViewById(R.id.card_name);
                android.widget.TextView addr = v.findViewById(R.id.card_address);
                android.view.View edit = v.findViewById(R.id.card_edit);
                android.view.View del = v.findViewById(R.id.card_delete);
                if (pos == rows.size() - 1) { // add row
                    marker.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
                    name.setText("+ Tambah profil");
                    name.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
                    addr.setText("");
                    edit.setVisibility(android.view.View.GONE);
                    del.setVisibility(android.view.View.GONE);
                    v.setOnClickListener(x -> startActivityForResult(new android.content.Intent(x.getContext(), ProfileEditActivity.class), 77));
                    return;
                }
                ProfileStore.Profile p = rows.get(pos);
                boolean active = p.id.equals(activeProfileId);
                marker.setBackgroundColor(android.graphics.Color.parseColor(active ? "#4CAF50" : "#333333"));
                name.setText(p.name.isEmpty() ? "Untitled" : p.name);
                name.setTextColor(android.graphics.Color.parseColor(active ? "#00E5FF" : "#FFFFFF"));
                addr.setText((p.address.isEmpty() ? "-" : p.address) + " • " + (p.path.isEmpty() ? "/" : p.path));
                edit.setVisibility(android.view.View.VISIBLE);
                del.setVisibility(profileStore.count() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
                v.setOnClickListener(x -> {
                    saveActiveToStore();
                    activeProfileId = p.id;
                    profileStore.setActiveId(p.id);
                    loadFieldsFrom(p);
                    renderProfileTabs();
                    renderProfileList();
                    show("Profil aktif: " + (p.name.isEmpty() ? "Untitled" : p.name));
                });
                edit.setOnClickListener(x -> {
                    android.content.Intent it = new android.content.Intent(profileList.getContext(), ProfileEditActivity.class);
                    it.putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, p.id);
                    ((android.app.Activity) v.getContext()).startActivityForResult(it, 77);
                });
                del.setOnClickListener(x -> confirmDeleteProfile(p));
            }
            @Override public int getItemCount() { return rows.size(); }
        });
    }

    private void saveActiveToStore() {
        if (activeProfileId == null) return; // never write a profile without an id
        profileStore.put(new ProfileStore.Profile(activeProfileId, profileName(activeProfileId),
            text(address), text(uuid), text(path), text(sni), text(host)));
    }

    private String profileName(String id) {
        ProfileStore.Profile p = profileStore.get(id);
        return p == null ? "" : p.name;
    }

    private void confirmDeleteProfile(final ProfileStore.Profile p) {
        if (profileStore.count() <= 1) { show("Minimal harus ada 1 profil"); return; }
        if (connected) { show("Putuskan VPN dulu sebelum hapus profil"); return; }
        String name = p.name.isEmpty() ? "Untitled" : p.name;
        String message = "Hapus profil '" + name + "'?";
        if (ProfileStore.hasConfig(p) && profileStore.countWithConfig() == 1) {
            message += "\n\nIni satu-satunya profil yang punya config. Setelah dihapus kamu harus import atau isi config lagi sebelum bisa connect.";
        }
        new AlertDialog.Builder(this)
            .setTitle("Hapus profil")
            .setMessage(message)
            .setPositiveButton("Hapus", (d, w) -> deleteProfile(p))
            .setNegativeButton("Batal", null)
            .show();
    }

    /** Delete a profile without losing unsaved field edits, then follow the store's new active profile. */
    private void deleteProfile(ProfileStore.Profile p) {
        boolean wasActive = p.id.equals(activeProfileId);
        saveActiveToStore(); // field edits not saved yet must survive the delete
        profileStore.remove(p.id);
        if (wasActive) {
            ProfileStore.Profile a = profileStore.active();
            activeProfileId = a == null ? null : a.id;
            if (a != null) loadFieldsFrom(a);
        }
        renderProfileTabs();
        show(profileStore.countWithConfig() == 0
            ? "Profil dihapus — belum ada config, import dulu"
            : "Profil dihapus");
    }

    private void loadFieldsFrom(ProfileStore.Profile p) {
        address.setText(p.address); uuid.setText(p.uuid); path.setText(p.path.isEmpty() ? "/" : p.path);
        sni.setText(p.sni); host.setText(p.host);
    }

    private void save() {
        prefs.edit().putString("address", text(address)).putString("uuid", text(uuid)).putString("path", text(path)).putString("sni", text(sni)).putString("host", text(host)).apply();
        if (activeProfileId != null) profileStore.put(new ProfileStore.Profile(activeProfileId, profileName(activeProfileId), text(address), text(uuid), text(path), text(sni), text(host)));
    }

    private String clipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        return manager.hasPrimaryClip() ? String.valueOf(manager.getPrimaryClip().getItemAt(0).coerceToText(this)) : "";
    }

    private void copy(String value) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("JPS Tunnel", value)); show("Copied"); }

    private void readImport(Uri uri) {
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
            importText(reader.readLine());
        } catch (Exception error) { show("Import failed"); }
    }

    private void writeExport(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            output.write(exportLink().getBytes(StandardCharsets.UTF_8)); show("Exported");
        } catch (Exception error) { show("Export failed"); }
    }

    private void writeLicense(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            output.write(pendingExportPayload.getBytes(StandardCharsets.UTF_8)); show("Locked config exported");
        } catch (Exception error) { show("Export failed"); }
        pendingExportPayload = null;
    }

    private void show(String value) { Toast.makeText(this, value == null ? "Error" : value, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { VpnService.setListener(null); handler.removeCallbacks(trafficTask); super.onDestroy(); }
}
