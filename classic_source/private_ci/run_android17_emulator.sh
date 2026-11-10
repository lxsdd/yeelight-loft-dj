#!/usr/bin/env bash
# Owner-approved PRIVATE workflow only. Never call from public source CI.
# The source and private APK stay inside one ephemeral hosted runner.
set -euo pipefail
set +x
umask 077
fail() { echo "PRIVATE_ANDROID17_EMULATOR_BLOCKED" >&2; exit 2; }
[[ "${GITHUB_REPOSITORY:-}" == "lxsdd/yeelight-loft-dj" ]] || fail
[[ "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" && "${GITHUB_REF:-}" == "refs/heads/main" && "${GITHUB_ACTOR:-}" == "lxsdd" ]] || fail
[[ "${RUNNER_ENVIRONMENT:-}" == "github-hosted" ]] || fail
[[ -z "${GH_TOKEN:-}" && -z "${CLASSIC_VAULT_TOKEN:-}" ]] || fail
[[ -n "${ANDROID_HOME:-}" && -n "${RUNNER_TEMP:-}" ]] || fail
[[ "$#" -eq 1 && -f "$1" ]] || fail
APK="$(realpath -e -- "$1")"
[[ "$APK" == "$RUNNER_TEMP"/private-output/* && "$(basename -- "$APK")" == "classic-loft-dj-TEST-SIGNED.apk" ]] || fail
[[ -s "$APK" ]] || fail
command -v sdkmanager >/dev/null || fail
command -v avdmanager >/dev/null || fail
command -v adb >/dev/null || fail
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$AAPT" ]] || fail
WORK="$RUNNER_TEMP/classic-android17-emulator"
[[ ! -e "$WORK" ]] || fail
mkdir -m 700 "$WORK"
export ANDROID_AVD_HOME="$WORK/avd"
mkdir -m 700 "$ANDROID_AVD_HOME"
EMULATOR_PID=''
cleanup() {
  set +e
  if [[ -n "$EMULATOR_PID" ]]; then
    adb -s emulator-5554 emu kill >/dev/null 2>&1
    kill "$EMULATOR_PID" >/dev/null 2>&1
  fi
  rm -rf -- "$WORK"
}
trap cleanup EXIT
# SDK license agreements must already be accepted by the configured environment.
# No automatic license acceptance, third-party action, cache or APK upload.
sdkmanager --install 'emulator' 'system-images;android-37;google_apis;x86_64' >"$WORK/sdk.log" 2>&1 || fail
printf 'no\n' | avdmanager create avd --force -n classic-offline-api37 -k 'system-images;android-37;google_apis;x86_64' >"$WORK/avd.log" 2>&1 || fail
"$ANDROID_HOME/emulator/emulator" -avd classic-offline-api37 -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -memory 4096 -gpu swiftshader_indirect >"$WORK/emulator.log" 2>&1 &
EMULATOR_PID=$!
READY=0
for _ in $(seq 1 150); do
  if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then READY=1; break; fi
  kill -0 "$EMULATOR_PID" 2>/dev/null || fail
  sleep 2
done
[[ "$READY" == 1 ]] || fail
adb -s emulator-5554 root >"$WORK/root.log" 2>&1 || fail
adb -s emulator-5554 wait-for-device >/dev/null 2>&1 || fail
[[ "$(adb -s emulator-5554 shell id -u | tr -d '\r')" == 0 ]] || fail
# Before the original provider/application can execute, remove all device networking.
adb -s emulator-5554 shell settings put global airplane_mode_on 1 >/dev/null || fail
adb -s emulator-5554 shell settings put global wifi_on 0 >/dev/null || fail
adb -s emulator-5554 shell settings put global mobile_data 0 >/dev/null || fail
adb -s emulator-5554 shell svc wifi disable >/dev/null || fail
adb -s emulator-5554 shell svc data disable >/dev/null || fail
adb -s emulator-5554 shell cmd connectivity airplane-mode enable >/dev/null || fail
adb -s emulator-5554 shell ip -4 route flush table main >/dev/null || fail
adb -s emulator-5554 shell ip -6 route flush table main >/dev/null || fail
# Do not print badging: it contains private original component names.
BADGING="$WORK/badging.txt"
"$AAPT" dump badging "$APK" >"$BADGING" 2>"$WORK/aapt.log" || fail
LAUNCHER="$(python3 - "$BADGING" <<'PY'
import re,sys
s=open(sys.argv[1],encoding='utf-8').read()
a=re.findall(r"(?m)^launchable-activity: name='([^']+)'",s)
p=re.findall(r"(?m)^package: name='([^']+)'",s)
if p!=['io.lxsdd.yeelightdj'] or len(a)!=1 or not re.fullmatch(r'com\.yeelight\.[A-Za-z0-9_.$]+',a[0]):
    sys.exit(2)
print(a[0])
PY
)" || fail
PIN="$(sha256sum "$APK" | cut -d' ' -f1)"
[[ "$PIN" =~ ^[a-f0-9]{64}$ ]] || fail
# Installed APK is confined to the disposable emulator and verified AGAINST PIN
# using the on-device hash before launching any OEM code.
adb -s emulator-5554 install -r "$APK" >"$WORK/install.log" 2>&1 || fail
python3 classic_source/private_ci/android17_start_smoke.py \
 --serial emulator-5554 \
 --launcher-component "io.lxsdd.yeelightdj/$LAUNCHER" \
 --expected-installed-apk-sha256 "$PIN" \
 --explicit-owner-approved-private-emulator || fail
printf 'PRIVATE_ANDROID17_CLASSIC_NATIVE_UI_SMOKE_PASS\n'
