package main

import "testing"

func TestParseVLESSWebSocketTLS(t *testing.T) {
	p, err := parseVLESS("vless://00000000-0000-0000-0000-000000000000@server.example:443?type=ws&security=tls&path=%2Fvless&sni=sni.example&host=host.example#Test")
	if err != nil {
		t.Fatal(err)
	}
	if p.Name != "Test" || p.Address != "server.example" || p.Port != "443" || p.Path != "/vless" || p.SNI != "sni.example" || p.Host != "host.example" {
		t.Fatalf("bad profile: %#v", p)
	}
}

func TestRejectNonWebSocketTLS(t *testing.T) {
	if _, err := parseVLESS("vless://00000000-0000-0000-0000-000000000000@server.example:443?type=tcp&security=tls"); err == nil {
		t.Fatal("expected unsupported transport rejection")
	}
}

func TestParsePort(t *testing.T) {
	if _, err := parsePort("443"); err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{"0", "65536", "bad"} {
		if _, err := parsePort(value); err == nil {
			t.Fatalf("expected invalid port: %s", value)
		}
	}
}
