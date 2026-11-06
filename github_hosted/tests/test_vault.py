from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

BASE=Path(__file__).resolve().parents[2]
SCRIPT=BASE/'github_hosted/vault.sh'

class VaultGuard(unittest.TestCase):
    def run_script(self,mode,dirname,tag,overrides=None):
        e=os.environ.copy()
        e.update({'GH_TOKEN':'test-token-never-echo','GITHUB_REPOSITORY':'lxsdd/yeelight-loft-dj',
            'GITHUB_REF':'refs/heads/main','GITHUB_ACTOR':'lxsdd', 'GITHUB_EVENT_NAME':'workflow_dispatch',
            'RUNNER_ENVIRONMENT':'github-hosted','GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'12345',
            'RUNNER_TEMP':str(dirname.parent)})
        if overrides:e.update(overrides)
        return subprocess.run(['bash',str(SCRIPT),mode,str(dirname),tag],env=e,text=True,capture_output=True)
    def test_denies_invalid_event_even_with_token(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d)/'input';directory.mkdir()
            for name,val in [('GITHUB_EVENT_NAME','pull_request'),('GITHUB_ACTOR','attacker'),('GITHUB_REF','refs/heads/feature')]:
                with self.subTest(name=name):
                    r=self.run_script('fetch',directory,'classic-original-3.5.4-v1',{name:val})
                    self.assertEqual(r.returncode,2)
                    self.assertNotIn('test-token-never-echo',r.stdout+r.stderr)
    def test_denies_unknown_tag_and_outside_temp_dir(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d)/'input';directory.mkdir()
            r=self.run_script('fetch',directory,'../../bad')
            self.assertEqual(r.returncode,2)
            r=self.run_script('fetch',Path('/usr') ,'classic-original-3.5.4-v1')
            self.assertEqual(r.returncode,2)
    def test_denies_missing_token(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d)/'input';directory.mkdir()
            r=self.run_script('fetch',directory,'classic-original-3.5.4-v1',{'GH_TOKEN':''})
            self.assertEqual(r.returncode,2)
    def test_denies_publish_without_structural_preflight(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'output';out.mkdir()
            (out/'classic-loft-dj-UNSIGNED.apk').write_bytes(b'fake')
            (out/'classic-loft-dj-UNSIGNED.apk.preflight.json').write_text('{}')
            tag='classic-unsigned-run-12345-'+'a'*12
            r=self.run_script('publish',out,tag)
            self.assertNotEqual(r.returncode,0)
            self.assertNotIn('test-token-never-echo',r.stdout+r.stderr)

if __name__=='__main__':unittest.main()

class VaultPublishSmoke(unittest.TestCase):
    def fake_gh_run(self, visibility='true', wrong_digest=False, wrong_draft=False, already_exists=False):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            output=root/'private-output';output.mkdir()
            data=b'pretend-private-output-structural-only'
            digest=hashlib.sha256(data).hexdigest()
            (output/'classic-loft-dj-UNSIGNED.apk').write_bytes(data)
            (output/'classic-loft-dj-UNSIGNED.apk.preflight.json').write_text(json.dumps({
                'status':'UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED',
                'signed':False,'installed':False,'apk_sha256':digest
            }))
            bindir=root/'bin';bindir.mkdir()
            gh=bindir/'gh'
            log=root/'gh.argv.jsonl'
            gh.write_text("""#!/usr/bin/env python3
import os,sys,json,pathlib,hashlib
args=sys.argv[1:]
with open(os.environ['FAKE_GH_CAPTURE'],'a') as f:f.write(json.dumps(args)+'\\n')
if args[:2]==['api','repos/lxsdd/yeelight-classic-vault']:
    print(os.environ['FAKE_VAULT_VISIBILITY'])
    sys.exit(0)
if args[:2]==['release','create']:
    pathlib.Path(os.environ['FAKE_GH_CREATED']).write_text('created')
    sys.exit(0)
if args[:2]==['api','repos/lxsdd/yeelight-classic-vault/releases?per_page=100'] and args[2:]==['--paginate','--slurp']:
    exists=pathlib.Path(os.environ['FAKE_GH_CREATED']).exists() or os.environ.get('FAKE_ALREADY_EXISTS')=='1'
    if not exists:
        print(json.dumps([[]]))
        sys.exit(0)
    paths=[pathlib.Path(os.environ['FAKE_OUTPUT'])/name for name in
        ('classic-loft-dj-UNSIGNED.apk','classic-loft-dj-UNSIGNED.apk.preflight.json')]
    assets=[]
    for p in paths:
        sha=hashlib.sha256(p.read_bytes()).hexdigest()
        if os.environ.get('FAKE_WRONG_DIGEST')=='1': sha='0'*64
        assets.append({'name':p.name,'digest':'sha256:'+sha,'size':p.stat().st_size,'state':'uploaded'})
    release={'tag_name':os.environ['FAKE_TAG'],
        'target_commitish':'main',
        'body':'Source SHA: '+('a'*40)+'. UNSIGNED. Structural preflight only; NOT installed or hardware approved.',
        'draft':os.environ.get('FAKE_WRONG_DRAFT')!='1', 'assets':assets}
    print(json.dumps([[release]]))
    sys.exit(0)
if len(args)>1 and '/releases/tags/' in args[1]:
    sys.exit(22) # As observed on GitHub: draft by-tag API returns 404.
sys.exit(3)
""")
            gh.chmod(0o755)
            tag='classic-unsigned-run-12345-'+'a'*12
            e=os.environ.copy()
            e.update({'PATH':str(bindir)+os.pathsep+e.get('PATH',''),'FAKE_GH_CAPTURE':str(log),
                'FAKE_VAULT_VISIBILITY':visibility,'FAKE_OUTPUT':str(output),'FAKE_TAG':tag,
                'FAKE_GH_CREATED':str(root/'already-created'),'FAKE_ALREADY_EXISTS':'1' if already_exists else '0',
                'FAKE_WRONG_DIGEST':'1' if wrong_digest else '0',
                'FAKE_WRONG_DRAFT':'1' if wrong_draft else '0',
                'GH_TOKEN':'fake-secret','RUNNER_TEMP':str(root),'GITHUB_REPOSITORY':'lxsdd/yeelight-loft-dj',
                'GITHUB_REF':'refs/heads/main','GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_ACTOR':'lxsdd',
                'RUNNER_ENVIRONMENT':'github-hosted','GITHUB_RUN_ID':'12345','GITHUB_SHA':'a'*40})
            r=subprocess.run(['bash',str(SCRIPT),'publish',str(output),tag],env=e,text=True,capture_output=True)
            calls=[json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            self.assertNotIn('fake-secret',r.stdout+r.stderr+repr(calls))
            self.assertFalse(any('lxsdd/yeelight-loft-dj' in repr(c) for c in calls))
            return r,calls

    def test_private_draft_release_is_only_gh_path(self):
        r,calls=self.fake_gh_run()
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('PRIVATE_DRAFT_RELEASE_VERIFIED_NO_PUBLIC_APK_ARTIFACT',r.stdout)
        creates=[x for x in calls if x[:2]==['release','create']]
        self.assertEqual(len(creates),1)
        self.assertIn('--draft',creates[0])
        self.assertIn('lxsdd/yeelight-classic-vault',creates[0])
        self.assertEqual(len([x for x in calls if x[:2]==['api','repos/lxsdd/yeelight-classic-vault']]),2)

    def test_rerun_with_existing_identical_private_draft_does_not_reupload(self):
        r,calls=self.fake_gh_run(already_exists=True)
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertFalse(any(c[:2]==['release','create'] for c in calls))
        self.assertIn('PRIVATE_DRAFT_RELEASE_VERIFIED_NO_PUBLIC_APK_ARTIFACT',r.stdout)
    def test_draft_lookup_never_uses_unavailable_by_tag_endpoint(self):
        r,calls=self.fake_gh_run()
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertFalse(any('/releases/tags/' in ' '.join(c) for c in calls))

    def test_vault_visibility_changed_public_before_upload_blocks(self):
        r,calls=self.fake_gh_run(visibility='false')
        self.assertEqual(r.returncode,2)
        self.assertFalse(any(c[:2]==['release','create'] for c in calls))

    def test_remote_release_readback_digest_mismatch_blocks(self):
        r,calls=self.fake_gh_run(wrong_digest=True)
        self.assertEqual(r.returncode,2)
        self.assertEqual(len([c for c in calls if c[:2]==['release','create']]),1)

    def test_remote_release_not_draft_blocks(self):
        r,calls=self.fake_gh_run(wrong_draft=True)
        self.assertEqual(r.returncode,2)
