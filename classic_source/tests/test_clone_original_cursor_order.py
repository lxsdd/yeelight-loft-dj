"""Private clone-only original device/group cursor DnD production contract."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from classic_first import GateFailure
import clone_original_cursor_order_hook as hook

def adapter(name, device=False):
    owner = "Lcom/yeelight/cherry/ui/fragment/" + name + ";"
    base = hook.BASE
    signature = hook.DEVICE_INIT if device else hook.INIT
    extra = f"""
.method public {hook.INIT}
    .locals 1
    invoke-direct {{p0, p1, p2, p3}}, {owner}->{hook.DEVICE_INIT}
    return-void
.end method
""" if device else ""
    return f"""
.class public {owner}
.super {base}
{extra}
.method public {signature}
    .locals 2
    invoke-direct {{p0, p1, p2}}, {base}->{hook.INIT}
    const/4 v0, 0x0
    return-void
.end method
.method public {hook.ROW_METHOD}
    .locals 4
    invoke-virtual {{p2}}, Landroid/database/Cursor;->getPosition()I
    move-result v0
    if-ltz v0, :exit
    const/4 v1, 0x1
    :exit
    return-void
.end method
"""

DEVICE = adapter("DeviceRecyclerViewAdapter", True)
GROUP = adapter("GroupRecyclerViewAdapter")

class OriginalCursorIntegrationTests(unittest.TestCase):
    def test_private_oem_constructor_and_original_row_callback(self):
        d,g = hook.patch_text(DEVICE,GROUP)
        for original,changed,symbol in (
                (DEVICE,d,"wrapDevices"),(GROUP,g,"wrapGroups")):
            self.assertIn("->" + symbol + "(Landroid/content/Context;Landroid/database/Cursor;)Landroid/database/Cursor;",changed)
            self.assertIn("move-result-object p2",changed)
            self.assertIn("invoke-static/range {p0 .. p2}, " + hook.BIND,changed)
            self.assertEqual(changed.count(hook.BIND),1)
            self.assertIn("->getPosition()I",changed)
            self.assertEqual(changed.count(hook.BASE + "->" + hook.INIT),1)
            self.assertEqual(changed.count("const/4 v0, 0x0"),1)
            with self.assertRaisesRegex(GateFailure,"ALREADY_PATCHED"):
                hook.patch_text(changed, GROUP) if symbol == "wrapDevices" else hook.patch_text(DEVICE,changed)
        self.assertEqual(d.count("->" + hook.DEVICE_INIT),1)

    def test_constructor_register_shapes_no_two_local_limit(self):
        for device in (False, True):
            signature = hook.DEVICE_INIT if device else hook.INIT
            base = DEVICE if device else GROUP
            for shape in ('.locals 0', '.locals 1', '.locals 10',
                          '.locals 16', '.locals 128',
                          '.registers ' + ('4' if device else '3'),
                          '.registers 24'):
                with self.subTest(device=device, shape=shape):
                    edited = base.replace(
                        '.method public ' + signature + '\n    .locals 2\n',
                        '.method public ' + signature + '\n    ' + shape + '\n', 1)
                    if shape in ('.locals 0', '.registers 4', '.registers 3'):
                        edited = edited.replace('    const/4 v0, 0x0\n', '', 1)
                    d, g = hook.patch_text(edited, GROUP) if device else hook.patch_text(DEVICE, edited)
                    adapter = d if device else g
                    self.assertIn(shape, hook.method(adapter, signature).group())
                    self.assertEqual(adapter.count('->' + ('wrapDevices' if device else 'wrapGroups') + '('), 1)
                    self.assertEqual(adapter.count(hook.BIND), 1)

    def test_constructor_invalid_register_forms_fail_closed(self):
        for shape in ('.locals -1', '.locals 65536', '.locals x',
                      '.registers 0', '.registers 1', '.registers 2',
                      '.registers 65536', '.registers 99999'):
            with self.subTest(shape=shape):
                bad = DEVICE.replace('.locals 2', shape, 1)
                with self.assertRaisesRegex(GateFailure, 'REGISTERS_DRIFT'):
                    hook.patch_text(bad, GROUP)
        duplicate = DEVICE.replace('    .locals 2\n',
                                   '    .locals 2\n    .registers 8\n', 1)
        with self.assertRaisesRegex(GateFailure, 'REGISTERS_DRIFT'):
            hook.patch_text(duplicate, GROUP)
        premutation = DEVICE.replace('    invoke-direct {p0, p1, p2}, ' + hook.BASE,
            '    const/4 p2, 0x0\n    invoke-direct {p0, p1, p2}, ' + hook.BASE, 1)
        with self.assertRaisesRegex(GateFailure, 'PRE_SUPER_DRIFT'):
            hook.patch_text(premutation, GROUP)

    def test_crlf_oem_constructor_and_row(self):
        d, g = hook.patch_text(DEVICE.replace('\n', '\r\n'),
                               GROUP.replace('\n', '\r\n'))
        self.assertEqual(d.count('->wrapDevices('), 1)
        self.assertEqual(g.count('->wrapGroups('), 1)
        self.assertEqual(d.count(hook.BIND), 1)
        self.assertEqual(g.count(hook.BIND), 1)

    def test_changed_oem_abi_and_callback_provenance_fail_closed(self):
        invalid = [
            (DEVICE.replace("DeviceRecyclerViewAdapter;","ForeignRecyclerViewAdapter;"),GROUP),
            (DEVICE.replace("Landroid/database/Cursor;Z)V","Landroid/database/Cursor;I)V"),GROUP),
            (DEVICE.replace("invoke-direct {p0, p1, p2}", "invoke-static {p0, p1, p2}"),GROUP),
            (DEVICE.replace("    .locals 2","    .registers 1"),GROUP),
            (DEVICE.replace("    invoke-direct {p0, p1, p2}, " + hook.BASE, "    invoke-static {}, Lother/Unexpected;->go()V\n    invoke-direct {p0, p1, p2}, " + hook.BASE, 1),GROUP),
            (DEVICE.replace("    :exit\n    return-void", "    :exit\n    throw v0"),GROUP),
            (DEVICE.replace("    :exit\n    return-void", "    :exit\n    return v0"),GROUP),
            (DEVICE.replace("    return-void\n.end method\n", "    return-void\n.end method\n.method public "+hook.ROW_METHOD+"\n    .locals 1\n    return-void\n.end method\n",1),GROUP),
            (DEVICE,GROUP.replace("GroupRecyclerViewAdapter;","OtherAdapter;")),
            (DEVICE,GROUP.replace("Landroid/database/Cursor;)V","Landroid/database/Cursor;I)V")),
        ]
        for d,g in invalid:
            with self.subTest(failure=(len(d),len(g))):
                with self.assertRaises(GateFailure): hook.patch_text(d,g)

    def test_private_staging_is_atomic_and_no_device_io(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t)
            for path,source in ((hook.DEVICE,DEVICE),(hook.GROUP,GROUP)):
                file=root/path
                file.parent.mkdir(parents=True,exist_ok=True)
                file.write_text(source)
            bad = root/hook.GROUP
            bad.write_text("bad")
            with self.assertRaises(GateFailure): hook.patch_staged_clone(root)
            self.assertEqual((root/hook.DEVICE).read_text(), DEVICE)
            bad.write_text(GROUP)
            record=hook.patch_staged_clone(root)
            self.assertEqual(record["original_adapter_types"],2)
            self.assertEqual(record["oem_database_or_scene_writes"],0)
            self.assertFalse(record["runtime_ui_verified"])
            with self.assertRaises(GateFailure): hook.patch_staged_clone(root)

    def test_original_adapter_real_build_and_backend_scopes(self):
        build=(ROOT/"build_private_unsigned.py").read_text()
        bridge=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalCursorOrder.java").read_text()
        self.assertIn("patch_original_cursor_order(staged)",build)
        self.assertIn("original_cursor_sort",build)
        self.assertIn("ClassicLocalOrderStore",bridge)
        self.assertIn("ClassicOrderPolicy.Surface.DEVICE_LIST",bridge)
        self.assertIn("ClassicOrderPolicy.Surface.GROUP_LIST",bridge)
        self.assertIn('getColumnIndex("_id")',bridge)
        self.assertIn("List<String> refreshed = scan(delegate)",bridge)
        self.assertIn("sourcePositions.clear()",bridge)
        self.assertIn("position = -1;",bridge)
        self.assertIn("cursor.getLong(id)",bridge)
        self.assertIn("setOnLongClickListener",bridge)
        self.assertIn("setOnDragListener",bridge)
        self.assertIn("startDragAndDrop",bridge)
        self.assertIn("moveRelative(id, -1, revision)",bridge)
        self.assertIn("moveRelative(id, 1, revision)",bridge)
        self.assertIn("moving.revision != revision",bridge)
        self.assertIn("!cursor.validRevision(revision)",bridge)
        self.assertIn("((Handle) old).clear()",bridge)
        self.assertIn("void clear()",bridge)
        self.assertIn("undoLastUserDrop", (ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicLocalOrderStore.java").read_text())
        for forbidden in ("getRemoteDeviceList(", "set_music(", "set_power(",
                          "SQLiteDatabase", "ContentResolver", "getContentResolver(",
                          "sendBroadcast(", "startService(", "delete(", "update("):
            self.assertNotIn(forbidden,bridge)

if __name__ == "__main__":
    unittest.main()
