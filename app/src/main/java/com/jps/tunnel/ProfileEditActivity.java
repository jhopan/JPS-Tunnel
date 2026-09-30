package com.jps.tunnel;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.jps.tunnel.core.ProfileStore;

/** Full-screen profile editor (NekoBox style): name + config in one page. */
public final class ProfileEditActivity extends AppCompatActivity {
    static final String EXTRA_PROFILE_ID = "profile_id";
    static final String EXTRA_RAW_URI = "raw_uri";

    private ProfileStore store;
    private String profileId;
    private String rawUri;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_profile_edit);
        Toolbar toolbar = findViewById(R.id.editToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        store = new ProfileStore(getSharedPreferences("vpn", MODE_PRIVATE));
        profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        rawUri = getIntent().getStringExtra(EXTRA_RAW_URI);

        EditText name = findViewById(R.id.edit_name);
        EditText address = findViewById(R.id.edit_address);
        EditText uuid = findViewById(R.id.edit_uuid);
        EditText path = findViewById(R.id.edit_path);
        EditText sni = findViewById(R.id.edit_sni);
        EditText host = findViewById(R.id.edit_host);

        ProfileStore.Profile p = profileId == null ? null : store.get(profileId);
        if (p != null) {
            toolbar.setTitle(p.name.isEmpty() ? "Edit profil" : "Edit: " + p.name);
            name.setText(p.name); address.setText(p.address); uuid.setText(p.uuid);
            path.setText(p.path.isEmpty() ? "/" : p.path); sni.setText(p.sni); host.setText(p.host);
        } else {
            toolbar.setTitle("Profil baru");
            path.setText("/");
        }

        // raw URI import (clipboard): prefill via parser
        if (rawUri != null && !rawUri.isEmpty()) {
            try {
                com.jps.tunnel.core.VlessConfig c = com.jps.tunnel.core.VlessParser.parse(rawUri.trim());
                address.setText(c.address + ":" + c.port); uuid.setText(c.uuid); path.setText(c.path);
                sni.setText(c.sni); host.setText(c.host);
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        findViewById(R.id.edit_save).setOnClickListener(v -> {
            try {
                String addr = address.getText().toString().trim();
                String uid = uuid.getText().toString().trim();
                String nm = name.getText().toString().trim();
                if (addr.isEmpty() || uid.isEmpty()) {
                    Toast.makeText(this, "Address dan UUID wajib diisi", Toast.LENGTH_SHORT).show();
                    return;
                }
                // validate before save
                com.jps.tunnel.core.VlessParser.export(new com.jps.tunnel.core.VlessConfig(
                    hostPart(addr), portOf(addr), uid,
                    path.getText().toString().trim().isEmpty() ? "/" : path.getText().toString().trim(),
                    sni.getText().toString().trim().isEmpty() ? hostPart(addr) : sni.getText().toString().trim(),
                    host.getText().toString().trim().isEmpty() ? sni.getText().toString().trim() : host.getText().toString().trim(),
                    true));

                if (profileId == null) profileId = ProfileStore.newId();
                boolean ok = store.put(new ProfileStore.Profile(profileId,
                    nm.isEmpty() ? "Profile " + (store.count() + 1) : nm,
                    addr, uid,
                    path.getText().toString().trim().isEmpty() ? "/" : path.getText().toString().trim(),
                    sni.getText().toString().trim(), host.getText().toString().trim()));
                if (!ok) { Toast.makeText(this, "Maksimal 10 profil", Toast.LENGTH_SHORT).show(); return; }
                store.setActiveId(profileId);
                setResult(RESULT_OK);
                finish();
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage() == null ? "Config tidak valid" : error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String hostPart(String addrPort) {
        int i = addrPort.lastIndexOf(':');
        return i > 0 ? addrPort.substring(0, i) : addrPort;
    }
    private static int portOf(String addrPort) {
        int i = addrPort.lastIndexOf(':');
        if (i <= 0) return 443;
        try { int v = Integer.parseInt(addrPort.substring(i + 1)); return v > 0 && v < 65536 ? v : 443; }
        catch (Exception ignored) { return 443; }
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }
}
