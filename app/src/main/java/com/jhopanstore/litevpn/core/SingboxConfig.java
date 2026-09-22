package com.jhopanstore.litevpn.core;

import org.json.JSONArray;
import org.json.JSONObject;

public final class SingboxConfig {
    public static final int PROXY_PORT = 10808;
    private SingboxConfig() {}

    public static String build(VlessConfig config, String cachePath) throws Exception {
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("level", "warn"));
        root.put("dns", new JSONObject().put("servers", new JSONArray()
            .put(new JSONObject().put("tag", "dns-main").put("address", "1.1.1.1").put("strategy", "prefer_ipv4"))
            .put(new JSONObject().put("tag", "dns-backup").put("address", "8.8.8.8").put("strategy", "prefer_ipv4"))
            .put(new JSONObject().put("tag", "dns-local").put("address", "local")))
            .put("rules", new JSONArray().put(new JSONObject().put("outbound", "direct").put("server", "dns-local"))));
        root.put("inbounds", new JSONArray()
            .put(new JSONObject().put("type", "tun").put("tag", "tun-in").put("inet4_address", "172.19.0.1/30").put("mtu", 1280).put("auto_route", true).put("strict_route", true).put("sniff", true).put("sniff_override_destination", false).put("stack", "gvisor"))
            .put(new JSONObject().put("type", "mixed").put("tag", "mixed-in").put("listen", "127.0.0.1").put("listen_port", PROXY_PORT)));
        JSONObject tls = new JSONObject().put("enabled", true)
            .put("server_name", config.sni)
            .put("insecure", config.allowInsecure);
        JSONObject ws = new JSONObject().put("type", "ws").put("path", config.path).put("headers", new JSONObject().put("Host", config.host));
        root.put("outbounds", new JSONArray()
            .put(new JSONObject().put("type", "vless").put("tag", "proxy").put("server", config.address).put("server_port", config.port).put("uuid", config.uuid).put("domain_strategy", "prefer_ipv4").put("tls", tls).put("transport", ws))
            .put(new JSONObject().put("type", "direct").put("tag", "direct"))
            .put(new JSONObject().put("type", "block").put("tag", "block"))
            .put(new JSONObject().put("type", "dns").put("tag", "dns-out")));
        root.put("route", new JSONObject().put("auto_detect_interface", false).put("override_android_vpn", true)
            .put("rules", new JSONArray().put(new JSONObject().put("protocol", "dns").put("outbound", "dns-out")))
            .put("final", "proxy"));
        root.put("experimental", new JSONObject().put("cache_file", new JSONObject().put("enabled", true).put("path", cachePath + "/cache.db")));
        return root.toString();
    }
}
