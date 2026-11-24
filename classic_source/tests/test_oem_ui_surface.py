"""No OEM/hardware fixtures: owner-only UI hierarchy privacy regression."""
from pathlib import Path
import unittest
from private_ci.oem_ui_surface import summarize, UiSurfaceBlocked

DUMP = ('UI hierchary dumped to: /dev/tty\n'
        '<hierarchy rotation="0">'
        '<node package="com.yeelight.cherry" enabled="true" clickable="true" '
        'text="Private user email@example.net" content-desc="secret TOKEN=abcdef" '
        'resource-id="com.yeelight.cherry:id/private_info">'
        '<node package="com.yeelight.cherry" enabled="true" clickable="false" '
        'scrollable="true"/>'
        '</node></hierarchy>')
class UiSurfaceTests(unittest.TestCase):
    def test_no_private_labels_or_ids_ever_returned(self):
        result = summarize(DUMP)
        self.assertEqual(result["hierarchy_node_count"], 2)
        self.assertEqual(result["original_package_node_count"], 2)
        self.assertEqual(result["enabled_clickable_node_count"], 1)
        self.assertEqual(result["scrollable_node_count"], 1)
        self.assertTrue(result["original_ui_hierarchy_visible"])
        self.assertFalse(result["original_music_screen_reached"])
        self.assertFalse(result["account_state_examined"])
        self.assertFalse(result["raw_hierarchy_published"])
        for secret in ("Private user", "example.net", "abcdef", "private_info",
                       "com.yeelight.cherry:id/"):
            self.assertNotIn(secret, str(result))

    def test_foreign_package_does_not_prove_original_ui(self):
        data = DUMP.replace('package="com.yeelight.cherry"',
                            'package="com.example.foreign"')
        result = summarize(data)
        self.assertFalse(result["original_ui_hierarchy_visible"])
        self.assertEqual(result["original_package_node_count"], 0)
        self.assertFalse(result["original_music_screen_reached"])

    def test_malformed_and_oversized_ui_never_qualifies(self):
        for raw in ("", "not XML", "<hierarchy>", "<hierarchy></hierarchy>",
                    "<hierarchy><node></hierarchy>", "<!DOCTYPE x><hierarchy>"
                    "<node/></hierarchy>", "A"*2_000_001):
            with self.subTest(raw=raw[:20]):
                with self.assertRaises(UiSurfaceBlocked):
                    summarize(raw)

    def test_stock_baseline_observes_only_after_resumed_and_no_interaction(self):
        source=(Path(__file__).resolve().parents[1]/"private_ci/private_original_baseline.py").read_text()
        self.assertIn("from private_ci.oem_ui_surface import summarize as ui_surface_summary", source)
        self.assertIn('["shell", "uiautomator", "dump", "/dev/tty"]', source)
        self.assertIn('report["original_ui_surface"] = surface', source)
        self.assertIn("if resumed:", source)
        self.assertNotIn('["shell", "input", "tap"', source)
        self.assertNotIn('["shell", "am", "start", "-a"', source)

if __name__ == "__main__":
    unittest.main()
