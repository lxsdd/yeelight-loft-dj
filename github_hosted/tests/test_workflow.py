from __future__ import annotations
import pathlib
import unittest
import yaml

BASE=pathlib.Path(__file__).resolve().parents[2]
WORKFLOW=BASE/'.github/workflows/classic-protected-assembly.yml'

class WorkflowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text=WORKFLOW.read_text('utf-8')
        cls.w=yaml.load(cls.text,Loader=yaml.BaseLoader)
        cls.job=cls.w['jobs']['guarded-private-build']
        cls.steps=cls.job['steps']
    def test_manual_only_no_pr_or_push(self):
        self.assertEqual(list(self.w['on']),['workflow_dispatch'])
        self.assertEqual(self.w['permissions'], {'contents':'read'})
        self.assertNotIn('pull_request_target',self.text)
    def test_owner_main_ref_environment(self):
        expression=self.job['if']
        for required in ('github.actor == \'lxsdd\'',"github.ref == 'refs/heads/main'", "github.repository == 'lxsdd/yeelight-loft-dj'"):
            self.assertIn(required,expression)
        self.assertEqual(self.job['environment']['name'],'classic-private-build')
        self.assertEqual(self.job['runs-on'],'ubuntu-latest')
        self.assertEqual(self.w['concurrency']['cancel-in-progress'],'false')
    def test_only_two_scoped_secret_steps(self):
        secret_steps=[x for x in self.steps if '${{ secrets.' in str(x)]
        self.assertEqual(len(secret_steps),2)
        for step in secret_steps:
            self.assertEqual(step['env'],{'GH_TOKEN':'${{ secrets.CLASSIC_VAULT_TOKEN }}'})
            self.assertIn('github_hosted/vault.sh', step['run'])
        self.assertNotIn('secrets.',str(self.job.get('env','')))
    def test_no_public_distribution_or_persistent_cache(self):
        src='\n'.join(s.get('run','') for s in self.steps)
        self.assertNotIn('actions/upload-artifact',self.text)
        self.assertNotIn('actions/cache',self.text)
        self.assertNotIn('gh release create',self.text)
        self.assertNotIn('gh release upload',self.text)
        self.assertNotIn('workflow_dispatch:',str(self.w['jobs']))
        gradle=next(s for s in self.steps if 'gradle/actions/setup-gradle' in s.get('uses',''))
        self.assertEqual(gradle['with']['cache-disabled'],'true')
        self.assertEqual(gradle['with']['gradle-version'],'9.8.2')
        self.assertNotIn('sign', '\n'.join(s.get('name','') for s in self.steps).lower().replace('unsigned',''))
    def test_checkout_no_credentials(self):
        checkout=next(s for s in self.steps if s.get('uses','').startswith('actions/checkout'))
        self.assertEqual(checkout['with']['persist-credentials'],'false')
    def test_private_asset_sha_pinned(self):
        self.assertIn('com.yeelight.cherry_3.5.4.apk',self.text)
        self.assertIn('github_hosted/vault.sh fetch',self.text)
        self.assertIn('github_hosted/vault.sh publish',self.text)
        self.assertIn('dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423',self.text)
        self.assertIn('private-output',self.text)

if __name__=='__main__':unittest.main()
