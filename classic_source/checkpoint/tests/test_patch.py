import importlib.util, pathlib, tempfile, unittest
from unittest.mock import patch
P = pathlib.Path(__file__).resolve().parents[1] / 'src' / 'classic_patch.py'
spec = importlib.util.spec_from_file_location('classic_patch', P)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
class PatchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)
        self.apk = root / 'original.apk'; self.apk.write_bytes(b'fake synthetic original')
        self.dec = root / 'decoded'; self.dec.mkdir()
        (self.dec / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yeelight.cherry"><application><activity android:name=".MainActivity" /><provider android:authorities="com.yeelight.cherry.provider" /></application></manifest>')
        self.hook = self.dec / 'smali' / 'demo' / 'ClassicActivity.smali'; self.hook.parent.mkdir(parents=True)
        self.hook.write_text('.class public Ldemo/ClassicActivity;\n.super Landroid/app/Activity;\n.method public onClick(Landroid/view/View;)V\n    .locals 0\n    # SAFE_HOOK\n    return-void\n.end method\n')
        self.dst = root / 'patched'
    def run_patch(self): return m.patch(self.apk, self.dec, self.dst, 'smali/demo/ClassicActivity.smali', '# SAFE_HOOK')
    def test_refuses_wrong_hash(self):
        with self.assertRaisesRegex(m.PatchError, 'SHA256'): self.run_patch()
        self.assertFalse(self.dst.exists())
    def test_synthetic_navigation_and_manifest(self):
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            result = self.run_patch()
        self.assertEqual(result['package'], m.NEW_PACKAGE)
        manifest = (self.dst / 'AndroidManifest.xml').read_text()
        self.assertIn('loftdj.private.classic.provider', manifest)
        self.assertIn('loftdj.classic.DjActivity', manifest)
        self.assertIn('com.yeelight.cherry.MainActivity', manifest)
        self.assertIn('invoke-static {p0}', (self.dst / 'smali/demo/ClassicActivity.smali').read_text())
        self.assertTrue((self.dst / 'smali/loftdj/classic/DjEntry.smali').exists())
        self.assertNotIn('loftdj.classic.DjActivity', (self.dec / 'AndroidManifest.xml').read_text())
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'already exists'): self.run_patch()
    def test_refuses_ambiguous_anchor(self):
        self.hook.write_text(self.hook.read_text().replace('# SAFE_HOOK', '# SAFE_HOOK\n# SAFE_HOOK'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'not unique'): self.run_patch()
    def test_refuses_unmapped_authority(self):
        f = self.dec / 'AndroidManifest.xml'; f.write_text(f.read_text().replace('com.yeelight.cherry.provider', 'other.provider'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Unknown provider'): self.run_patch()
    def test_refuses_outside_hook(self):
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Invalid in-tree'): m.patch(self.apk, self.dec, self.dst, '../outside.smali', '# SAFE_HOOK')
if __name__ == '__main__': unittest.main()

class AdditionalHookSafety(PatchTests):
    def test_refuses_fragment_hook(self):
        self.hook.write_text(self.hook.read_text().replace('Landroid/app/Activity;', 'Landroid/app/Fragment;'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'not a directly verified Activity'): self.run_patch()
    def test_refuses_static_hook(self):
        self.hook.write_text(self.hook.read_text().replace('public onClick', 'public static onClick'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'instance method'): self.run_patch()

class TreeBoundaryTests(PatchTests):
    def test_refuses_symlink_in_decoded_tree(self):
        outside = pathlib.Path(self.tmp.name) / 'outside'; outside.write_text('private')
        (self.dec / 'leak').symlink_to(outside)
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Symlink inside'): self.run_patch()
        self.assertFalse(self.dst.exists())
    def test_refuses_symlinked_root(self):
        link = pathlib.Path(self.tmp.name) / 'linked'
        link.symlink_to(self.dec, target_is_directory=True)
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Symlinked root'):
                m.patch(self.apk, link, self.dst, 'smali/demo/ClassicActivity.smali', '# SAFE_HOOK')

class AtomicOutputTests(PatchTests):
    def test_existing_injected_namespace_leaves_no_output(self):
        existing = self.dec / 'smali' / 'loftdj' / 'classic'
        existing.mkdir(parents=True)
        (existing / 'sentinel').write_text('keep')
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'namespace already exists'): self.run_patch()
        self.assertFalse(self.dst.exists())
        self.assertEqual((existing / 'sentinel').read_text(), 'keep')
        self.assertEqual(list(self.dst.parent.glob('.classic-stage-*')), [])

    def test_write_failure_removes_staging_and_destination(self):
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA), patch.object(m, '_write_injection', side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'): self.run_patch()
        self.assertFalse(self.dst.exists())
        self.assertEqual(list(self.dst.parent.glob('.classic-stage-*')), [])

    def test_absolute_hook_refused(self):
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Invalid in-tree'):
                m.patch(self.apk, self.dec, self.dst, str(self.hook), '# SAFE_HOOK')
        self.assertFalse(self.dst.exists())

class ReachabilitySafetyTests(PatchTests):
    def test_refuses_dead_hook_after_return(self):
        self.hook.write_text(self.hook.read_text().replace('    # SAFE_HOOK', '    return-void\n    # SAFE_HOOK'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'terminal or branching'): self.run_patch()
        self.assertFalse(self.dst.exists())

    def test_refuses_hook_before_branch(self):
        self.hook.write_text(self.hook.read_text().replace('    return-void', '    goto :finish\n    :finish\n    return-void'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Labelled|directly precede'): self.run_patch()
        self.assertFalse(self.dst.exists())

    def test_refuses_hook_in_exception_handler(self):
        self.hook.write_text(self.hook.read_text().replace('    # SAFE_HOOK', '    .catch Ljava/lang/Exception; {:start .. :end} :handler\n    # SAFE_HOOK'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'Complex control-flow'): self.run_patch()
        self.assertFalse(self.dst.exists())

    def test_refuses_nonvoid_handler(self):
        self.hook.write_text(self.hook.read_text().replace('onClick(Landroid/view/View;)V', 'onClick(Landroid/view/View;)Z'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'void handler'): self.run_patch()
        self.assertFalse(self.dst.exists())

    def test_refuses_hook_anchor_as_instruction(self):
        self.hook.write_text(self.hook.read_text().replace('# SAFE_HOOK', 'nop'))
        with patch.object(m, 'sha', return_value=m.ORIGINAL_SHA):
            with self.assertRaisesRegex(m.PatchError, 'comment marker'):
                m.patch(self.apk, self.dec, self.dst, 'smali/demo/ClassicActivity.smali', 'nop')
        self.assertFalse(self.dst.exists())
