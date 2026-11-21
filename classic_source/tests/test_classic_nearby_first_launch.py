"""Clone first-launch permission route: positive and negative OEM-free tests."""
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SRC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SRC))
import classic_first as c
from test_classic_first import MANIFEST

GATE = (SRC / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicNearbyGateActivity.java").read_text("utf-8")


class NearbyGateContractTest(unittest.TestCase):
    def prepare(self, text=MANIFEST, *, identity_control=False):
        with tempfile.TemporaryDirectory() as root:
            manifest = Path(root) / "AndroidManifest.xml"
            manifest.write_text(text, encoding="utf-8")
            result = c.patch_manifest(manifest, identity_control=identity_control)
            return ET.parse(manifest).getroot(), result

    def test_clone_preserves_original_activity_but_intercepts_launcher(self):
        root, report = self.prepare()
        app = root.find("application")
        original = app.find("activity")
        self.assertEqual(original.get(c.A("name")), c.OLD_PACKAGE + ".MainActivity")
        self.assertFalse(any(q.get(c.A("name")) == "android.intent.category.LAUNCHER"
                         for f in original.findall("intent-filter")
                         for q in f.findall("category")))
        gate = [a for a in app.findall("activity")
                if a.get(c.A("name")) == c.BRIDGE_NEARBY_GATE]
        self.assertEqual(len(gate), 1)
        self.assertEqual(gate[0].find("meta-data").get(c.A("value")),
                         c.OLD_PACKAGE + ".MainActivity")
        self.assertEqual(gate[0].get(c.A("exported")), "true")
        self.assertEqual(report["nearby_first_launch_gate"],
                         "VERIFIED_ONE_ORIGINAL_LAUNCHER")
        perms = {p.get(c.A("name")) for p in root.findall("uses-permission")}
        for item in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES"):
            self.assertIn("android.permission." + item, perms)

    def test_original_identity_control_keeps_launcher_and_does_not_add_gate(self):
        root, report = self.prepare(identity_control=True)
        self.assertEqual(report["nearby_first_launch_gate"], "IDENTITY_CONTROL_UNCHANGED")
        self.assertFalse(any(a.get(c.A("name")) == c.BRIDGE_NEARBY_GATE
            for a in root.find("application").findall("activity")))

    def test_multiple_launcher_filters_fail_closed(self):
        double = MANIFEST.replace(
            '    <activity-alias android:name=".Alias"',
            '    <activity android:name=".Another"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>\n'
            '    <activity-alias android:name=".Alias"')
        with self.assertRaisesRegex(c.GateFailure, "LAUNCHER_NOT_UNIQUE"):
            self.prepare(double)

    def test_missing_launcher_fails_closed(self):
        with self.assertRaisesRegex(c.GateFailure, "LAUNCHER_NOT_UNIQUE"):
            self.prepare(MANIFEST.replace(
                '<category android:name="android.intent.category.LAUNCHER"/>', ""))

    def test_nonisolated_launcher_filter_fails_closed(self):
        with self.assertRaisesRegex(c.GateFailure, "FILTER_NOT_ISOLATED"):
            self.prepare(MANIFEST.replace(
                '<action android:name="android.intent.action.MAIN"/>',
                '<action android:name="android.intent.action.VIEW"/>'))

    def test_actual_production_gate_requires_explicit_grant_not_automatic_device_access(self):
        self.assertIn("requestPermissions(permissions, REQUEST);", GATE)
        self.assertIn("Manifest.permission.BLUETOOTH_CONNECT", GATE)
        self.assertIn("Manifest.permission.NEARBY_WIFI_DEVICES", GATE)
        self.assertIn("if (launched || !nearbyGranted()", GATE)
        self.assertIn("if (nearbyGranted()) openOriginal();", GATE)
        for forbidden in ("grantRuntimePermission", "startDiscovery(",
                          "startLeScan(", "set_music", "set_power", "sendBroadcast("):
            self.assertNotIn(forbidden, GATE)


if __name__ == "__main__":
    unittest.main()
