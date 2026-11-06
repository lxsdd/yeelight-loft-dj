#!/usr/bin/env python3
"""Create a SHA-fenced, SOURCE-ONLY Android build-tool upgrade proposal.

No GitHub writes, no mutation of the checked-out source and no publishing.
All four changed files in the output are *candidate* changes until real PR CI
runs on their exact commit. Classic OEM contents are never read or distributed.
"""
from __future__ import annotations
import argparse
import difflib
import hashlib
import json
import re
from pathlib import Path

BASE_BLOBS = {
    'build.gradle.kts': '553a5c018e76008a1e87dd4b676645e11eb3d109',
    'app/build.gradle.kts': '26b66cbb89f1b793aac656af4f3a0df22f493904',
    '.github/workflows/android.yml': '6d69b4e2106ae2cfe2976cdbb9fc611e78aa5617',
}
NEW = {'gradle': '9.8.2', 'agp': '9.5.1', 'kotlin': '2.4.21'}

class GateFailure(RuntimeError):
    pass

def git_blob_sha(data: bytes) -> str:
    return hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()

def exact(old: str, find: str, replacement: str, label: str) -> str:
    if old.count(find) != 1:
        raise GateFailure(f'UNEXPECTED_SOURCE_{label}: found {old.count(find)} occurrences')
    return old.replace(find, replacement, 1)

def upgrade_root(t: str) -> str:
    t = exact(t, 'id("com.android.application") version "8.13.2" apply false',
              'id("com.android.application") version "9.5.1" apply false', 'AGP')
    t = exact(t, '    id("org.jetbrains.kotlin.android") version "2.2.20" apply false\n', '', 'OLD_KOTLIN_ANDROID_PLUGIN')
    t = exact(t, 'id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false',
              'id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false', 'COMPOSE_PLUGIN')
    return t

def upgrade_app(t: str) -> str:
    t = exact(t, '    id("org.jetbrains.kotlin.android")\n', '', 'APP_KOTLIN_ANDROID_PLUGIN')
    t = exact(t, '    kotlinOptions { jvmTarget = "17" }\n', '', 'OLD_KOTLIN_OPTIONS')
    new_block = '''kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

'''
    t = exact(t, '\ndependencies {\n', '\n'+new_block+'dependencies {\n', 'DEPENDENCIES_ANCHOR')
    return t

def upgrade_workflow(t: str) -> str:
    if not re.search(r'^\s+- uses: actions/checkout@v4$', t, re.M):
        raise GateFailure('CHECKOUT_V4_NOT_FOUND')
    if not re.search(r'^\s+- uses: actions/setup-java@v4$', t, re.M):
        raise GateFailure('SETUP_JAVA_V4_NOT_FOUND')
    if t.count("gradle-version: '8.13'") != 1:
        raise GateFailure('GRADLE_8_13_ANCHOR_MISSING')
    t = t.replace('uses: actions/checkout@v4', 'uses: actions/checkout@v7')
    t = t.replace('uses: actions/setup-java@v4', 'uses: actions/setup-java@v6')
    t = exact(t, "gradle-version: '8.13'", "gradle-version: '9.8.2'", 'GRADLE_VERSION')
    # The current production workflow uses name-based upload/download artifacts.
    # Stage the new action majors; release-path compatibility requires a real
    # trusted canary before any merge, not just pull_request CI.
    if t.count('uses: actions/upload-artifact@v4') != 1:
        raise GateFailure('EXPECTED_ONE_UPLOAD_ARTIFACT_V4')
    if t.count('uses: actions/download-artifact@v4') != 1:
        raise GateFailure('EXPECTED_ONE_DOWNLOAD_ARTIFACT_V4')
    t = t.replace('uses: actions/upload-artifact@v4', 'uses: actions/upload-artifact@v7')
    t = t.replace('uses: actions/download-artifact@v4', 'uses: actions/download-artifact@v8')
    t = exact(t, '      - name: Unit tests and debug build\n',
        '''      - name: Require Android SDK 36 and Build Tools 36
        shell: bash
        run: |
          set -euo pipefail
          sdkmanager --install 'platforms;android-36' 'build-tools;36.0.0'
          sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK missing}}"
          test -f "$sdk/platforms/android-36/android.jar"
          test -f "$sdk/build-tools/36.0.0/d8"
      - name: Unit tests and debug build
''', 'UNIT_TEST_ANCHOR')
    return t

