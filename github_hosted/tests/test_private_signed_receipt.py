from __future__ import annotations
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'private_signed_receipt.py'
spec=importlib.util.spec_from_file_location('signed_receipt',SCRIPT)
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
SHA='a'*40
TAG='classic-test-signed-run-12345-'+SHA[:12]

class SignedReceiptTests(unittest.TestCase):
    def case(self,draft=True,tamper=False):
        with tempfile.TemporaryDirectory() as dir:
            root=Path(dir)
            apk=root/'classic-loft-dj-TEST-SIGNED.apk';apk.write_bytes(b'ephemeral-signed-test-fake')
            report=root/'classic-loft-dj-TEST-SIGNED.apk.test-signing.json';report.write_text('{"status":"test"}')
            assets=[]
            for path in [apk,report]:
                digest=mod.file_sha(path)
                if tamper:digest='sha256:'+'0'*64
                assets.append({'name':path.name,'size':path.stat().st_size,'state':'uploaded','digest':digest})
            release={'tag_name':TAG,'draft':draft,'target_commitish':'main',
                     'body':f'Source SHA: {SHA}. PRIVATE TEST SIGNED with EPHEMERAL key. Not hardware approved.',
                     'assets':assets}
            self.assertEqual(mod.verify([[]],TAG,SHA,apk,report),3)
            self.assertEqual(mod.verify([[release]],TAG,SHA,apk,report),0 if draft and not tamper else 2)
            self.assertEqual(mod.verify([[release,release]],TAG,SHA,apk,report),2)
            self.assertEqual(mod.verify([[release]],TAG,'b'*40,apk,report),2)
    def test_exact_and_absent(self):self.case()
    def test_reject_published(self):self.case(draft=False)
    def test_reject_wrong_digest(self):self.case(tamper=True)

if __name__=='__main__':unittest.main()
