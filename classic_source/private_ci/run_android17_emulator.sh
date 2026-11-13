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
# Print only allowlisted booleans; no private OEM classes, paths, or APK bytes.
python3 classic_source/secondary_dex_transport.py --owner-only-transport-status || fail
command -v sdkmanager >/dev/null || fail
command -v avdmanager >/dev/null || fail
command -v adb >/dev/null || fail
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$AAPT" ]] || fail
source classic_source/private_ci/hosted_kvm_scope.sh
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
  kvm_scope_restore
  rm -rf -- "$WORK"
}
trap cleanup EXIT
# Reuse the already-proven protected Android17 emulator KVM and SDK gates.
# No obsolete image, previews, PlayStore, new license consent, retries or fallback.
kvm_scope_acquire || fail
sdkmanager --list --channel=0 </dev/null >"$WORK/sdk-catalog.log" 2>&1 || fail
IMAGE="$(python3 classic_source/private_ci/select_android17_image.py "$WORK/sdk-catalog.log")" || fail
[[ "$IMAGE" == 'system-images;android-37.2;google_apis_ps16k;x86_64' ||
   "$IMAGE" == 'system-images;android-37.1;google_apis_ps16k;x86_64' ]] || fail
if sdkmanager --install 'emulator' "$IMAGE" --channel=0 \
  </dev/null >"$WORK/sdk.log" 2>&1; then
  :
else
  # Never automatically accept or interactively request new SDK licenses.
  fail
fi
"$ANDROID_HOME/emulator/emulator" -accel-check >"$WORK/accel.log" 2>&1 || fail
printf 'no\n' | avdmanager create avd --force -n classic-offline-api37 \
  -k "$IMAGE" >"$WORK/avd.log" 2>&1 || fail
"$ANDROID_HOME/emulator/emulator" -avd classic-offline-api37 -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -memory 4096 -accel on -gpu swangle_indirect -feature -Vulkan \
  >"$WORK/emulator.log" 2>&1 &
EMULATOR_PID=$!
boot_failed() {
  # Fixed non-OEM boot diagnosis only; no raw emulator log leaves runner.
  python3 classic_source/private_ci/emulator_boot_diagnosis.py \
    "$WORK/emulator.log" || true
  fail
}
READY=0
for _ in $(seq 1 150); do
  if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then READY=1; break; fi
  kill -0 "$EMULATOR_PID" 2>/dev/null || boot_failed
  sleep 2
done
[[ "$READY" == 1 ]] || boot_failed
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
# Do not rely on aapt launchable-activity; verified Classic itself needed the
# installed PackageManager's MAIN/LAUNCHER alias resolution.
python3 classic_source/private_ci/clone_launcher_selection.py --preinstall \
  --badging "$BADGING" || fail
PIN="$(sha256sum "$APK" | cut -d' ' -f1)"
[[ "$PIN" =~ ^[a-f0-9]{64}$ ]] || fail
# Installed APK is confined to the disposable emulator and verified AGAINST PIN
# using the on-device hash before launching any OEM code.
adb -s emulator-5554 install -r --no-streaming "$APK" >"$WORK/install.log" 2>&1 || fail
# The installed OS, not an aapt heuristic, selects the original Classic entry.
# Raw component strings remain in the ephemeral protected runner directory.
adb -s emulator-5554 shell cmd package query-activities --components --user 0 \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -p io.lxsdd.yeelightdj >"$WORK/query.log" 2>&1 || fail
adb -s emulator-5554 shell cmd package resolve-activity --components --user 0 \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -p io.lxsdd.yeelightdj >"$WORK/resolved.log" 2>&1 || fail
LAUNCHER="$(python3 classic_source/private_ci/clone_launcher_selection.py --installed \
  --badging "$BADGING" --query "$WORK/query.log" \
  --resolved "$WORK/resolved.log")" || fail
# Preserve the original single-launch diagnostic. A failed launch must never
# be turned into a PASS by the forensic helper. Inspect only the existing
# crash buffer after failure; never retry, re-launch, or write any physical device.
if python3 classic_source/private_ci/android17_start_smoke.py \
 --serial emulator-5554 \
 --launcher-component "io.lxsdd.yeelightdj/$LAUNCHER" \
 --expected-installed-apk-sha256 "$PIN" \
 --candidate-apk "$APK" \
 --explicit-owner-approved-private-emulator; then
  printf 'PRIVATE_ANDROID17_CLASSIC_NATIVE_UI_SMOKE_PASS\n'
