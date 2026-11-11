#!/usr/bin/env bash
# Protected owner-only disposable emulator. Never run in public PR or push CI.
# The SHA-pinned, OEM-signed stock original is never modified or re-signed.
set -euo pipefail
set +x
umask 077
fail() {
  # The private runner emits only these fixed, source-reviewed stage names.
  case "${1:-}" in
    TOOLCHAIN_UNAVAILABLE|SDK_PACKAGE_INSTALL_FAILED|SDK_PACKAGE_CATALOG_FAILED|\
SDK_IMAGE_NOT_LISTED|SDK_LICENSE_NOT_PREAUTHORIZED|SIGNATURE_TOOL_UNAVAILABLE|\
ORIGINAL_PIN_UNVERIFIED|STOCK_SIGNATURE_INVALID|AVD_SETUP_FAILED|\
EMULATOR_BOOT_FAILED|EMULATOR_ROOT_FAILED|NETWORK_ISOLATION_FAILED|\
OEM_BADGING_FAILED|LAUNCHER_DISCOVERY_FAILED|OEM_INSTALL_FAILED|\
STOCK_STARTUP_UNVERIFIED) code="$1" ;;
    *) code="GENERAL" ;;
  esac
  printf '%s\n' "STOCK_ORIGINAL_ANDROID17_EMULATOR_BLOCKED_${code}" >&2
  exit 2
}
[[ "${GITHUB_REPOSITORY:-}" == 'lxsdd/yeelight-loft-dj' ]] || fail
[[ "${GITHUB_EVENT_NAME:-}" == 'workflow_dispatch' && "${GITHUB_ACTOR:-}" == 'lxsdd' ]] || fail
[[ "${GITHUB_REF:-}" == 'refs/heads/main' && "${RUNNER_ENVIRONMENT:-}" == 'github-hosted' ]] || fail
[[ -z "${GH_TOKEN:-}" && -z "${CLASSIC_VAULT_TOKEN:-}" ]] || fail
[[ -n "${RUNNER_TEMP:-}" && -n "${ANDROID_HOME:-}" ]] || fail
[[ "$#" -eq 1 && -f "$1" && ! -L "$1" ]] || fail
APK="$(realpath -e -- "$1")"
[[ "$APK" == "$RUNNER_TEMP/classic-original/com.yeelight.cherry_3.5.4.apk" ]] || fail
[[ -s "$APK" ]] || fail
for tool in sdkmanager avdmanager adb python3; do command -v "$tool" >/dev/null || fail TOOLCHAIN_UNAVAILABLE; done
ROOT="$RUNNER_TEMP/classic-stock-emulator"
[[ ! -e "$ROOT" ]] || fail
mkdir -m 700 "$ROOT"
export ANDROID_AVD_HOME="$ROOT/avd"
mkdir -m 700 "$ANDROID_AVD_HOME"
EMULATOR_PID=''
cleanup() {
  set +e
  if [[ -n "$EMULATOR_PID" ]]; then
    adb -s emulator-5554 emu kill >/dev/null 2>&1
    kill "$EMULATOR_PID" >/dev/null 2>&1
  fi
  rm -rf -- "$ROOT"
}
trap cleanup EXIT
# Read the stable SDK package catalog first. The obsolete unversioned
# "android-37;google_apis" image was NOT downloadable (run 43021597830).
# Never select unknown/preview/PlayStore/non-root images or alter licensing.
sdkmanager --list --channel=0 >"$ROOT/sdk-catalog.log" 2>&1 || fail SDK_PACKAGE_CATALOG_FAILED
SYSTEM_IMAGE="$(python3 classic_source/private_ci/select_android17_image.py \
  "$ROOT/sdk-catalog.log")" || fail SDK_IMAGE_NOT_LISTED
[[ "$SYSTEM_IMAGE" == 'system-images;android-37.2;google_apis_ps16k;x86_64' ||
   "$SYSTEM_IMAGE" == 'system-images;android-37.1;google_apis_ps16k;x86_64' ]] || fail SDK_IMAGE_NOT_LISTED

# This workflow never accepts new SDK contracts on the owner's behalf.
# A missing preaccepted license fails closed; no interactive 'yes' or retry.
if sdkmanager --install 'emulator' "$SYSTEM_IMAGE" 'build-tools;36.0.0' \
  --channel=0 </dev/null >"$ROOT/sdk.log" 2>&1; then
  :
