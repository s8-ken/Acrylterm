#!/bin/bash
# Builds the app and runs unit tests on the Mac, writing logs to .verify/ (git-ignored).
cd "$(dirname "$0")/.." || exit 1
OUT=.verify
mkdir -p "$OUT"
LOG="$OUT/build.log"
{
  echo "=== verify started $(date) ==="
  # Toolchains: prefer Android Studio's bundled JDK, then any installed JDK 17+.
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$(/usr/libexec/java_home -v 17+ 2>/dev/null)"; do
    if [ -x "$j/bin/java" ]; then export JAVA_HOME="$j"; break; fi
  done
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  echo "JAVA_HOME=$JAVA_HOME"; "$JAVA_HOME/bin/java" -version 2>&1
  echo "ANDROID_HOME=$ANDROID_HOME"; ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools" 2>&1
  ls "$ANDROID_HOME/ndk" 2>&1
  which go adb 2>&1; go version 2>&1
  "$ANDROID_HOME/platform-tools/adb" devices -l 2>&1
  [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew --no-daemon --console=plain :app:assembleDebug 2>&1
  echo "=== gradle exit: $? ==="
} > "$LOG" 2>&1
echo done > "$OUT/status"
