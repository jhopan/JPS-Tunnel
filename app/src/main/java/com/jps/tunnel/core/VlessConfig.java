package com.jps.tunnel.core;

public final class VlessConfig {
    public final String address;
    public final int port;
    public final String uuid;
    public final String path;
    public final String sni;
    public final String host;
    public final boolean allowInsecure;

    public VlessConfig(String address, int port, String uuid, String path, String sni, String host, boolean allowInsecure) {
        this.address = address;
        this.port = port;
        this.uuid = uuid;
        this.path = path;
        this.sni = sni;
        this.host = host;
        this.allowInsecure = allowInsecure;
    }
}