else
  if grep -Eiq 'licenses? (have )?not been accepted|licenses? not accepted|not accepted.*licenses?' "$ROOT/sdk.log"; then
    fail SDK_LICENSE_NOT_PREAUTHORIZED
  fi
  fail SDK_PACKAGE_INSTALL_FAILED
fi
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
[[ -x "$AAPT" && -x "$APKSIGNER" ]] || fail SIGNATURE_TOOL_UNAVAILABLE
# Original pin and existing OEM signature are mandatory. Never sign any APK.
python3 - "$APK" >"$ROOT/original-pin.log" 2>&1 <<'PY' || fail ORIGINAL_PIN_UNVERIFIED
from pathlib import Path
import sys
sys.path.insert(0, 'classic_source')
from classic_first import inspect_original
inspect_original(Path(sys.argv[1]))
PY
"$APKSIGNER" verify "$APK" >"$ROOT/signature.log" 2>&1 || fail STOCK_SIGNATURE_INVALID
printf 'no\n' | avdmanager create avd --force -n classic-stock-offline-37 \
  -k "$SYSTEM_IMAGE" >"$ROOT/avd.log" 2>&1 || fail AVD_SETUP_FAILED
"$ANDROID_HOME/emulator/emulator" -avd classic-stock-offline-37 -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -memory 4096 -gpu swiftshader_indirect >"$ROOT/emulator.log" 2>&1 &
EMULATOR_PID=$!
READY=0
for _ in $(seq 1 150); do
  if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    READY=1; break
  fi
  kill -0 "$EMULATOR_PID" 2>/dev/null || fail EMULATOR_BOOT_FAILED
  sleep 2
done
[[ "$READY" == 1 ]] || fail EMULATOR_BOOT_FAILED
adb -s emulator-5554 root >"$ROOT/root.log" 2>&1 || fail EMULATOR_ROOT_FAILED
adb -s emulator-5554 wait-for-device >/dev/null 2>&1 || fail EMULATOR_ROOT_FAILED
[[ "$(adb -s emulator-5554 shell id -u | tr -d '\r')" == 0 ]] || fail EMULATOR_ROOT_FAILED
# Isolate all guest routes BEFORE the stock app is installed or launched.
adb -s emulator-5554 shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell settings put global wifi_on 0 >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell settings put global mobile_data 0 >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell svc wifi disable >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell svc data disable >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell ip -4 route flush table main >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
adb -s emulator-5554 shell ip -6 route flush table main >/dev/null 2>&1 || fail NETWORK_ISOLATION_FAILED
# AAPT data remain only in restricted ephemeral files; no OEM names leave logs.
"$AAPT" dump badging "$APK" >"$ROOT/badging.txt" 2>"$ROOT/aapt.log" || fail OEM_BADGING_FAILED
LAUNCHER="$(python3 - "$ROOT/badging.txt" <<'PY'
import re
import sys
from pathlib import Path
s=Path(sys.argv[1]).read_text('utf-8')
package=re.findall(r"(?m)^package: name='([^']+)'", s)
activity=re.findall(r"(?m)^launchable-activity: name='([^']+)'", s)
if package != ["com.yeelight.cherry"] or len(activity) != 1:
    sys.exit(2)
if re.fullmatch(r"com\.yeelight\.[A-Za-z0-9_.$]+", activity[0]) is None:
    sys.exit(2)
print(activity[0])
PY
)" || fail LAUNCHER_DISCOVERY_FAILED
# Install unchanged OEM-signed stock archive into disposable guest ONLY.
adb -s emulator-5554 install -r --no-streaming "$APK" >"$ROOT/install.log" 2>&1 || fail OEM_INSTALL_FAILED
PIN="$(sha256sum "$APK" | cut -d' ' -f1)"
[[ "$PIN" =~ ^[a-f0-9]{64}$ ]] || fail ORIGINAL_PIN_UNVERIFIED
python3 classic_source/private_ci/stock_original_emulator_probe.py \
  --serial emulator-5554 \
  --launcher "$LAUNCHER" \
  --pinned-sha256 "$PIN" \
  --explicit-owner-approved-private-emulator || fail STOCK_STARTUP_UNVERIFIED
printf '%s\n' STOCK_ORIGINAL_ANDROID17_READONLY_BASELINE_OBSERVED
