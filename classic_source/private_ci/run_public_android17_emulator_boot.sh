#!/usr/bin/env bash
# Public, owner-PR-only Android17 emulator BOOT qualification with NO OEM bytes.
# No release, APK install, real devices, credentials, account, cloud login or
# license acceptance. Host SDK/system image are disposable runner inputs.
set -euo pipefail
set +x
umask 077

fail() {
  case "${1:-}" in
    INVALID_CONTEXT|HOST_SDK_UNAVAILABLE|HOST_KVM_DEVICE_MISSING|\
    HOST_KVM_ACCESS_UNAVAILABLE|SDK_CATALOG_FAILED|SDK_IMAGE_UNLISTED|SDK_LICENSE_NOT_PREAUTHORIZED|\
    SDK_INSTALL_FAILED|AVD_CREATION_FAILED|EMULATOR_EXEC_UNAVAILABLE|\
    EMULATOR_ACCELERATION_FAILED|EMULATOR_BOOT_FAILED|ACTIVITY_SNAPSHOT_FAILED|ACTIVITY_RESUME_UNVERIFIED) code="$1";;
    *) code="INTERNAL_ERROR";;
  esac
  printf 'ANDROID17_PUBLIC_EMULATOR_BLOCKED_%s\n' "$code" >&2
  exit 2
}
[[ "$#" -eq 1 && "$1" == "--no-oem" ]] || fail INVALID_CONTEXT
[[ "${GITHUB_REPOSITORY:-}" == "lxsdd/yeelight-loft-dj" &&
   "${GITHUB_ACTOR:-}" == "lxsdd" &&
   "${GITHUB_EVENT_NAME:-}" == "pull_request" &&
   "${RUNNER_ENVIRONMENT:-}" == "github-hosted" ]] || fail INVALID_CONTEXT
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" &&
   -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || fail HOST_SDK_UNAVAILABLE
# This one hosted job alone receives scoped KVM access, restored in EXIT.
# No personal hardware, LAN, account, OEM data or physical devices touched.
source classic_source/private_ci/hosted_kvm_scope.sh
CLI="$ANDROID_HOME/cmdline-tools/latest/bin"
PLAT="$ANDROID_HOME/platform-tools"
[[ -x "$CLI/sdkmanager" && -x "$CLI/avdmanager" && -x "$PLAT/adb" ]] ||
  fail HOST_SDK_UNAVAILABLE
export PATH="$CLI:$PLAT:$PATH"
ROOT="$(mktemp -d "$RUNNER_TEMP/classic-public-emulator.XXXXXXXX")" || fail HOST_SDK_UNAVAILABLE
export ANDROID_AVD_HOME="$ROOT/avd"
mkdir -m 700 "$ANDROID_AVD_HOME"
EMULATOR_PID=''
cleanup() {
  set +e
  if [[ -n "$EMULATOR_PID" ]]; then
    "$PLAT/adb" -s emulator-5554 emu kill >/dev/null 2>&1
    kill "$EMULATOR_PID" >/dev/null 2>&1
  fi
  kvm_scope_restore
  rm -rf -- "$ROOT"
}
trap cleanup EXIT
if ! kvm_scope_acquire; then
  [[ "$KVM_SCOPE_REASON" == "KVM_DEVICE_MISSING" ]] && fail HOST_KVM_DEVICE_MISSING
  fail HOST_KVM_ACCESS_UNAVAILABLE
fi

"$CLI/sdkmanager" --list --channel=0 </dev/null >"$ROOT/catalog.log" 2>&1 ||
  fail SDK_CATALOG_FAILED
IMAGE="$(python3 classic_source/private_ci/select_android17_image.py "$ROOT/catalog.log")" ||
  fail SDK_IMAGE_UNLISTED
[[ "$IMAGE" == "system-images;android-37.2;google_apis_ps16k;x86_64" ||
   "$IMAGE" == "system-images;android-37.1;google_apis_ps16k;x86_64" ]] ||
  fail SDK_IMAGE_UNLISTED
# SDK installer reads from /dev/null; automatic license acceptance disabled.
if "$CLI/sdkmanager" --install --channel=0 "emulator" "$IMAGE" \
  </dev/null >"$ROOT/sdk-install.log" 2>&1; then
  :
else
  if grep -Eiq 'licens.{0,70}not.{0,20}accept|not.{0,20}accept.{0,70}licens' "$ROOT/sdk-install.log"; then
    fail SDK_LICENSE_NOT_PREAUTHORIZED
  fi
  fail SDK_INSTALL_FAILED
fi
EMU="$ANDROID_HOME/emulator/emulator"
[[ -x "$EMU" ]] || fail EMULATOR_EXEC_UNAVAILABLE
"$EMU" -accel-check >"$ROOT/accel.log" 2>&1 || fail EMULATOR_ACCELERATION_FAILED
printf 'no\n' | "$CLI/avdmanager" create avd --force \
  -n classic-public-android17-boot -k "$IMAGE" \
  >"$ROOT/avd.log" 2>&1 || fail AVD_CREATION_FAILED

boot_failed() {
  # Only source-audited enum; do not expose raw log or paths.
  python3 classic_source/private_ci/emulator_boot_diagnosis.py "$ROOT/emulator.log" ||
    true
  fail EMULATOR_BOOT_FAILED
}
# One experimental GPU/graphics configuration, not opportunistic retries.
# Chromium's Android17 PS16K x86_64 AVD defines swangle_indirect.
"$EMU" -avd classic-public-android17-boot -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -memory 4096 -accel on -gpu swangle_indirect -feature -Vulkan \
  >"$ROOT/emulator.log" 2>&1 &
EMULATOR_PID=$!
READY=0
for _ in $(seq 1 180); do
  if [[ "$("$PLAT/adb" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    READY=1
    break
  fi
  kill -0 "$EMULATOR_PID" 2>/dev/null || boot_failed
  sleep 2
done
[[ "$READY" == "1" ]] || boot_failed
[[ "$("$PLAT/adb" -s emulator-5554 shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" == "1" ]] ||
  boot_failed
[[ "$("$PLAT/adb" -s emulator-5554 shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" == "37" ]] ||
  boot_failed
# Exercise the real Android17 *global* resumed-record grammar with NO OEM.
# Dump remains in a private runner temp file; only an enum reaches CI output.
"$PLAT/adb" -s emulator-5554 shell dumpsys activity activities \
  >"$ROOT/activity-snapshot.txt" 2>&1 || fail ACTIVITY_SNAPSHOT_FAILED
python3 - "$ROOT/activity-snapshot.txt" <<'PY' || fail ACTIVITY_RESUME_UNVERIFIED
from pathlib import Path
import sys
sys.path.insert(0, "classic_source/private_ci")
from stock_activity_evidence import inspect_top_resumed
try:
    snapshot = Path(sys.argv[1]).read_text("utf-8")
    state = inspect_top_resumed(snapshot, "com.yeelight.cherry.SyntheticOnly")
    if state["category"] != "FOREGROUND_OTHER_PACKAGE":
        raise ValueError("fail closed")
except Exception:
    sys.exit(2)
PY
printf '%s\n' ANDROID17_PUBLIC_GLOBAL_RESUME_GRAMMAR_PROVEN_NO_OEM

printf '%s\n' ANDROID17_PUBLIC_EMULATOR_COLD_BOOT_PROVEN_NO_OEM
