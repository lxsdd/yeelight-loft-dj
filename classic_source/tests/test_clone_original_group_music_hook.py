"""Tests real original group-list observation without selected-target authority."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from classic_first import GateFailure
import clone_original_group_music_hook as group

ENTRY = """
.class public final Lcom/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity;
.super Ljava/lang/Object;
.method protected onCreate(Landroid/os/Bundle;)V
    .locals 8
    invoke-virtual {v0, v1}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v7
    invoke-virtual {v2, v7}, Lcom/yeelight/yeelib/managers/YeelightDeviceManager;->j0(Ljava/lang/String;)Lcom/yeelight/yeelib/device/base/c;
    move-result-object v3
    invoke-direct {p0}, Lcom/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity;->a0()V
    return-void
.end method
.method private a0()V
    .locals 24
    invoke-virtual {v0}, Lcom/yeelight/yeelib/device/c;->J1()Ljava/util/List;
    move-result-object v23
    invoke-interface {v23}, Ljava/util/List;->size()I
    move-result v1
    invoke-virtual {v0}, Lcom/yeelight/yeelib/device/c;->J1()Ljava/util/List;
    move-result-object v2
    invoke-virtual {v0}, Lcom/yeelight/yeelib/device/c;->J1()Ljava/util/List;
    move-result-object v3
    invoke-virtual {v0}, Lcom/yeelight/yeelib/device/c;->J1()Ljava/util/List;
    move-result-object v4
    return-void
