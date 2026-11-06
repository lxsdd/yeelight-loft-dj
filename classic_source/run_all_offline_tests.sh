#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${CLASSIC_ORIGINAL_APK:?Set CLASSIC_ORIGINAL_APK to YOUR read-only original com.yeelight.cherry_3.5.4.apk}"
command -v java >/dev/null
command -v javac >/dev/null
command -v kotlinc >/dev/null
command -v python3 >/dev/null
export CLASSIC_ORIGINAL_APK
python3 "$ROOT/classic_first.py" inspect --original "$CLASSIC_ORIGINAL_APK"
python3 "$ROOT/apk_readonly_audit.py" --original "$CLASSIC_ORIGINAL_APK" > /dev/null
(cd "$ROOT" && python3 -m unittest discover -s tests -q)
(cd "$ROOT/checkpoint" && python3 -m unittest discover -s tests -q)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src/android/content" "$WORK/classes"
cat > "$WORK/src/android/content/Context.java" <<'JAVA'
package android.content;
public class Context {}
JAVA
javac -source 8 -target 8 -d "$WORK/classes" \
 "$WORK/src/android/content/Context.java" \
 "$ROOT/bridge/src/io/github/lxsdd/yeelightclassicdj/runtime/DjRuntime.java" >/dev/null
kotlinc -jvm-target 1.8 -cp "$WORK/classes" \
 "$ROOT"/checkpoint/src/*.kt \
 "$ROOT"/checkpoint/tests/*.kt \
 "$ROOT"/checkpoint/tests/android-stubs/android/media/AudioRecord.kt \
 "$ROOT"/checkpoint/tests/android-stubs/android/os/SystemClock.kt \
 "$ROOT/bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt" \
 -include-runtime -d "$WORK/classic-regressions.jar"
for test in CoordinatorTest BeatAnalyzerTest DjEngineTest PcmBandsTest CaptureSessionTest AndroidAudioRecordInputTest YeelightReverseMusicTransportTest; do
  java -cp "$WORK/classic-regressions.jar:$WORK/classes" "loftdj.classic.${test}Kt"
done
java -cp "$WORK/classic-regressions.jar:$WORK/classes" \
  io.github.lxsdd.yeelightclassicdj.runtime.CheckpointRuntimeTestKt
printf 'SUCCESS: Python integration/toolchain + checkpoint regression suites, 8 Kotlin/JVM test mains, original APK statically audited.\n'
printf 'NO Android SDK/DEX/apktool, hardware, cloud, account, signing or installation acceptance from this test.\n'
