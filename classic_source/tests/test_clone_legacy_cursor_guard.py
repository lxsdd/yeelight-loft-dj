"""Synthetic positive/negative regressions for absent legacy-provider Cursor."""
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import GateFailure
from clone_legacy_cursor_guard import patch_callback_text, patch_staged_clone, SOURCE_PATH

ORIGINAL = """.class public final Lcom/yeelight/yeelib/managers/v;
.super Ljava/lang/Object;
.method public a(Lcom/yeelight/yeelib/device/base/e;)V
    .locals 2
    invoke-static {p1}, Lcom/yeelight/yeelib/data/DeviceInteractionProvider;->f(Lcom/yeelight/yeelib/device/base/e;)Landroid/database/Cursor;
    move-result-object v0
    invoke-interface {v0}, Landroid/database/Cursor;->moveToFirst()Z
    move-result v1
    invoke-interface {v0}, Landroid/database/Cursor;->close()V
    return-void
.end method
.method public b(Ljava/lang/String;I)V
    .locals 1
    invoke-static {p1, p2}, Lcom/yeelight/yeelib/data/DeviceInteractionProvider;->g(Ljava/lang/String;I)Landroid/database/Cursor;
    move-result-object v0
    return-void
.end method
"""

class LegacyCursorGuardTests(unittest.TestCase):
    def test_null_short_circuit_valid_cursor_unchanged(self):
        patched = patch_callback_text(ORIGINAL)
        self.assertIn("if-nez v0, :classic_private_missing_legacy_cursor_present\n"
                      "    return-void\n"
                      "    :classic_private_missing_legacy_cursor_present", patched)
        self.assertEqual(patched.count("->moveToFirst()Z"), 1)
        self.assertEqual(patched.split(".method public b(")[1], ORIGINAL.split(".method public b(")[1])
        self.assertEqual(patched.count("DeviceInteractionProvider;->f("), 1)
        self.assertNotIn("insert(", patched)
    def test_missing_callback_fails(self):
        with self.assertRaisesRegex(GateFailure, "CALLBACK_NOT_UNIQUE"):
            patch_callback_text(ORIGINAL.replace("public a(", "public x("))
    def test_missing_provider_query_fails(self):
        with self.assertRaisesRegex(GateFailure, "CALL_SHAPE_UNVERIFIED"):
            patch_callback_text(ORIGINAL.replace("->f(", "->other(", 1))
    def test_missing_cursor_use_fails(self):
        with self.assertRaisesRegex(GateFailure, "CALL_SHAPE_UNVERIFIED"):
            patch_callback_text(ORIGINAL.replace("->moveToFirst()Z", "->isClosed()Z"))
    def test_wrong_owner_fails(self):
        with self.assertRaisesRegex(GateFailure, "OWNER_MISMATCH"):
            patch_callback_text(ORIGINAL.replace("Lcom/yeelight/yeelib/managers/v;", "Lwrong/v;", 1))
    def test_double_apply_fails(self):
        with self.assertRaisesRegex(GateFailure, "CALL_SHAPE_UNVERIFIED"):
            patch_callback_text(patch_callback_text(ORIGINAL))
    def test_ambiguous_query_fails(self):
        duplicate = ("    invoke-static {p1}, Lcom/yeelight/yeelib/data/DeviceInteractionProvider;"
                     "->f(Lcom/yeelight/yeelib/device/base/e;)Landroid/database/Cursor;\n")
        with self.assertRaisesRegex(GateFailure, "CALL_SHAPE_UNVERIFIED"):
            patch_callback_text(ORIGINAL.replace("    move-result-object v0\n", duplicate+"    move-result-object v0\n", 1))
    def test_result_register_drift_fails(self):
        with self.assertRaisesRegex(GateFailure, "RESULT_REGISTER_DRIFT"):
            patch_callback_text(ORIGINAL.replace("move-result-object v0", "move-result-object p1", 1))
    def test_missing_file_fails(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(GateFailure, "CALLBACK_FILE_MISSING"):
                patch_staged_clone(Path(d))
    def test_staged_patch(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / SOURCE_PATH
            path.parent.mkdir(parents=True)
            path.write_text(ORIGINAL, encoding="utf-8")
            report = patch_staged_clone(Path(d))
            self.assertEqual(report["original_oem_methods_modified"], 1)
            self.assertFalse(report["legacy_provider_restored"])
            self.assertTrue(report["null_cursor_handled"])
            self.assertEqual(path.read_text(encoding="utf-8"), patch_callback_text(ORIGINAL))
    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / SOURCE_PATH
            path.parent.mkdir(parents=True)
            target = Path(d) / "target"
            target.write_text(ORIGINAL, encoding="utf-8")
            path.symlink_to(target)
            with self.assertRaisesRegex(GateFailure, "CALLBACK_FILE_MISSING"):
                patch_staged_clone(Path(d))
    def test_model_null_cursor_returns_without_read(self):
        class Cursor:
            def __init__(self): self.read_count = 0
            def moveToFirst(self): self.read_count += 1; return True
        def on_device_update(cursor):
            if cursor is None: return False
            return cursor.moveToFirst()
        self.assertFalse(on_device_update(None))
        cursor = Cursor()
        self.assertTrue(on_device_update(cursor))
        self.assertEqual(cursor.read_count, 1)

if __name__ == "__main__":
    unittest.main()
