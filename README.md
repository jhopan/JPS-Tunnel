<div align="center">

<img src="design/logo.svg" alt="JPS Tunnel" width="128"/>

# JPS Tunnel

A lightweight Android VPN client for VLESS over WebSocket + TLS.

**Light. Stable. 24/7.**

[![Release](https://img.shields.io/github/v/release/jhopan/JPS-Tunnel?label=release&color=4CAF50)](https://github.com/jhopan/JPS-Tunnel/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%2024%2B-121212)](#build)
[![Architecture](https://img.shields.io/badge/arch-arm64--v8a-1976D2)](#build)

Made with :green_heart: by **[JhopanStore](https://jhopanstore.my.id)** • [Telegram](https://t.me/jhopan_05)

</div>

## Overview

JPS Tunnel is a Java-only Android VPN client built on `VpnService` and a local `libbox` (sing-box v1.11.0) runtime. It speaks exactly one protocol — VLESS + WebSocket + TLS with normal SNI — and focuses on three things: staying light, staying honest about connection state, and staying alive around the clock.

## Features

### Connection

- VLESS + WebSocket + TLS with normal SNI; path, SNI, and Host preserved exactly as supplied
- IPv4-first outbound (`prefer_ipv4`); Cloudflare DNS primary (1.1.1.1), Google backup (8.8.8.8) — consistent across both the sing-box config and the Android TUN
- Stable routing via `override_android_vpn` — Android `protect(fd)`, no interface guessing, works on both Qualcomm and MediaTek
- Honest status flow: Connecting → Checking internet → Connected, only after a real HTTP 204 through the tunnel; failures show a safe reason (no network, DNS, TLS, WebSocket)

### 24/7 resilience

- Persistent foreground service with `START_STICKY` and saved URI auto-reconnect
- Screen-on auto-heal probe, network-change probe, adaptive health checks (90 s stable / 30 s recovering / 2 s after events)
- Auto-reconnect after 3 failed probes, capped at 3 attempts — no battery-hungry loops
- Keep-alive on task removal: swiping the app away restarts the service while connected
- Stepwise setup guidance: battery optimization, MIUI Autostart, Recents lock — with direct settings links

### Licensing (offline HWID)

- Export Locked Config: set a config name, customer HWID, and expiry date (dd/mm/yyyy)
- The `.jvs` payload is AES-256-GCM encrypted with a key derived from the customer's HWID — only that device can open it
- Wrong HWID or expired license → rejected; lock hides all config fields and shows only the name and expiry
- Importing the customer's own VLESS URI releases the license automatically; Clear Config wipes everything back to first-run state

### Import / export

- Clipboard and `.jvs` file import/export; `.jvs` files open directly with the app
- HWID copy for license issuing; About dialog with Telegram and website links

### Efficiency

- Session traffic meter: counts from zero on every connect, resets on disconnect, survives app close, toggleable in the menu
- No wake locks; adaptive probing; log level `warn`; traffic sampling pauses in background
- Measured: ~66 MB RSS, ~0.5% idle CPU, release APK ~23 MB

> [!NOTE]
> Scope is intentionally narrow: no QUIC, hotspot sharing, backup servers, rules, failover, or wake locks. `allowInsecure=true` is the default to support compatible Cloudflare Worker bug-domain profiles.

## Download

Grab the latest APK from GitHub Releases:

```bash
https://github.com/jhopan/JPS-Tunnel/releases/latest/download/JhopanStoreVPN.apk
```

Versioned releases (`v1.0.0` and newer) are permanent; the `latest` tag tracks the current build of `main`.

## Build

Requirements: JDK 17, Android SDK API 35, Go, Android NDK, Git Bash or WSL.

```bash
# Rebuild the local ARM64 libbox AAR (only when needed)
bash build_libbox.sh

# Build the app
./gradlew assembleDebug
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

> [!IMPORTANT]
> `build_libbox.sh` pins sing-box `v1.11.0` and produces an ARM64-only `libbox.aar` (gVisor + Clash API retained, QUIC/uTLS removed, symbols stripped). The Java `VpnService` targets this exact tested libbox API — upgrade sing-box only together with a tested libbox migration.

CI (GitHub Actions) builds the release APK on every push to `main` and publishes it to the rolling `latest` release; pushing a `v*` tag creates a permanent versioned release.

## TUN stack note

The TUN inbound uses the `system` stack instead of sing-box's default `gvisor`, measured on a MediaTek device (vivo 1802, LTE):

| metric | gvisor | system |
|---|---|---|
| Total PSS | ~106 MB | ~66 MB |
| HTTP 204 via tunnel | ~0.4–0.9 s | ~0.4–0.5 s |
| stress + screen-off recovery | ok | ok |

Both stacks passed connect, browse, stress, and 30 s screen-off recovery. If any "some pages won't load" report appears, revert by removing `"stack": "system"` in `SingboxConfig.java`.

## Licensing notes

The HWID is a SHA-256 hash of a random installation ID (24 hex chars). It survives restarts and updates but resets when the user clears app data or uninstalls — they will then need a new license file. Licensing is fully offline: no server, no remote revocation. To renew, send a new license file, or the customer can import their own VLESS URI, which releases the license.
