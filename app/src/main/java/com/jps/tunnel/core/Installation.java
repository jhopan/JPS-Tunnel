package com.jps.tunnel.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Device-bound installation HWID (SHA-256 of a random install id). */
public final class Installation {
    private Installation() {}

    public static String id(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("vpn", Context.MODE_PRIVATE);
        String id = prefs.getString("installation_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("installation_id", id).apply();
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte part : hash) out.append(String.format("%02X", part));
            return out.substring(0, 24);
        } catch (Exception error) {
            return id;
        }
    }
}
