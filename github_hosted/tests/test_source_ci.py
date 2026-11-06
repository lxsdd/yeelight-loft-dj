"""Source-only CI must never request vault secrets or publish binaries."""
from pathlib import Path
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[2]
PATH=ROOT/'.github/workflows/classic-source-verify.yml'

class SourceWorkflowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.txt=PATH.read_text('utf-8')
        cls.doc=yaml.load(cls.txt,Loader=yaml.BaseLoader)
    def test_no_secret_or_build_environment(self):
        self.assertEqual(cls_permissions:=self.doc['permissions'],{'contents':'read'})
        self.assertNotIn('classic-private-build',self.txt)
        for banned in ('secrets.', 'GH_TOKEN', 'pull_request_target', 'actions/upload-artifact@', 'gh release create', 'gh release upload'):
            self.assertNotIn(banned, self.txt)
    def test_public_only_trigger(self):
        self.assertEqual(set(self.doc['on']),{'pull_request','workflow_dispatch'})
        self.assertNotIn('push', self.doc['on'])
        job=self.doc['jobs']['source-verify']
        self.assertEqual(job['runs-on'],'ubuntu-latest')
        self.assertNotIn('environment',job)
    def test_boundary_scanner_before_code_tests(self):
        steps=self.doc['jobs']['source-verify']['steps']
        names=[x['name'] for x in steps]
        self.assertLess(names.index('Verify public/private source boundary'),names.index('Run synthetic-only Classic tests (no original APK)'))
        self.assertIn('check_public_boundary.py',self.txt)
        self.assertIn("CLASSIC_ORIGINAL_APK: ''",self.txt)
if __name__=='__main__': unittest.main()
