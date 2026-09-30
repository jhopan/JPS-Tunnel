package com.jps.tunnel.core;

import android.net.Uri;
import java.net.URLEncoder;
import java.util.UUID;

public final class VlessParser {
    private VlessParser() {}

    public static VlessConfig parse(String value) throws IllegalArgumentException {
        if (value == null || !value.startsWith("vless://")) throw new IllegalArgumentException("Link must start with vless://");
        Uri uri = Uri.parse(value);
        String uuid = uri.getUserInfo();
        String address = uri.getHost();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (uuid == null || address == null || address.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid VLESS address");
        try { UUID.fromString(uuid); } catch (Exception error) { throw new IllegalArgumentException("Invalid UUID"); }
        String type = query(uri, "type", "ws");
        String security = query(uri, "security", "tls");
        if (!"ws".equalsIgnoreCase(type) || !"tls".equalsIgnoreCase(security)) throw new IllegalArgumentException("Only VLESS WebSocket TLS is supported");
        String sni = query(uri, "sni", address);
        if (sni.toLowerCase().startsWith("onering:")) throw new IllegalArgumentException("OneRing SNI is not supported");
        return new VlessConfig(address, port, uuid, query(uri, "path", "/"), sni, query(uri, "host", sni), Boolean.parseBoolean(query(uri, "allowInsecure", "true")));
    }

    public static String export(VlessConfig config) {
        return "vless://" + config.uuid + "@" + config.address + ":" + config.port
            + "?type=ws&security=tls&path=" + encode(config.path)
            + "&sni=" + encode(config.sni) + "&host=" + encode(config.host)
            + "&allowInsecure=" + config.allowInsecure + "#VpnService";
    }

    private static String query(Uri uri, String key, String fallback) {
        String value = uri.getQueryParameter(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException error) { return value; }
    }
}
