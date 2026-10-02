<div align="center">

<img src="design/logo.svg" alt="JPS Tunnel" width="120"/>

# JPS Tunnel

**VPN ringan berbasis sing-box — VLESS WebSocket TLS**

Stabil 24/7 · Hemat baterai · Multi-platform

[![Android Release](https://img.shields.io/github/v/release/jhopan/JPS-Tunnel?label=Android&color=10b981&logo=android)](https://github.com/jhopan/JPS-Tunnel/releases/latest)
[![Desktop Release](https://img.shields.io/github/v/release/jhopan/JPS-Tunnel?label=Desktop&color=3b82f6&logo=windows)](https://github.com/jhopan/JPS-Tunnel/releases)
[![Platform](https://img.shields.io/badge/Android-24%2B-121212?logo=android)](https://github.com/jhopan/JPS-Tunnel/releases/latest)
[![Go](https://img.shields.io/badge/Go-1.26-00ADD8?logo=go)](desktop/go.mod)
[![sing-box](https://img.shields.io/badge/sing--box-v1.11.15-6366f1)](https://github.com/SagerNet/sing-box)

Dibuat oleh **[JhopanStore](https://jhopanstore.my.id)** &nbsp;·&nbsp; [Telegram](https://t.me/jhopan_05)

</div>

---

## Tentang

JPS Tunnel adalah klien VPN multi-platform (Android + Desktop) yang berjalan di atas [sing-box](https://github.com/SagerNet/sing-box) dengan TUN stack gVisor. Hanya mendukung satu protokol — **VLESS + WebSocket + TLS** — dengan fokus pada kesederhanaan, kestabilan, dan efisiensi.

| | Android | Desktop |
|---|---|---|
| **Bahasa** | Java + XML | Go + Gio |
| **Ukuran** | ~8 MB (download) | ~13 MB |
| **RAM idle** | ~106 MB | ~56 MB |
| **Platform** | Android 7+ (arm64/v7) | Windows, Linux, macOS |

---

## Download

### Android APK

| File | Untuk siapa |
|---|---|
| [JPS-Tunnel-v8.apk](https://github.com/jhopan/JPS-Tunnel/releases/latest) | **Rekomendasi** — HP modern 2017+, arm64-v8a |
| [JPS-Tunnel-v7.apk](https://github.com/jhopan/JPS-Tunnel/releases/latest) | HP lama, armeabi-v7a 32-bit |
| [JPS-Tunnel-universal.apk](https://github.com/jhopan/JPS-Tunnel/releases/latest) | Semua HP |

### Desktop

| Platform | File |
|---|---|
| Windows x64 | `JPS-Tunnel-Desktop-windows-amd64.exe` |
| Linux x64 | `JPS-Tunnel-Desktop-linux-amd64` |
| macOS Intel | `JPS-Tunnel-Desktop-macos-intel` |
| macOS Apple Silicon | `JPS-Tunnel-Desktop-macos-apple` |

→ [Lihat semua release](https://github.com/jhopan/JPS-Tunnel/releases)

> **Catatan Desktop:** Core sing-box terpisah di release `core-v*` — letakkan di folder yang sama dengan binary desktop.

---

## Fitur

### Koneksi
- VLESS + WebSocket + TLS · SNI, Host, Path dihormati persis dari URI
- DNS: Cloudflare 1.1.1.1 (primary) + Google 8.8.8.8 (backup), lewat tunnel
- TUN gVisor — stabil lintas vendor (Qualcomm, MediaTek, Exynos)
- Status jujur: **Connecting → Checking → Connected**, hanya setelah HTTP 204 nyata lewat tunnel

### Profil
- Multi-profil — import dari clipboard (`vless://`) atau file `.jps`
- Kartu profil: tap untuk aktifkan, tombol Edit & Hapus per kartu
- Profil aktif ditandai border hijau

### Kestabilan 24/7
- Foreground service `START_STICKY` + auto-reconnect saat jaringan berubah
- Health check adaptif: 90 detik stabil / 30 detik recovery / 2 detik setelah event
- Reconnect otomatis maksimal 3x — tidak ada loop boros baterai
- Tetap berjalan saat app di-swipe dari Recents

### Lisensi Offline (HWID)
- Export profil terkunci (`.jps`) — terenkripsi AES-256-GCM berbasis HWID perangkat
- HWID salah atau expired → ditolak otomatis
- Import URI `vless://` milik sendiri → lisensi dilepas otomatis
- Tidak ada server, tidak ada revocation remote

### Efisiensi
- APK v8 hanya **~8 MB** — ABI split + kompresi DEFLATE pada `.so`
- Log level `warn` — tidak ada file log yang menumpuk
- Traffic meter per sesi, reset tiap connect
- Tidak ada wake lock

---

## Build

### Android

```bash
# Syarat: JDK 17, Android SDK API 35
./gradlew assembleRelease     # → app/build/outputs/apk/release/
./gradlew assembleDebug
```

### Desktop

```bash
cd desktop
go fmt ./... && go vet ./... && go test ./...
go build -ldflags="-s -w -H windowsgui" -o bin/JPS-Tunnel-Desktop.exe .

# Installer Windows (butuh Inno Setup 6)
"C:/Program Files (x86)/Inno Setup 6/ISCC.exe" setup.iss
```

### Core (sing-box)

```bash
# Build AAR Android lokal
bash build_libbox.sh

# Atau trigger CI: push tag core-v*
git tag core-v1.x.x && git push origin core-v1.x.x
```

> **Penting:** Build tags core wajib `with_gvisor,with_clash_api` — jangan dihapus.

---

## CI / Release

| Tag | Workflow | Hasil |
|---|---|---|
| `v*` | `build.yml` | APK Android (v7/v8/universal) |
| `desktop-v*` | `desktop-release.yml` | Binary desktop 4 platform |
| `core-v*` | `core.yml` | Core sing-box multi-platform + AAR |

---

## Arsitektur Singkat

```
vless:// URI
    ↓ parse (VlessParser.java)
    ↓ generate config (SingboxConfig.java)
    ↓ sing-box child process (VpnService.java)
    ↓ TUN gVisor ← semua traffic sistem
    ↓ VLESS WS TLS → server
```

---

## Catatan TUN Stack

gVisor dipilih karena stabil lintas vendor. Stack `system` lebih ringan (~66 MB vs ~106 MB PSS) tapi pernah tidak stabil di beberapa chipset MediaTek. Untuk menguji: ubah `"stack"` di `SingboxConfig.java`, uji minimal di 2 perangkat berbeda sebelum dipakai produksi.

---

<div align="center">

MIT License · © 2026 JhopanStore

</div>
