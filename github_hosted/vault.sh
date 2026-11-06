#!/usr/bin/env bash
# GitHub CLI private release exchange; never run this script with -x.
set -euo pipefail
set +x
umask 077
fail() { echo "CLASSIC_PRIVATE_VAULT_BLOCKED" >&2; exit 2; }
[[ "${GH_TOKEN:-}" != '' ]] || fail
[[ "$#" == 3 ]] || fail
MODE="$1"; PRIVATE_DIR="$2"; RELEASE_TAG="$3"
[[ "$PRIVATE_DIR" == "$RUNNER_TEMP"/* ]] || fail
[[ -d "$PRIVATE_DIR" ]] || fail
[[ "$GITHUB_REPOSITORY" == 'lxsdd/yeelight-loft-dj' && "$GITHUB_REF" == 'refs/heads/main' && "$GITHUB_EVENT_NAME" == 'workflow_dispatch' && "$GITHUB_ACTOR" == 'lxsdd' ]] || fail
[[ "${RUNNER_ENVIRONMENT:-}" == 'github-hosted' ]] || fail
VAULT='lxsdd/yeelight-classic-vault'
# A private destination is a safety requirement, not a naming convention.
# Reject credential use if the vault has accidentally become public or is unreadable.
assert_private_vault() {
  local private
  private="$(gh api "repos/$VAULT" --jq '.private' 2>/dev/null)" || fail
  [[ "$private" == 'true' ]] || fail
}
assert_private_vault
if [[ "$MODE" == 'fetch' ]]; then
  [[ "$RELEASE_TAG" == 'classic-original-3.5.4-v1' ]] || fail
  [[ ! -e "$PRIVATE_DIR/com.yeelight.cherry_3.5.4.apk" ]] || fail
  gh release download "$RELEASE_TAG" -R "$VAULT" -p 'com.yeelight.cherry_3.5.4.apk' -D "$PRIVATE_DIR" >/dev/null 2>&1 || fail
  chmod 600 "$PRIVATE_DIR/com.yeelight.cherry_3.5.4.apk"
  echo 'abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2  '"$PRIVATE_DIR/com.yeelight.cherry_3.5.4.apk" | sha256sum -c --status || fail
  echo 'PRIVATE_INPUT_SHA256_VERIFIED'
elif [[ "$MODE" == 'publish' ]]; then
  [[ "$RELEASE_TAG" =~ ^classic-unsigned-run-[1-9][0-9]{0,11}-[0-9a-f]{12}$ ]] || fail
  [[ "$RELEASE_TAG" == "classic-unsigned-run-${GITHUB_RUN_ID}-${GITHUB_SHA:0:12}" ]] || fail
  OUT="$PRIVATE_DIR/classic-loft-dj-UNSIGNED.apk"
  REPORT="$PRIVATE_DIR/classic-loft-dj-UNSIGNED.apk.preflight.json"
  [[ -s "$OUT" && -s "$REPORT" ]] || fail
  # The structural preflight report is the release gate; it is not a hardware qualification.
  python3 - "$REPORT" "$OUT" <<'PY'
import hashlib,json,sys
with open(sys.argv[1], encoding='utf-8') as stream: d=json.load(stream)
with open(sys.argv[2], 'rb') as f: actual=hashlib.sha256(f.read()).hexdigest()
assert d['status']=='UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED'
assert d['signed'] is False and d['installed'] is False
assert d['apk_sha256']==actual
PY
  # Draft release assets live in a PRIVATE repository, not in public Actions artifacts.
  # The GitHub REST by-tag endpoint returns 404 for DRAFT releases.
  # Authenticated release listing includes drafts; paginate and slurp all pages.
  # Re-running the same workflow run may encounter an already uploaded draft:
  # verify exact private bytes instead of creating duplicates or overwriting.
  readonly release_json="$RUNNER_TEMP/classic-private-release-verify-${GITHUB_RUN_ID}.json"
  [[ ! -e "$release_json" ]] || fail
  trap 'rm -f -- "$release_json"' EXIT
  receipt_check() {
    gh api "repos/$VAULT/releases?per_page=100" --paginate --slurp > "$release_json" 2>/dev/null || return 2
    python3 github_hosted/release_receipt.py "$release_json" "$OUT" "$REPORT" "$RELEASE_TAG" "$GITHUB_SHA"
  }
  if receipt_check; then
    echo 'PRIVATE_DRAFT_RELEASE_VERIFIED_NO_PUBLIC_APK_ARTIFACT'
    exit 0
  else
    receipt_status=$?
    [[ "$receipt_status" == 3 ]] || fail
  fi
  # Tag is genuinely absent in the authenticated PRIVATE listing.
  gh release create "$RELEASE_TAG" "$OUT" "$REPORT" -R "$VAULT" --target main --draft --latest=false \
    --title "Private Classic unsigned structural build ${GITHUB_RUN_ID}" \
    --notes "Source SHA: ${GITHUB_SHA}. UNSIGNED. Structural preflight only; NOT installed or hardware approved." \
    >/dev/null 2>&1 || fail
  assert_private_vault
  receipt_check || fail
  echo 'PRIVATE_DRAFT_RELEASE_VERIFIED_NO_PUBLIC_APK_ARTIFACT'
else
  fail
fi
