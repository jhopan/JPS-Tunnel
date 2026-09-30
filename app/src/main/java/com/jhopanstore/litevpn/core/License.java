package com.jhopanstore.litevpn.core;

/**
 * Offline license payload embedded in a locked .jps file.
 * Created by the seller (this app): current config encrypted with the
 * customer's HWID-derived key, so only that customer's device can open it.
 */
public final class License {
    public final String vless;
    public final String name;
    public final String hwid;
    public final boolean lock;
    public final long expiry;

    public License(String vless, String name, String hwid, boolean lock, long expiry) {
        this.vless = vless;
        this.name = name;
        this.hwid = hwid;
        this.lock = lock;
        this.expiry = expiry;
    }
}
