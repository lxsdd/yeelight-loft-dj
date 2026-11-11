#!/usr/bin/env bash
# Public and protected workflows use this SAME read-only hosted SDK catalog gate.
# No APKs, Google/Android license acceptance, package installation or devices.
set -euo pipefail
set +x
umask 077

fail() {
  case "${1:-}" in
    HOSTED_SDK_MISSING|HOSTED_SDK_CATALOG_FAILED|HOSTED_SDK_IMAGE_UNLISTED|HOSTED_SDK_INVALID_INVOCATION)
      printf 'ANDROID17_PUBLIC_CATALOG_%s\n' "$1" >&2 ;;
    *) printf 'ANDROID17_PUBLIC_CATALOG_BLOCKED\n' >&2 ;;
  esac
  exit 2
}

[[ "$#" -eq 1 && "$1" == '--catalog' ]] || fail HOSTED_SDK_INVALID_INVOCATION
[[ "${GITHUB_REPOSITORY:-}" == 'lxsdd/yeelight-loft-dj' ]] || fail HOSTED_SDK_INVALID_INVOCATION
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || fail HOSTED_SDK_MISSING
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || fail HOSTED_SDK_MISSING
CLI="$ANDROID_HOME/cmdline-tools/latest/bin"
PLATFORM="$ANDROID_HOME/platform-tools"
[[ -x "$CLI/sdkmanager" && -x "$CLI/avdmanager" && -x "$PLATFORM/adb" ]] || fail HOSTED_SDK_MISSING
[[ -f "$ANDROID_HOME/cmdline-tools/latest/source.properties" ]] || fail HOSTED_SDK_MISSING
export PATH="$CLI:$PLATFORM:$PATH"

PRIVATE="$(mktemp -d "$RUNNER_TEMP/classic-readonly-sdk.XXXXXXXX")" || fail HOSTED_SDK_MISSING
cleanup() { rm -rf -- "$PRIVATE"; }
trap cleanup EXIT

# A bounded, noninteractive *listing* from Google SDK catalog; never install.
timeout 150s "$CLI/sdkmanager" --list --channel=0 </dev/null >"$PRIVATE/catalog" 2>&1 ||
  fail HOSTED_SDK_CATALOG_FAILED
python3 classic_source/private_ci/select_android17_image.py "$PRIVATE/catalog" \
  >"$PRIVATE/selected-image" 2>&1 || fail HOSTED_SDK_IMAGE_UNLISTED
IMAGE="$(cat "$PRIVATE/selected-image")"
[[ "$IMAGE" == 'system-images;android-37.2;google_apis_ps16k;x86_64' ||
   "$IMAGE" == 'system-images;android-37.1;google_apis_ps16k;x86_64' ]] ||
  fail HOSTED_SDK_IMAGE_UNLISTED

# No system-image name, shell traces, SDK logs or OEM details go to CI logs.
printf '%s\n' ANDROID17_PUBLIC_STABLE_CATALOG_PASS
