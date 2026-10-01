package main

import (
	"encoding/json"
	"testing"
)

func TestBuildConfigUsesVlessWebSocketTLS(t *testing.T) {
	data, err := buildConfig(settings{
		Address: "server.example", UUID: "00000000-0000-0000-0000-000000000000",
		Path: "/vless", SNI: "sni.example", Host: "host.example",
	}, 443)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(data, &config); err != nil {
		t.Fatal(err)
	}
	outbounds := config["outbounds"].([]any)
	proxy := outbounds[0].(map[string]any)
	if proxy["type"] != "vless" || proxy["server"] != "server.example" || proxy["server_port"].(float64) != 443 {
		t.Fatalf("bad proxy: %#v", proxy)
	}
	transport := proxy["transport"].(map[string]any)
	if transport["type"] != "ws" || transport["path"] != "/vless" {
		t.Fatalf("bad transport: %#v", transport)
	}
}

func TestParsePort(t *testing.T) {
	if _, err := parsePort("443"); err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{"0", "65536", "wrong"} {
		if _, err := parsePort(value); err == nil {
			t.Fatalf("expected bad port %q", value)
		}
	}
}
