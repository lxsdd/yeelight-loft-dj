"""Synthetic positive and negative regressions for main-vs-:miot guard rewrite."""
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import GateFailure
from clone_main_process_guard import patch_guard_text, patch_staged_clone, SOURCE_PATH

ORIGINAL = """.class public Lcom/yeelight/cherry/YeelightApplication;
.super Landroidx/multidex/MultiDexApplication;
.method public h()Z
    .locals 2
    invoke-direct {p0}, Lcom/yeelight/cherry/YeelightApplication;->f()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :not_main
    const-string v1, "com.yeelight.cherry"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :not_main
    const/4 v0, 0x1
    return v0
    :not_main
    const/4 v0, 0x0
    return v0
.end method
.method public onCreate()V
    .locals 1
    const-string v0, "com.yeelight.cherry"
    return-void
.end method
"""

class ProcessGuardTests(unittest.TestCase):
    def test_one_method_only(self):
        patched = patch_guard_text(ORIGINAL)
        self.assertEqual(patched.count("->getPackageName()"), 1)
        self.assertEqual(patched.count('"com.yeelight.cherry"'), 1)
        self.assertIn("move-result-object v1", patched)
        self.assertEqual(patched.split(".method public onCreate()V")[1],
                         ORIGINAL.split(".method public onCreate()V")[1])
    def test_double_apply_rejected(self):
        with self.assertRaises(GateFailure): patch_guard_text(patch_guard_text(ORIGINAL))
    def test_wrong_class_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace("Lcom/yeelight/cherry/YeelightApplication;", "LFake;", 1))
    def test_missing_literal_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace('const-string v1, "com.yeelight.cherry"', 'nop', 1))
    def test_ambiguous_literal_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace('const-string v1, "com.yeelight.cherry"',
                'const-string v1, "com.yeelight.cherry"\n    const-string v1, "com.yeelight.cherry"', 1))
    def test_invalid_register_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace('const-string v1,', 'const-string v0,', 1))
    def test_unknown_upstream_method_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace("->f()Ljava/lang/String;", "->other()Ljava/lang/String;", 1))
    def test_no_equals_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace("->equals(Ljava/lang/Object;)Z", "->contains(Ljava/lang/Object;)Z", 1))
    def test_changed_locals_rejected(self):
        with self.assertRaises(GateFailure):
            patch_guard_text(ORIGINAL.replace(".locals 2", ".locals 1", 1))
    def test_missing_file_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(GateFailure): patch_staged_clone(Path(d))
    def test_file_staged(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / SOURCE_PATH
            path.parent.mkdir(parents=True)
            path.write_text(ORIGINAL)
            evidence = patch_staged_clone(Path(d))
            self.assertEqual(evidence["original_oem_methods_modified"], 1)
            self.assertIn("getPackageName()", path.read_text())
    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / SOURCE_PATH
            p.parent.mkdir(parents=True)
            target = Path(d) / "else"
            target.write_text(ORIGINAL)
            p.symlink_to(target)
            with self.assertRaises(GateFailure): patch_staged_clone(Path(d))
    def test_main_side_process_discrimination_model(self):
        pred = lambda process, package: process is not None and process == package
        self.assertTrue(pred("com.yeelight.cherry", "com.yeelight.cherry"))
        self.assertTrue(pred("io.lxsdd.yeelightdj", "io.lxsdd.yeelightdj"))
        self.assertFalse(pred("io.lxsdd.yeelightdj:miot", "io.lxsdd.yeelightdj"))
        self.assertFalse(pred("thirdparty", "io.lxsdd.yeelightdj"))
        self.assertFalse(pred(None, "io.lxsdd.yeelightdj"))

if __name__ == "__main__":
    unittest.main()
