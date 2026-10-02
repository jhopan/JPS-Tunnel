## JPS Tunnel Android __VER__

Aplikasi VPN Android berbasis sing-box (gVisor TUN stack), protokol VLESS WebSocket TLS.
Ringan, stabil 24/7, hemat baterai, pemulihan otomatis.

### Download APK

| File | Untuk siapa |
|---|---|
| `JPS-Tunnel-__VER__-v8.apk` | **Rekomendasi** — HP modern 2017+, arm64-v8a |
| `JPS-Tunnel-__VER__-v7.apk` | HP lama, armeabi-v7a 32-bit |
| `JPS-Tunnel-__VER__-universal.apk` | Semua HP, ukuran lebih besar |

Tidak bisa install? Aktifkan _Install dari sumber tidak dikenal_ di Pengaturan > Keamanan.

### Kenapa APK sekecil ini?

APK v8 hanya ~8 MB padahal core sing-box mentah berukuran ~23 MB. Rahasianya:

**ABI split** — CI membangun tiga APK terpisah (v7/v8/universal). Setiap APK hanya membawa `.so` untuk satu arsitektur, bukan ketiganya sekaligus seperti APK biasa.

**Kompresi DEFLATE pada `.so`** — `useLegacyPackaging true` di `app/build.gradle` membuat `libgojni.so` dikemas DEFLATE di dalam APK (bukan STORED/raw). Library 23 MB terkompresi menjadi ~8 MB di dalam APK.

**Trade-off** — Ukuran storage setelah install tetap ~23 MB karena Android mengekstrak `.so` ke disk saat install. Yang hemat adalah ukuran download, bukan storage. Pendekatan ini sama dengan yang dipakai aplikasi VPN populer lainnya.

### Fitur

- Multi-profil VLESS WS TLS, import dari clipboard atau file `.jps`
- HTTP ping live setiap 3 detik + traffic meter upload/download
- Lisensi offline berbasis HWID (AES-GCM)
- Error diagnostik 3 baris: GAGAL / Penyebab / Solusi
- TUN stack gVisor, stabil lintas vendor tanpa root tambahan
