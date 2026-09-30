#!/usr/bin/env bash
# Stable compatible ARMv7 + ARM64 libbox for VpnService: sing-box v1.11.0.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="${SINGBOX_VERSION:-v1.11.0}"
WORK="$(mktemp -d "$ROOT/.libbox-build.XXXXXX")"
OUTPUT="$ROOT/app/libs/libbox.aar"
CANDIDATE="$WORK/libbox.aar"
trap 'rm -rf "$WORK"' EXIT

command -v go >/dev/null || { echo 'Go is required.' >&2; exit 1; }
command -v git >/dev/null || { echo 'Git is required.' >&2; exit 1; }
command -v jar >/dev/null || { echo 'JDK jar command is required.' >&2; exit 1; }
export PATH="$(go env GOPATH)/bin:$PATH"

go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

git clone --depth 1 --branch "$VERSION" https://github.com/SagerNet/sing-box.git "$WORK/sing-box"
cd "$WORK/sing-box"

# gVisor supports Android TUN; Clash API is required internally by v1.11 libbox service startup. QUIC, uTLS, and OneRing are excluded.
gomobile bind \
  -target=android/arm,android/arm64 \
  -androidapi=24 \
  -javapkg=io.github.sagernet.libbox \
  -tags="with_gvisor,with_clash_api" \
  -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=$VERSION -extldflags=-Wl,-z,max-page-size=16384" \
  ./experimental/libbox

mv libbox.aar "$CANDIDATE"
mapfile -t native < <(jar tf "$CANDIDATE" | grep '^jni/.*/libgojni\.so$')
expected=$'jni/arm64-v8a/libgojni.so\njni/armeabi-v7a/libgojni.so'
if [ "$(printf '%s\n' "${native[@]}" | sort)" != "$expected" ]; then
  printf 'Unexpected native libraries:\n%s\n' "${native[*]:-none}" >&2
  exit 1
fi
mkdir -p "$(dirname "$OUTPUT")"
mv "$CANDIDATE" "$OUTPUT"
echo "Built stable $OUTPUT from sing-box $VERSION"
