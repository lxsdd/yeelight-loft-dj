"""Fail-closed regressions for a provider query missing its Cursor in private clone."""
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import GateFailure
from clone_legacy_cursor_guard import patch_callback_text, patch_staged_clone, SOURCE_PATH

BASE = """.class public final Lcom/yeelight/yeelib/managers/v;
.super Ljava/lang/Object;
.method public final a(Lcom/yeelight/yeelib/device/base/e;)V
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
    return-void
.end method
"""

class GuardTests(unittest.TestCase):
    def changed(self, original=BASE):
        updated=patch_callback_text(original)
        self.assertEqual(updated.count('if-nez v0, :classic_private_missing_legacy_cursor_present'),1)
        self.assertEqual(updated.count('Landroid/database/Cursor;->moveToFirst()Z'),1)
        self.assertIn('invoke-interface {v0}, Landroid/database/Cursor;->close()V',updated)
        self.assertEqual(updated.split('.method public b(')[1],original.split('.method public b(')[1])
        self.assertEqual(updated.count('DeviceInteractionProvider;->f('),1)
        self.assertEqual(updated.count('return-void'),original.count('return-void')+1)
        return updated
    def test_base(self):self.changed()
    def test_line_between_query_and_result(self):
        self.changed(BASE.replace('    move-result-object v0\n', '    .line 143\n    move-result-object v0\n',1))
    def test_comment_and_local_between(self):
        self.changed(BASE.replace('    move-result-object v0\n', '    # private-debug marker\n    .local v1, "cursor":Landroid/database/Cursor;\n\n    move-result-object v0\n',1))
    def test_crlf(self):
        self.changed(BASE.replace('\n','\r\n'))
    def test_not_adjacent_real_opcode(self):
        bad=BASE.replace('    move-result-object v0', '    const/4 v0, 0x0\n    move-result-object v0',1)
        with self.assertRaisesRegex(GateFailure,'RESULT_NOT_ADJACENT_EXECUTABLE'):patch_callback_text(bad)
    def test_label_before_result(self):
        bad=BASE.replace('    move-result-object v0', '    :not_proven\n    move-result-object v0',1)
        with self.assertRaisesRegex(GateFailure,'RESULT_NOT_ADJACENT_EXECUTABLE'):patch_callback_text(bad)
    def test_missing_query(self):
        with self.assertRaisesRegex(GateFailure,'QUERY_SITE_NOT_UNIQUE'):patch_callback_text(BASE.replace('->f(','->z(',1))
    def test_ambiguous_query(self):
        with self.assertRaisesRegex(GateFailure,'QUERY_SITE_NOT_UNIQUE'):patch_callback_text(BASE.replace('    move-result-object v0\n', '    invoke-static {p1}, Lcom/yeelight/yeelib/data/DeviceInteractionProvider;->f(Lcom/yeelight/yeelib/device/base/e;)Landroid/database/Cursor;\n    move-result-object v0\n',1))
    def test_missing_move_to_first(self):
        with self.assertRaisesRegex(GateFailure,'DEREFERENCE_SHAPE_UNVERIFIED'):patch_callback_text(BASE.replace('->moveToFirst()Z','->isClosed()Z'))
    def test_wrong_owner(self):
        with self.assertRaisesRegex(GateFailure,'OWNER_MISMATCH'):patch_callback_text(BASE.replace('Lcom/yeelight/yeelib/managers/v;','Lwrong/v;',1))
    def test_wrong_callback(self):
        with self.assertRaisesRegex(GateFailure,'CALLBACK_NOT_UNIQUE'):patch_callback_text(BASE.replace('public final a(','public final z(',1))
    def test_double_apply(self):
        with self.assertRaisesRegex(GateFailure,'DOUBLE_PATCH_BLOCKED'):patch_callback_text(self.changed())
    def test_monitor_enter(self):
        with self.assertRaisesRegex(GateFailure,'MONITOR_FLOW_UNVERIFIED'):patch_callback_text(BASE.replace('    .locals 2','    .locals 2\n    monitor-enter p0',1))
    def test_register_declaration_missing(self):
        with self.assertRaisesRegex(GateFailure,'REGISTER_DECLARATION_MISSING'):patch_callback_text(BASE.replace('    .locals 2','    .local v0, "cursor":Landroid/database/Cursor;',1))
    def test_missing_file(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(GateFailure,'CALLBACK_FILE_MISSING'):patch_staged_clone(Path(d))
    def test_staged_file(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/SOURCE_PATH;p.parent.mkdir(parents=True);p.write_text(BASE)
            report=patch_staged_clone(Path(d))
            self.assertEqual(report['original_oem_methods_modified'],1)
            self.assertFalse(report['legacy_provider_restored'])
            self.assertEqual(p.read_text(),self.changed())
    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/SOURCE_PATH;p.parent.mkdir(parents=True)
            dest=Path(d)/'some-other-file';dest.write_text(BASE)
            try:p.symlink_to(dest)
            except (OSError,NotImplementedError):self.skipTest('symlink unavailable')
            with self.assertRaisesRegex(GateFailure,'CALLBACK_FILE_MISSING'):patch_staged_clone(Path(d))
    def test_empty_cursor_branch_model(self):
        touched=[]
        def original_valid(cursor):
            if cursor is None: return False
            touched.append('moveToFirst')
            return cursor.moveToFirst()
        class Cursor:
            def moveToFirst(self):return True
        self.assertFalse(original_valid(None));self.assertEqual(touched,[])
        self.assertTrue(original_valid(Cursor()));self.assertEqual(touched,['moveToFirst'])

if __name__=='__main__':unittest.main()
