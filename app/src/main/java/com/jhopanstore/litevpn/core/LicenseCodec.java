package com.jhopanstore.litevpn.core;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * Offline AES-GCM license codec. The payload is encrypted with a key derived
 * from the customer HWID, so only the device holding that HWID can decrypt it.
 * Wrong device = GCM auth failure, garbage in, garbage rejected.
 */
public final class LicenseCodec {
    private static final String SALT = "jps-tunnel-license-v1";
    private static final String PREFIX = "JLS1:";
    private static final byte VERSION = 0x01;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private LicenseCodec() {}

    public static boolean isEncoded(String text) { return text != null && text.startsWith(PREFIX); }

    public static String encode(License license) throws Exception {
        JSONObject root = new JSONObject();
        root.put("v", license.vless);
        root.put("n", license.name);
        root.put("h", license.hwid);
        root.put("l", license.lock);
        root.put("e", license.expiry);
        byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);

        SecretKeySpec key = keyFor(license.hwid);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plain);

        byte[] payload = new byte[1 + IV_LEN + ct.length];
        payload[0] = VERSION;
        System.arraycopy(iv, 0, payload, 1, IV_LEN);
        System.arraycopy(ct, 0, payload, 1 + IV_LEN, ct.length);
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP);
    }

    public static License decode(String text, String deviceHwid) throws Exception {
        if (!isEncoded(text)) throw new IllegalArgumentException("Not a locked config");
        byte[] payload = Base64.decode(text.substring(PREFIX.length()), Base64.NO_WRAP);
        if (payload.length < 1 + IV_LEN) throw new IllegalArgumentException("Corrupt license");
        if (payload[0] != VERSION) throw new IllegalArgumentException("Unsupported license version");
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[payload.length - 1 - IV_LEN];
        System.arraycopy(payload, 1, iv, 0, IV_LEN);
        System.arraycopy(payload, 1 + IV_LEN, ct, 0, ct.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keyFor(deviceHwid), new GCMParameterSpec(TAG_BITS, iv));
        byte[] plain = cipher.doFinal(ct); // AEADBadTagException when HWID mismatch
        JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));

        License license = new License(
            root.optString("v", ""),
            root.optString("n", ""),
            deviceHwid,
            root.optBoolean("l", true),
            root.optLong("e", 0)
        );
        if (license.vless.isEmpty()) throw new IllegalArgumentException("Corrupt license");
        return license;
    }

    public static boolean expired(License license) {
        return license.expiry > 0 && System.currentTimeMillis() > license.expiry;
    }

    private static SecretKeySpec keyFor(String hwid) throws Exception {
        byte[] bytes = (hwid + SALT).getBytes(StandardCharsets.UTF_8);
        byte[] key = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new SecretKeySpec(key, "AES");
    }
}
