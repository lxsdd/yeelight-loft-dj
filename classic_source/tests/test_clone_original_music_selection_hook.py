"""Original Classic Music -> GroupMusic device-key witness, never a lamp grant."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from classic_first import GateFailure
import clone_original_music_selection_hook as hook


ORIGINAL = """
.class public final Lcom/yeelight/cherry/ui/fragment/DiscoFragment$c;
.super Ljava/lang/Object;
.method public onClick(Landroid/view/View;)V
    .locals 4
    new-instance v0, Landroid/content/Intent;
    invoke-direct {v0}, Landroid/content/Intent;-><init>()V
    invoke-static {v1}, Lcom/yeelight/cherry/ui/fragment/DiscoFragment;->z(Lcom/yeelight/cherry/ui/fragment/DiscoFragment;)Lcom/yeelight/yeelib/device/base/e;
    move-result-object v2
    invoke-virtual {v2}, Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;
    move-result-object v2
    const-string v1, "oem-selected-key"
    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
    invoke-virtual {p0, v0}, Landroidx/fragment/app/Fragment;->startActivity(Landroid/content/Intent;)V
    return-void
.end method
"""


class OriginalMusicSelectionHookTest(unittest.TestCase):
    def test_exact_original_click_provenance_injected_once_after_result(self):
        changed = hook.patch_text(ORIGINAL)
        self.assertEqual(changed.count(hook.BRIDGE), 1)
        self.assertIn(
            "move-result-object v2\n    invoke-static/range {v2 .. v2}, "
            + hook.BRIDGE, changed
        )
        self.assertEqual(changed.count("->G()Ljava/lang/String;"), 1)
        self.assertEqual(changed.count("->putExtra("), 1)
        self.assertEqual(changed.count("->startActivity("), 1)
        self.assertEqual(changed.count(".locals 4"), 1)
        self.assertLess(changed.index(hook.BRIDGE),
                        changed.index("->putExtra("))
        with self.assertRaisesRegex(GateFailure, "ALREADY_PATCHED"):
            hook.patch_text(changed)

    def test_high_register_keeps_exact_original_register(self):
        changed = hook.patch_text(ORIGINAL.replace(
            "Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;\n"
            "    move-result-object v2",
            "Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;\n"
            "    move-result-object v19", 1
        ))
        self.assertIn("{v19 .. v19}", changed)
        self.assertNotIn(".locals 5", changed)

    def test_debug_line_not_a_different_dalvik_result(self):
        changed = hook.patch_text(ORIGINAL.replace(
            "    move-result-object v2\n", "    .line 77\n    move-result-object v2\n", 1
        ))
        self.assertIn("->observedFromOriginalDevice", changed)

    def test_wrong_or_ambiguous_original_caller_fails_closed(self):
        defects = [
            ("owner", ORIGINAL.replace("DiscoFragment$c;", "DiscoFragment$b;")),
            ("wrong click", ORIGINAL.replace("onClick(Landroid/view/View;)", "run()")),
            ("not original key", ORIGINAL.replace(
                "Lcom/yeelight/yeelib/device/base/c;->G()",
                "Lcom/yeelight/yeelib/device/base/c;->T()")),
            ("wrong original getter", ORIGINAL.replace(
                "DiscoFragment;->z(", "DiscoFragment;->u(")),
            ("no group navigation", ORIGINAL.replace("->startActivity(", "->launch(")),
            ("no original extra", ORIGINAL.replace("->putExtra(", "->setData(")),
            ("ambiguous key", ORIGINAL.replace(
                "    return-void", "    invoke-virtual {v2}, "
                "Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;\n"
                "    move-result-object v2\n    return-void")),
            ("ambiguous handler", ORIGINAL + ORIGINAL.split(".method public onClick")[1]
             .join([".method public onClick", ""])),
            ("wrong result instruction", ORIGINAL.replace(
                "move-result-object v2\n    const-string", "move-result v2\n    const-string"
            ).replace("move-result-object v2\n    const-string",
                      "move-result v2\n    const-string")),
            ("reversed provenance", ORIGINAL.replace(
                "    invoke-virtual {v2}, Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;\n"
                "    move-result-object v2\n", "", 1).replace(
                "    return-void", "    invoke-virtual {v2}, "
                "Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;\n"
                "    move-result-object v2\n    return-void")),
        ]
        for label, defective in defects:
            with self.subTest(label=label):
                with self.assertRaises(GateFailure):
                    hook.patch_text(defective)

    def test_file_mutation_only_after_full_validation(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / hook.SOURCE_PATH
            path.parent.mkdir(parents=True)
            with self.assertRaises(GateFailure):
                hook.patch_staged_clone(Path(d))
            path.write_text(ORIGINAL)
            report = hook.patch_staged_clone(Path(d))
            self.assertTrue(report["original_music_click_preserved"])
            self.assertFalse(report["participant_authorized"])
            self.assertFalse(report["device_id_or_address_exported"])
            self.assertEqual(report["device_or_scene_writes"], 0)
            self.assertEqual(path.read_text().count(hook.BRIDGE), 1)
            with self.assertRaises(GateFailure):
                hook.patch_staged_clone(Path(d))

    def test_private_clone_wiring_and_original_commands_still_blocked(self):
        build = (ROOT / "build_private_unsigned.py").read_text()
        service = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
                   / "ClassicDjService.java").read_text()
        music = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
                 / "ClassicMusicInlineControls.java").read_text()
        provider = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
                    / "ClassicDjEntryProvider.java").read_text()
        assert "patch_original_music_selection(staged)" in build
        assert "smali_report['original_music_selection']" in build
        assert "ClassicOriginalMusicSelection.fresh()" in music
        assert "originalMusicSelectionStatus.setText" in music
        assert "ClassicOriginalMusicSelection.clear();" in provider
        assert "if (!ips.isEmpty())" in service
        assert "OEM-Lampenauswahl nicht verifiziert" in service
        assert "new String[0]" not in music
        assert "Audioquelle und Start/Stopp: oben in Classic" in music
        assert "No OEM device/room identity" in service
        assert "set_power" not in hook.BRIDGE

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK required for executable selection-contract regression")
    def test_actual_jdk_selection_expiry_match_and_reset(self):
        source = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui" \
                    / "ClassicOriginalMusicSelection.java"
        harness = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
public final class OriginalMusicSelectionHarness {
  static void check(boolean result, String what) {
    if (!result) throw new AssertionError(what);
  }
  public static void main(String[] args) {
    ClassicOriginalMusicSelection.clear();
    check(!ClassicOriginalMusicSelection.freshAt(100), "empty");
    ClassicOriginalMusicSelection.observeAt("original-device-key", 100);
    check(ClassicOriginalMusicSelection.freshAt(100), "fresh");
    check(ClassicOriginalMusicSelection.matchesAt("original-device-key", 100),
          "same original identity");
    check(!ClassicOriginalMusicSelection.matchesAt("second-device", 100),
          "foreign identity");
    check(ClassicOriginalMusicSelection.freshAt(
          100 + ClassicOriginalMusicSelection.FRESH_MS), "inclusive boundary");
    check(!ClassicOriginalMusicSelection.freshAt(
          101 + ClassicOriginalMusicSelection.FRESH_MS), "expiry");
    check(!ClassicOriginalMusicSelection.matchesAt("original-device-key", 101),
          "expired identity");
    ClassicOriginalMusicSelection.observeAt("new-device", 500);
    check(ClassicOriginalMusicSelection.freshAt(500), "new context");
    ClassicOriginalMusicSelection.observeAt(" ", 510);
    check(!ClassicOriginalMusicSelection.freshAt(510), "invalid clears");
    ClassicOriginalMusicSelection.observeAt("new-device", 1000);
    check(!ClassicOriginalMusicSelection.freshAt(999), "clock rollback");
    ClassicOriginalMusicSelection.observeAt("x\ninj", 1000);
    check(!ClassicOriginalMusicSelection.freshAt(1000), "control rejected");
    ClassicOriginalMusicSelection.observeAt("x".repeat(193), 1000);
    check(!ClassicOriginalMusicSelection.freshAt(1000), "oversize rejected");
    ClassicOriginalMusicSelection.observeAt("new-device", 1100);
    ClassicOriginalMusicSelection.clear();
    check(!ClassicOriginalMusicSelection.freshAt(1100), "host destroyed");
  }
}
""".replace('"x".repeat(193)', 'new String(new char[193]).replace("\\0", "x")')
        with tempfile.TemporaryDirectory() as d:
            helper = Path(d) / "OriginalMusicSelectionHarness.java"
            helper.write_text(harness)
            subprocess.run(
                ["javac", "--release", "8", "-d", d, str(source), str(helper)],
                check=True, timeout=25, capture_output=True, text=True
            )
            subprocess.run(
                ["java", "-cp", d,
                 "io.github.lxsdd.yeelightclassicdj.ui.OriginalMusicSelectionHarness"],
                check=True, timeout=25, capture_output=True, text=True
            )


if __name__ == "__main__":
    unittest.main()
