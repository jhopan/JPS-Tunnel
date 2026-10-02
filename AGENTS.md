# JPS Tunnel — Project Context

Android + Desktop VPN client berbasis sing-box (gVisor TUN stack), protokol VLESS WebSocket TLS.
Package Android: `com.jps.tunnel`. Repo: `jhopan/JPS-Tunnel`.

## Layout

```
app/                        Android (Java/XML)
  src/main/java/com/jps/tunnel/
    MainActivity.java       UI utama, profil, connect/disconnect
    VpnService.java         VpnService + sing-box child process
    SplashActivity.java     Splash 1.5s, logo JhopanStore
    ProfileEditActivity.java Form edit profil
    core/
      SingboxConfig.java    Generate JSON config sing-box
      VlessParser.java      Parse URI vless://
      ProfileStore.java     Simpan/load profil (SharedPreferences)
      License.java          Verifikasi lisensi offline HWID AES-GCM
      LicenseCodec.java     SALT: jps-tunnel-license-v1
  libs/libbox.aar           Core sing-box (gomobile, v7+v8 universal)
desktop/                    Desktop (Go + Gio UI)
  main.go                   UI utama, profil, connect/disconnect
  setup_linux.go            One-time setcap via pkexec (Linux)
  setup_darwin.go           One-time chown+setuid via osascript (macOS)
  setup_windows.go          No-op (UAC via manifest)
  import_windows.go         Clipboard (user32) + file picker (comdlg32)
  import_other.go           Stub non-Windows
  core_linux.go / core_darwin.go / core_windows.go  coreName per platform
.github/workflows/
  build.yml                 CI Android → release tag v*
  core.yml                  CI core multi-platform → tag core-v*
  desktop-release.yml       CI desktop 4 platform → tag desktop-v*
  release-notes-android.md  Template deskripsi release Android
build_libbox.sh             Build AAR lokal (gomobile)
jps-tunnel.jks              Keystore rilis (TIDAK di git, di .gitignore)
```

## Dev Environment

- Android: JDK 17, AGP 8.6.1, Gradle wrapper (`./gradlew.bat`)
- Desktop: Go 1.26+, Gio v0.10.3
- Core: Go + gomobile, sing-box v1.11.15
- Inno Setup 6 untuk installer Windows desktop

## Build & Test

```bash
# Android
./gradlew.bat :app:assembleRelease        # build APK split (v7/v8/universal)
./gradlew.bat :app:assembleDebug          # debug APK

# Desktop (dari folder desktop/)
go fmt ./... && go vet ./... && go test -count=1 ./...
go build -ldflags="-s -w -H windowsgui" -o bin/JPS-Tunnel-Desktop.exe .

# Installer desktop Windows
"C:/Program Files (x86)/Inno Setup 6/ISCC.exe" setup.iss
```

## Release

```bash
# Android APK
git tag v1.x.x && git push origin v1.x.x   # trigger build.yml

# Desktop
git tag desktop-v1.x.x && git push origin desktop-v1.x.x  # trigger desktop-release.yml

# Core sing-box
git tag core-v1.x.x && git push origin core-v1.x.x        # trigger core.yml
```

## Konvensi

- Package: `com.jps.tunnel` — jangan kembalikan ke jhopanstore.litevpn
- Ekstensi profil: `.jps` (bukan .jvs)
- MIME: `application/x-jps-tunnel`
- Satu keystore: `jps-tunnel.jks`, alias `jps-tunnel` — jangan buat keystore kedua
- Protokol: VLESS WS TLS saja — `type=ws`, `security=tls`
- TUN stack: `gvisor` — jangan ganti ke system
- `allowInsecure` default `true`
- Proxy loopback: `127.0.0.1:10808`
- HTTP ping ke `http://connectivitycheck.gstatic.com/generate_204`
- Commit: conventional commits, author jhopan
- Rilis CI: push tag → GitHub Actions yang build; JANGAN build lokal lalu upload manual

## Pitfalls

- **JANGAN hapus `with_clash_api` dari build tags core** — pernah menyebabkan error saat uji coba, harus selalu ada. Build tags wajib: `with_gvisor,with_clash_api`
- `jps-tunnel.jks` ada di `.gitignore` — jangan di-commit, simpan backup terpisah
- `*.png` di-ignore kecuali `!app/src/main/res/drawable-nodpi/logo_jhopanstore.png` dan `!desktop/assets/` — logo wajib force-add jika perlu track
- `desktop/bin/`, `desktop/output/`, `desktop/*.syso` di-ignore — jangan commit artifact build
- `useLegacyPackaging true` di `app/build.gradle` — wajib untuk kompresi DEFLATE `.so`; jangan hapus
- Setup flag Linux/macOS: `~/.config/JPSTunnel/.setup_done` — hapus file ini untuk force re-setup privilege
- Core dicari di folder yang sama dengan EXE desktop — pastikan letakkan bersama saat distribusi
- sing-box v1.11.15 — jangan upgrade sembarangan, versi ini stabil dengan gomobile build kita
- `auto_detect_interface: true` + `strict_route: false` di Windows config — jangan ubah ke true, menyebabkan WFP over-block
