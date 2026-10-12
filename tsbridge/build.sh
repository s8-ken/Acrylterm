#!/usr/bin/env bash
# Builds app/libs/tsbridge.aar (embedded Tailscale via tsnet) with gomobile.
#
# Requirements (macOS):
#   - Go (any recent version; GOTOOLCHAIN=auto fetches the one tailscale.com needs)
#   - Android SDK + NDK (Android Studio > SDK Manager > SDK Tools > "NDK (Side by side)")
#   - ANDROID_HOME (default: ~/Library/Android/sdk)
#
# Usage:  ./tsbridge/build.sh
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  export ANDROID_NDK_HOME
fi
if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK not found. Install it from Android Studio (SDK Manager > SDK Tools > NDK)." >&2
  exit 1
fi
export GOTOOLCHAIN=auto
export PATH="$(go env GOPATH)/bin:$PATH"

# Resolve dependencies on first run (pins whatever is latest at that time in go.sum).
if ! grep -q 'tailscale.com' go.mod; then
  go get tailscale.com@latest
fi
go get golang.org/x/mobile/bind@latest
go mod tidy

if ! command -v gomobile >/dev/null; then
  go install golang.org/x/mobile/cmd/gomobile@latest
  go install golang.org/x/mobile/cmd/gobind@latest
fi
gomobile init

mkdir -p ../app/libs
# arm64 only keeps the AAR small; add android/amd64 for the emulator if needed.
gomobile bind -target=android/arm64 -androidapi 26 -ldflags="-s -w" \
  -o ../app/libs/tsbridge.aar .
echo "Built app/libs/tsbridge.aar"
