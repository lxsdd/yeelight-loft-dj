"""SHA-fenced tooling migration contract, source-only and owner-safe."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from toolchain_upgrade.prepare_loft_upgrade import stage,git_blob_sha,GateFailure,upgrade_root,upgrade_app,upgrade_workflow

ROOT = '''plugins {\n    id("com.android.application") version "8.13.2" apply false\n    id("org.jetbrains.kotlin.android") version "2.2.20" apply false\n    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false\n}\n'''
APP = '''plugins {\n    id("com.android.application")\n    id("org.jetbrains.kotlin.android")\n    id("org.jetbrains.kotlin.plugin.compose")\n}\nandroid {\n    kotlinOptions { jvmTarget = "17" }\n}\n\ndependencies {\n}\n'''
WORKFLOW = '''name: Android CI\non:\n  pull_request:\njobs:\n  verify-and-build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n      - uses: gradle/actions/setup-gradle@v6\n        with:\n          gradle-version: '8.13'\n      - name: Configure stable signing (trusted builds only)\n        if: ${{ github.ref == 'refs/heads/main' && github.event_name != 'pull_request' }}\n        env:\n          KEY: ${{ secrets.YEELIGHT_SIGNING_STORE_B64 }}\n          PW: ${{ secrets.YEELIGHT_SIGNING_PASSWORD }}\n      - uses: actions/upload-artifact@v4\n      - uses: actions/download-artifact@v4\n      - name: Unit tests and debug build\n        run: gradle testDebugUnitTest assembleDebug\n  publish-signed-github-release:\n    if: ${{ github.ref == 'refs/heads/main' && github.event_name != 'pull_request' }}\n'''
SOURCES={'build.gradle.kts':ROOT,'app/build.gradle.kts':APP,'.github/workflows/android.yml':WORKFLOW}

class ToolchainUpgradeTests(unittest.TestCase):
    def test_root_migrates_built_in_kotlin(self):
        s=upgrade_root(ROOT)
        self.assertIn('9.5.1',s)
        self.assertIn('2.4.21',s)
        self.assertNotIn('org.jetbrains.kotlin.android',s)
    def test_app_moves_jvm_target_out_of_android(self):
        s=upgrade_app(APP)
        self.assertIn('compilerOptions',s)
        self.assertNotIn('kotlinOptions',s)
        self.assertEqual(s.count('id("org.jetbrains.kotlin.plugin.compose")'),1)
    def test_workflow_keeps_sdk_and_gates(self):
        s=upgrade_workflow(WORKFLOW)
        self.assertIn("gradle-version: '9.8.2'",s)
        self.assertIn('setup-java@v6',s)
        self.assertIn('checkout@v7',s)
        self.assertIn('sdkmanager --install',s)
        self.assertIn('actions/upload-artifact@v7',s)
        self.assertIn('actions/download-artifact@v8',s)
        self.assertEqual(s.count('publish-signed-github-release:'),1)
        self.assertEqual(s.count("github.event_name != 'pull_request'"),2)
    def test_pinned_stage_keeps_checkout_unchanged(self):
        with tempfile.TemporaryDirectory() as td:
            repo=Path(td)/'repo';repo.mkdir()
            blobs={}
            for p,v in SOURCES.items():
                f=repo/p;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(v)
                blobs[p]=git_blob_sha(v.encode())
            out=Path(td)/'out'
            r=stage(repo,out,blobs)
            self.assertEqual(r['status'],'STAGED_ONLY_REQUIRES_EXACT_SOURCE_ANDROID_CI')
            self.assertEqual((repo/'build.gradle.kts').read_text(),ROOT)
            self.assertIn('9.5.1',(out/'build.gradle.kts').read_text())
            self.assertIn('gradle-version', (out/'toolchain-upgrade.diff').read_text())
            with self.assertRaisesRegex(GateFailure,'OUTPUT_ALREADY_EXISTS'):stage(repo,out,blobs)
    def test_tampered_base_fails_without_output(self):
        with tempfile.TemporaryDirectory() as td:
            repo=Path(td)/'repo';repo.mkdir()
            blobs={}
            for p,v in SOURCES.items():
                f=repo/p;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(v)
                blobs[p]=git_blob_sha(v.encode())
            (repo/'app/build.gradle.kts').write_text(APP+'// tampered\n')
            out=Path(td)/'out'
            with self.assertRaisesRegex(GateFailure,'BASE_BLOB_MISMATCH_app/build.gradle.kts'):
                stage(repo,out,blobs)
            self.assertFalse(out.exists())
    def test_no_unexpected_markers(self):
        with self.assertRaisesRegex(GateFailure,'UNEXPECTED_SOURCE_AGP'):
            upgrade_root(ROOT.replace('8.13.2','9.5.1'))
        with self.assertRaisesRegex(GateFailure,'UNIT_TEST_ANCHOR'):
            upgrade_workflow(WORKFLOW.replace('Unit tests and debug build','We changed the build'))
    def test_git_blob_sha_matches_known(self):
        self.assertEqual(git_blob_sha(b'hello'),hashlib.sha1(b'blob 5\0hello').hexdigest())

if __name__ == '__main__':unittest.main()
