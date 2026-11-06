from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
import unittest

P=Path(__file__).resolve().parents[1]/'check_public_boundary.py'
spec=importlib.util.spec_from_file_location('boundary_gate',P)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class BoundaryTests(unittest.TestCase):
    def test_source_only_allowed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            p=root/'classic_source/sub/core.py';p.parent.mkdir(parents=True);p.write_text('pass')
            self.assertEqual(module.check_paths(['classic_source/sub/core.py'],root),1)
    def test_restricted_oem_assets_fail(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for ext in ('.apk','.dex','.smali','.so','.p12','.keystore','.pcap'):
                p=root/('classic_source/oem'+ext);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'data')
                with self.subTest(ext=ext),self.assertRaises(module.BoundaryViolation):
                    module.check_paths([p.relative_to(root).as_posix()],root)
    def test_symlink_disallowed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);target=root/'outside';target.write_text('secret')
            p=root/'classic_source/unsafe.py';p.parent.mkdir();p.symlink_to(target)
            with self.assertRaises(module.BoundaryViolation):
                module.check_paths(['classic_source/unsafe.py'],root)
    def test_empty_scope_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(module.BoundaryViolation):module.check_paths(['README.md'],Path(d))
    def test_missing_and_traversal_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for path in ('classic_source/missing.py','classic_source/../../etc/passwd','github_hosted/\\secret.py'):
                with self.subTest(path=path),self.assertRaises(module.BoundaryViolation):module.check_paths([path],root)
if __name__=='__main__':unittest.main()
