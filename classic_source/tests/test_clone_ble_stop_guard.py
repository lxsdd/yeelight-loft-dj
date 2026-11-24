"""Synthetic positive, negative and quarantined-stage cases for BLE NPE guard."""
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import GateFailure
from clone_ble_stop_guard import patch_ble_stop_text, patch_staged_clone, SOURCE_PATH

BASE = """.class public final Lcom/yeelight/yeelib/managers/y;
.super Ljava/lang/Object;
.method public static synthetic a(Landroid/bluetooth/le/BluetoothLeScanner;Landroid/bluetooth/le/ScanCallback;)V
    .locals 0
    invoke-virtual {p0, p1}, Landroid/bluetooth/le/BluetoothLeScanner;->stopScan(Landroid/bluetooth/le/ScanCallback;)V
    return-void
.end method
.method public static z()V
    .locals 0
    return-void
.end method
"""

class CloneBleStopGuardTests(unittest.TestCase):
    def test_valid_guard_and_unmodified_callback(self):
        changed = patch_ble_stop_text(BASE)
        self.assertIn('if-nez p0, :classic_clone_ble_scanner_present', changed)
        self.assertIn('return-void\n    :classic_clone_ble_scanner_present\n    invoke-virtual {p0, p1}',changed)
        self.assertEqual(changed.split('.method public static z()V')[1],BASE.split('.method public static z()V')[1])
        self.assertEqual(changed.count('->stopScan('),1)
    def test_crlf_and_debug_directives(self):
        fixture = BASE.replace('    invoke-virtual {p0, p1}', '    .line 17\n    invoke-virtual {p0, p1}').replace('\n','\r\n')
        self.assertEqual(patch_ble_stop_text(fixture).count('if-nez p0'),1)
    def test_alternate_whitespace(self):
        fixture = BASE.replace('{p0, p1}', '{ p0 , p1 }')
        self.assertIn('if-nez p0', patch_ble_stop_text(fixture))
    def test_owner_changed_fails(self):
        with self.assertRaisesRegex(GateFailure,'OWNER_MISMATCH'):
            patch_ble_stop_text(BASE.replace('Lcom/yeelight/yeelib/managers/y;', 'Lfake/Y;', 1))
    def test_missing_static_method_fails(self):
        with self.assertRaisesRegex(GateFailure,'STATIC_METHOD_NOT_UNIQUE'):
            patch_ble_stop_text(BASE.replace('public static synthetic', 'public synthetic', 1))
    def test_unexpected_stop_api_fails(self):
        with self.assertRaisesRegex(GateFailure,'STOP_SITE_NOT_UNIQUE'):
            patch_ble_stop_text(BASE.replace('->stopScan(', '->startScan(', 1))
    def test_two_stop_calls_fail(self):
        line='    invoke-virtual {p0, p1}, Landroid/bluetooth/le/BluetoothLeScanner;->stopScan(Landroid/bluetooth/le/ScanCallback;)V\n'
        with self.assertRaisesRegex(GateFailure,'STOP_SITE_NOT_UNIQUE'):
            patch_ble_stop_text(BASE.replace(line,line+line,1))
    def test_bad_receiver_fails(self):
        with self.assertRaisesRegex(GateFailure,'REGISTER_PROVENANCE_UNVERIFIED'):
            patch_ble_stop_text(BASE.replace('{p0, p1}', '{v0, p1}',1))
    def test_bad_callback_fails(self):
        with self.assertRaisesRegex(GateFailure,'REGISTER_PROVENANCE_UNVERIFIED'):
            patch_ble_stop_text(BASE.replace('{p0, p1}', '{p0, v0}',1))
    def test_destination_overwritten_fails(self):
        with self.assertRaisesRegex(GateFailure,'SCANNER_REGISTER_OVERWRITTEN'):
            patch_ble_stop_text(BASE.replace('    invoke-virtual', '    move-object p0, p1\n    invoke-virtual',1))
    def test_monitor_enter_fails(self):
        with self.assertRaisesRegex(GateFailure,'MONITOR_OR_CATCH_UNVERIFIED'):
            patch_ble_stop_text(BASE.replace('    invoke-virtual', '    monitor-enter p0\n    invoke-virtual',1))
    def test_try_catch_fails(self):
        with self.assertRaisesRegex(GateFailure,'MONITOR_OR_CATCH_UNVERIFIED'):
            patch_ble_stop_text(BASE.replace('    return-void\n.end method', '    :try_end\n    .catch Ljava/lang/Exception; {:try_start .. :try_end} :caught\n    return-void\n.end method',1))
    def test_missing_registers_fails(self):
        with self.assertRaisesRegex(GateFailure,'REGISTERS_UNVERIFIED'):
            patch_ble_stop_text(BASE.replace('    .locals 0\n','',1))
    def test_already_applied_fails(self):
        with self.assertRaisesRegex(GateFailure,'ALREADY_APPLIED'):
            patch_ble_stop_text(patch_ble_stop_text(BASE))
    def test_absent_staged_source(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(GateFailure,'FILE_MISSING'):patch_staged_clone(Path(d))
    def test_staged_positive(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/SOURCE_PATH;p.parent.mkdir(parents=True);p.write_text(BASE)
            r=patch_staged_clone(Path(d))
            self.assertTrue(r['null_scanner_skip_only'])
            self.assertFalse(r['runtime_verified'])
            self.assertEqual(p.read_text(),patch_ble_stop_text(BASE))
    def test_staged_symlink_fails(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/SOURCE_PATH;p.parent.mkdir(parents=True)
            target=Path(d)/'t';target.write_text(BASE)
            try:p.symlink_to(target)
            except (OSError,NotImplementedError):self.skipTest('no symlink permission')
            with self.assertRaisesRegex(GateFailure,'FILE_MISSING'):patch_staged_clone(Path(d))
    def test_model_scanner_present_flow(self):
        calls=[]
        class Scanner:
            def stopScan(self,callback): calls.append(callback)
        def stop(scanner,callback):
            if scanner is None:return
            scanner.stopScan(callback)
        stop(None,'c');self.assertEqual(calls,[])
        stop(Scanner(),'c');self.assertEqual(calls,['c'])

if __name__=='__main__':unittest.main()