.end method
"""


class OriginalGroupObserverTests(unittest.TestCase):
    def test_exact_original_group_activity_observed_without_mutating_calls(self):
        changed = group.patch_text(ENTRY)
        self.assertEqual(changed.count(group.ENTRY_BRIDGE), 1)
        self.assertEqual(changed.count(group.MEMBERS_BRIDGE), 1)
        self.assertIn("move-result-object v7\n    invoke-static/range {v7 .. v7}, ",
                      changed)
        self.assertIn("move-result-object v23\n    invoke-static/range {v23 .. v23}, ",
                      changed)
        self.assertEqual(changed.count("->J1()Ljava/util/List;"), 4)
        self.assertEqual(changed.count("->j0(Ljava/lang/String;)"), 1)
        self.assertEqual(changed.count(".locals 24"), 1)
        with self.assertRaises(GateFailure):
            group.patch_text(changed)

    def test_changed_original_instructions_fail_closed(self):
        defects = [
            ENTRY.replace("GroupMusicModeManagerActivity;", "OtherManagerActivity;"),
            ENTRY.replace("onCreate(Landroid/os/Bundle;)", "onStart()"),
            ENTRY.replace("->getStringExtra(", "->getIntExtra("),
            ENTRY.replace("->j0(Ljava/lang/String;)", "->other(Ljava/lang/String;)"),
            ENTRY.replace("->a0()V", "->b0()V"),
            ENTRY.replace("->J1()Ljava/util/List;", "->notMembers()Ljava/util/List;", 1),
            ENTRY.replace("move-result-object v7", "move-result v7"),
            ENTRY.replace("move-result-object v23", "move-result v23"),
            ENTRY.replace("    return-void\n.end method",
                          "    invoke-virtual {v0}, Lcom/yeelight/yeelib/device/c;->J1()Ljava/util/List;\n    move-result-object v3\n    return-void\n.end method", 1),
            ENTRY.replace("    move-result-object v7",
                          "    move-result-object v7\n    invoke-static {v7}, Lcom/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity;->a0()V", 1)
                 .replace("    invoke-direct {p0}, Lcom/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity;->a0()V", "    nop"),
        ]
        for bad in defects:
            with self.subTest(bad=bad[:50]):
                with self.assertRaises(GateFailure):
                    group.patch_text(bad)

    def test_read_only_builder_wiring_and_oem_lifecycle(self):
        build = (ROOT / "build_private_unsigned.py").read_text()
        ui = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
              / "ClassicMusicInlineControls.java").read_text()
        provider = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
                    / "ClassicDjEntryProvider.java").read_text()
        service = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
                   / "ClassicDjService.java").read_text()
        self.assertIn("patch_original_group(staged)", build)
        self.assertIn("smali_report['original_group_membership']", build)
        self.assertIn("ClassicOriginalMusicSelection.originalGroupMemberCount()", ui)
        self.assertIn("keine bestätigte Auswahl", ui)
        self.assertIn("ClassicOriginalMusicSelection.originalGroupClosed()", provider)
        self.assertIn("if (!ips.isEmpty())", service)
        self.assertIn("new String[0]", ui)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / group.SOURCE_PATH
            path.parent.mkdir(parents=True)
            with self.assertRaises(GateFailure):
                group.patch_staged_clone(Path(d))
            path.write_text(ENTRY)
            report = group.patch_staged_clone(Path(d))
            self.assertFalse(report["participant_authorized"])
            self.assertEqual(report["device_or_scene_writes"], 0)
            self.assertEqual(report["original_oem_methods_modified"], 2)
            with self.assertRaises(GateFailure):
                group.patch_staged_clone(Path(d))
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / group.SOURCE_PATH
            path.parent.mkdir(parents=True)
            target = Path(d) / "target.smali"
            target.write_text(ENTRY)
            path.symlink_to(target)
            with self.assertRaises(GateFailure):
                group.patch_staged_clone(Path(d))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                         "JDK required for true read-only group state test")
    def test_real_production_java_identity_and_expiry(self):
        source = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui" / "ClassicOriginalMusicSelection.java"
        harness = """
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.*;
public final class OriginalGroupHarness {
  static void check(boolean v, String why) {
    if (!v) throw new AssertionError(why);
  }
  public static void main(String[] args) {
    ClassicOriginalMusicSelection.clear();
    ClassicOriginalMusicSelection.observeGroupEntryAt("group", 100);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("a","b"), 110);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(110) == -1, "no prior original click");
    ClassicOriginalMusicSelection.observeAt("group", 200);
    ClassicOriginalMusicSelection.observeGroupEntryAt("other", 210);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("a","b"), 220);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(220) == -1, "foreign group rejected");
    ClassicOriginalMusicSelection.observeGroupEntryAt("group", 230);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("a","b","c","d","e"), 240);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(240) == 5, "actual list count");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Collections.emptyList(), 250);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(250) == 0, "empty is real");
    ClassicOriginalMusicSelection.observeGroupMembersAt(null, 260);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(260) == -1, "null list unknown");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("a","b"), 270);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(270) == 2, "list refresh");
    ClassicOriginalMusicSelection.originalGroupClosed();
    check(ClassicOriginalMusicSelection.groupMemberCountAt(280) == 2, "last-seen count available on Music return");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("spoof"), 281);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(281) == 2, "closed group cannot update snapshot");
    check(ClassicOriginalMusicSelection.freshAt(280), "original click still observed");
    ClassicOriginalMusicSelection.observeGroupEntryAt("group", 290);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("a"), 300);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(300) == 1, "reopen");
    check(ClassicOriginalMusicSelection.groupMemberCountAt(301 + ClassicOriginalMusicSelection.FRESH_MS) == -1, "group expired");
    ClassicOriginalMusicSelection.observeAt("another", 900000);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(900000) == -1, "changed original click invalidates group");
    ClassicOriginalMusicSelection.observeGroupEntryAt("another", 900010);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList("a"), 900020);
    check(ClassicOriginalMusicSelection.groupMemberCountAt(900019) == -1, "clock regression");
    ClassicOriginalMusicSelection.clear();
    check(ClassicOriginalMusicSelection.groupMemberCountAt(900030) == -1, "owner lifecycle clear");
  }
}
"""
        with tempfile.TemporaryDirectory() as d:
            helper = Path(d) / "OriginalGroupHarness.java"
            helper.write_text(harness)
            subprocess.run(["javac","--release","8","-d",d,str(source),str(helper)],
                           check=True, timeout=25, capture_output=True, text=True)
            subprocess.run(["java","-cp",d,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalGroupHarness"],
                check=True, timeout=25, capture_output=True, text=True)
