#!/usr/bin/env bash
# PRIVATE build entrypoint; staged Java17/Android SDK/Gradle 9.8.2 upgrade.
# NEVER invoked in the public Loft-DJ repository or on a device.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${CLASSIC_ORIGINAL_APK:?Set to the PRIVATE, local owner-supplied original APK}"
: "${CLASSIC_OUTPUT_DIR:?Set to the PRIVATE directory for unsigned output}"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Android SDK path required; reuse Loft DJ CI setup-android SDK}"
: "${CLASSIC_APKTOOL_JAR:?Provide pinned Apktool 3.0.3 (SHA256 verified by builder)}"
# In a public workflow any private OEM input is an unacceptable disclosure risk.
if [[ "${GITHUB_REPOSITORY_VISIBILITY:-}" == 'public' ]]; then
    echo 'BLOCKED_PUBLIC_WORKFLOW: Classic input never enters public CI' >&2
    exit 2
fi
if [[ -n "${GITHUB_ACTIONS:-}" && "${GITHUB_REPOSITORY_VISIBILITY:-}" != 'private' ]]; then
    echo 'BLOCKED_UNVERIFIED_CI_PRIVACY: require a private-repository runner' >&2
    exit 2
fi
if [[ ! -f "$CLASSIC_ORIGINAL_APK" || ! -f "$CLASSIC_APKTOOL_JAR" ]]; then
    echo 'BLOCKED_PRIVATE_INPUT_MISSING' >&2
    exit 2
fi
python3 - "$CLASSIC_ORIGINAL_APK" "$CLASSIC_APKTOOL_JAR" <<'PY'
import hashlib,sys
expected=('abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2',
          'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423')
for path,want in zip(sys.argv[1:],expected):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    if h.hexdigest()!=want:raise SystemExit('BLOCKED_PINNED_INPUT_SHA_MISMATCH')
PY
# Actual CI toolchain may provide Java 17. Reject unintended runtime drift.
java -version 2>&1 | grep -q 'version "17\.' || { echo 'BLOCKED_JAVA_NOT_17' >&2; exit 2; }
gradle -v | grep -Eq '^Gradle 9\.8\.2$' || { echo 'BLOCKED_GRADLE_NOT_9_8_2' >&2; exit 2; }
[[ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]] || { echo 'BLOCKED_SDK36_MISSING' >&2; exit 2; }
[[ -f "$ANDROID_HOME/build-tools/36.0.0/d8" ]] || { echo 'BLOCKED_BUILD_TOOLS36_MISSING' >&2; exit 2; }
[[ -d "$CLASSIC_OUTPUT_DIR" ]] || { echo 'BLOCKED_PRIVATE_OUTPUT_DIR_MISSING' >&2; exit 2; }
[[ ! -e "$CLASSIC_OUTPUT_DIR/classic-loft-dj-UNSIGNED.apk" ]] || { echo 'BLOCKED_OUTPUT_ALREADY_EXISTS' >&2; exit 2; }
export CLASSIC_ORIGINAL_APK CLASSIC_APKTOOL_JAR ANDROID_HOME
python3 -m unittest discover -s "$ROOT/tests" -q
python3 -m unittest discover -s "$ROOT/checkpoint/tests" -q
python3 "$ROOT/build_private_unsigned.py" \
 --original "$CLASSIC_ORIGINAL_APK" \
 --android-sdk "$ANDROID_HOME" \
 --output "$CLASSIC_OUTPUT_DIR/classic-loft-dj-UNSIGNED.apk"
printf '%s\n' 'PRIVATE_UNSIGNED_BUILD_COMPLETED; NOT SIGNED/INSTALLED/DEVICE-TESTED'