TRANSFORM = {
    'build.gradle.kts': upgrade_root,
    'app/build.gradle.kts': upgrade_app,
    '.github/workflows/android.yml': upgrade_workflow,
}

def stage(repo_root: Path, target: Path, expected: dict[str,str] | None = None) -> dict:
    expected = BASE_BLOBS if expected is None else expected
    repo_root = Path(repo_root).resolve()
    target = Path(target).resolve()
    if target.exists():
        raise GateFailure('OUTPUT_ALREADY_EXISTS')
    # Fail before side effects if the checkout differs from the exact PR69 baseline.
    old = {}
    new = {}
    for path in TRANSFORM:
        if path not in expected:
            raise GateFailure('BASE_BLOB_UNPINNED_'+path)
        p = repo_root / path
        if not p.is_file():
            raise GateFailure('BASE_FILE_MISSING_'+path)
        data = p.read_bytes()
        sha = git_blob_sha(data)
        if sha != expected[path]:
            raise GateFailure('BASE_BLOB_MISMATCH_'+path+' actual='+sha)
        text = data.decode('utf-8')
        old[path] = text
        new[path] = TRANSFORM[path](text)
        if new[path] == text:
            raise GateFailure('NO_SOURCE_DELTA_'+path)
    # Check that the signing/release policy was not weakened by the change.
    before, after = old['.github/workflows/android.yml'], new['.github/workflows/android.yml']
    protected = (
        "if: ${{ github.ref == 'refs/heads/main' && github.event_name != 'pull_request' }}",
        'publish-signed-github-release:',
        'Configure stable signing (trusted builds only)',
        'secrets.YEELIGHT_SIGNING_STORE_B64',
        'secrets.YEELIGHT_SIGNING_PASSWORD',
    )
    for marker in protected:
        if marker not in before or marker not in after or before.count(marker) != after.count(marker):
            raise GateFailure('RELEASE_GATE_CHANGED_'+marker)
    diff = ''.join(''.join(difflib.unified_diff(
        old[p].splitlines(keepends=True), new[p].splitlines(keepends=True),
        fromfile='a/'+p,tofile='b/'+p)) for p in TRANSFORM)
    manifest = {
        'status': 'STAGED_ONLY_REQUIRES_EXACT_SOURCE_ANDROID_CI',
        'original_repo_unchanged': True,
        'source_ref': '93a58be2c530f9faa7b74688692a4796b2a6dd71',
        'input_blob_shas': expected,
        'proposed_versions': NEW,
        'files': {p: {'updated_sha256': hashlib.sha256(new[p].encode()).hexdigest()} for p in new},
        'oem_apk_used': False,
        'signing_or_release_performed': False,
    }
    target.mkdir(parents=True)
    for p in TRANSFORM:
        dest = target / p
        dest.parent.mkdir(parents=True,exist_ok=True)
        dest.write_text(new[p],encoding='utf-8')
    (target/'toolchain-upgrade.diff').write_text(diff, encoding='utf-8')
    (target/'toolchain-upgrade-report.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    return manifest

def main(argv=None) -> int:
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repo',required=True,type=Path,help='local *public source* checkout (not OEM APK)')
    cli.add_argument('--output',required=True,type=Path,help='fresh directory for staged patch; original checkout unchanged')
    args = cli.parse_args(argv)
    try:
        result = stage(args.repo,args.output)
    except (GateFailure, OSError, UnicodeError, ValueError) as e:
        print('BLOCKED: '+str(e))
        return 2
    print(json.dumps(result,indent=2))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