else
  # Private APK contents and class descriptors stay on this disposable runner.
  # The one post-crash ART verify command occurs only inside its offline emulator.
  python3 classic_source/private_ci/classloader_linkage_forensics.py \
    --original "$RUNNER_TEMP/private-original/com.yeelight.cherry_3.5.4.apk" \
    --candidate "$APK" \
    --serial emulator-5554 || fail
  # Only AFTER the clone has actually failed: inspect its exact crash first,
  # uninstall it, then run one unmodified original on THIS offline emulator.
  # No original install/launch if the clone passed; never turn a crash to PASS.
  python3 classic_source/private_ci/private_original_baseline.py \
    --original "$RUNNER_TEMP/private-original/com.yeelight.cherry_3.5.4.apk" \
    --candidate "$APK" \
    --serial emulator-5554 \
    --explicit-owner-approved-private-emulator || fail
  # After the real clone FAIL and the unmodified stock comparison, run one
  # strictly private *identity-preserving* control with a real DJ bridge.
  # This is a diagnostic A/B bundle, not an OEM source export or acceptance.
  # Keep original package/authorities/OEM dex but the same disabled providers,
  # UI bridge, min-SDK and ephemeral signing. No extra run if clone passed.
  # The stock original is uninstalled by private_original_baseline above.
  IDU="$RUNNER_TEMP/private-output/classic-loft-dj-IDENTITY-UNSIGNED.apk"
  IDS="$RUNNER_TEMP/private-output/classic-loft-dj-IDENTITY-TEST-SIGNED.apk"
  export CLASSIC_APKTOOL_JAR="$RUNNER_TEMP/classic-tools/apktool_3.0.3.jar"
  python3 classic_source/build_private_unsigned.py \
    --original "$RUNNER_TEMP/private-original/com.yeelight.cherry_3.5.4.apk" \
    --android-sdk "$ANDROID_HOME" --output "$IDU" --identity-control \
    >"$WORK/identity-build.log" 2>&1 || fail
  python3 classic_source/private_test_sign.py \
    --unsigned "$IDU" --preflight "$IDU.preflight.json" \
    --output "$IDS" --android-sdk "$ANDROID_HOME" --identity-control \
    >"$WORK/identity-sign.log" 2>&1 || fail
  python3 classic_source/private_payload_parity.py \
    --unsigned "$IDU" --signed "$IDS" || fail
  python3 classic_source/private_ci/private_identity_control.py \
    --original "$RUNNER_TEMP/private-original/com.yeelight.cherry_3.5.4.apk" \
    --candidate "$IDS" --serial emulator-5554 \
    --explicit-owner-approved-private-emulator || fail
  # Package-only control: derive from the audited original-identity unsigned
  # APK by changing only the binary Manifest package string. OEM DEX,
  # authorities, signature permissions, layouts and bridge stay byte-identical.
  # Installed only AFTER the original-identity control was uninstalled.
  PKU="$RUNNER_TEMP/private-output/classic-loft-dj-PACKAGE-UNSIGNED.apk"
  PKS="$RUNNER_TEMP/private-output/classic-loft-dj-PACKAGE-TEST-SIGNED.apk"
  python3 classic_source/private_ci/package_only_manifest.py \
    --source "$IDU" --output "$PKU" || fail
  python3 classic_source/private_test_sign.py \
    --unsigned "$PKU" --preflight "$PKU.preflight.json" \
    --output "$PKS" --android-sdk "$ANDROID_HOME" --package-only-control \
    >"$WORK/package-only-sign.log" 2>&1 || fail
  python3 classic_source/private_payload_parity.py \
    --unsigned "$PKU" --signed "$PKS" || fail
  python3 classic_source/private_ci/private_package_only_control.py \
    --original "$RUNNER_TEMP/private-original/com.yeelight.cherry_3.5.4.apk" \
    --candidate "$PKS" --serial emulator-5554 \
    --explicit-owner-approved-private-emulator || fail
  # Never turn a crashing clone or any nonfatal control into product PASS.
  fail
fi
