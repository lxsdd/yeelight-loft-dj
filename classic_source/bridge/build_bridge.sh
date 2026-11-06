#!/usr/bin/env bash
set -euo pipefail
# PRIVATE source-only bridge. No network, installation, signing, devices or account actions.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to installed Android SDK}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-35/android.jar}"
D8="${D8:-$ANDROID_HOME/build-tools/35.0.0/d8}"
[ -r "$ANDROID_JAR" ] || { echo "Missing Android SDK android.jar" >&2; exit 2; }
[ -x "$D8" ] || { echo "Missing Android SDK D8" >&2; exit 2; }
command -v javac >/dev/null || { echo "Missing javac" >&2; exit 2; }
command -v kotlinc >/dev/null || { echo "Missing kotlinc" >&2; exit 2; }
OUT="${1:?Pass a NEW output directory}"
[ ! -e "$OUT" ] || { echo "Output already exists" >&2; exit 2; }
mkdir -p "$OUT/classes" "$OUT/dex"
find "$ROOT/bridge/src" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -classpath "$ANDROID_JAR" -d "$OUT/classes"
# Reuse the tested original Kotlin DSP/PCM/coordinator files, no independent engine.
# Kotlin runtime is NOT repackaged: OEM Kotlin classes require compatibility audit.
kotlinc -jvm-target 1.8 -classpath "$ANDROID_JAR:$OUT/classes" \
  "$ROOT"/checkpoint/src/*.kt \
  "$ROOT"/bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt \
  -d "$OUT/classes"
(cd "$OUT/classes" && jar cf "$OUT/bridge.jar" .)
"$D8" --min-api 23 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/bridge.jar"
[ -s "$OUT/dex/classes.dex" ] || { echo "No sidecar DEX generated" >&2; exit 3; }
[ "$(find "$OUT/dex" -maxdepth 1 -name 'classes*.dex' | wc -l)" = 1 ] || { echo "Unexpected multi-DEX sidecar" >&2; exit 3; }
sha256sum "$OUT/dex/classes.dex"
