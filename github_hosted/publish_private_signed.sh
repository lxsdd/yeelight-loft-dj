#!/usr/bin/env bash
# NO set -x. Run only in approved PRIVATE environment of the public source runner.
set -euo pipefail
set +x
umask 077
fail() { echo 'CLASSIC_PRIVATE_SIGNED_OUTPUT_BLOCKED' >&2; exit 2; }
[[ "${GH_TOKEN:-}" != '' && "$#" == 1 ]] || fail
[[ "${GITHUB_REPOSITORY:-}" == 'lxsdd/yeelight-loft-dj' ]] || fail
[[ "${GITHUB_EVENT_NAME:-}" == 'workflow_dispatch' && "${GITHUB_REF:-}" == 'refs/heads/main' && "${GITHUB_ACTOR:-}" == 'lxsdd' ]] || fail
[[ "${RUNNER_ENVIRONMENT:-}" == 'github-hosted' ]] || fail
OUT="$1"
[[ "$OUT" == "$RUNNER_TEMP"/* && -d "$OUT" ]] || fail
APK="$OUT/classic-loft-dj-TEST-SIGNED.apk"
REPORT="$OUT/classic-loft-dj-TEST-SIGNED.apk.test-signing.json"
[[ -s "$APK" && -s "$REPORT" ]] || fail
python3 - "$APK" "$REPORT" <<'PY' || fail
import hashlib,json,sys
from pathlib import Path
apk,report=map(Path,sys.argv[1:3])
d=json.loads(report.read_text('utf-8'))
assert d.get('status')=='PRIVATE_CLASSIC_TEST_APK_SIGNED_NOT_INSTALLED_NOT_HARDWARE_TESTED'
assert d.get('signed') is True and d.get('installed') is False
assert d.get('signature')=='EPHEMERAL_UNIQUE_TEST_KEY_NOT_SUITABLE_FOR_APP_UPDATES'
assert d.get('package')=='io.lxsdd.yeelightdj'
h=hashlib.sha256()
with apk.open('rb') as stream:
    for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
assert d.get('apk_sha256')==h.hexdigest()
PY
VAULT=lxsdd/yeelight-classic-vault
is_private() { [[ "$(gh api "repos/$VAULT" --jq .private 2>/dev/null)" == 'true' ]] || fail; }
is_private
TAG="classic-test-signed-run-${GITHUB_RUN_ID}-${GITHUB_SHA:0:12}"
[[ "$TAG" =~ ^classic-test-signed-run-[1-9][0-9]{0,11}-[0-9a-f]{12}$ ]] || fail
RECEIPT="$RUNNER_TEMP/classic-signed-receipt-${GITHUB_RUN_ID}.json"
[[ ! -e "$RECEIPT" ]] || fail
trap 'rm -f -- "$RECEIPT"' EXIT
check_receipt() {
    gh api "repos/$VAULT/releases?per_page=100" --paginate --slurp > "$RECEIPT" 2>/dev/null || return 2
    python3 github_hosted/private_signed_receipt.py "$RECEIPT" "$TAG" "$GITHUB_SHA" "$APK" "$REPORT"
}
if check_receipt; then
    echo 'PRIVATE_SIGNED_TEST_RELEASE_VERIFIED'
    exit 0
else
    result=$?
    [[ "$result" == 3 ]] || fail
fi
gh release create "$TAG" "$APK" "$REPORT" -R "$VAULT" --target main --draft --latest=false \
  --title "Classic private TEST-SIGNED ${GITHUB_RUN_ID}" \
  --notes "Source SHA: ${GITHUB_SHA}. PRIVATE TEST SIGNED with EPHEMERAL key. Not hardware approved." \
  >/dev/null 2>&1 || fail
is_private
check_receipt || fail
echo 'PRIVATE_SIGNED_TEST_RELEASE_VERIFIED'
