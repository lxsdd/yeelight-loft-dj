"""Run the production original GroupMusic identity witness, never transport."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
WITNESS = UI / "ClassicOriginalGroupMemberWitness.java"
SELECTOR = UI / "ClassicOriginalMusicSelection.java"
POLICY = UI / "ClassicOrderPolicy.java"
INLINE = UI / "ClassicMusicInlineControls.java"

class OriginalGroupIdentityTests(unittest.TestCase):
    def test_production_wiring_is_read_only_and_oem_hook_unchanged(self):
        ui = INLINE.read_text()
        selector = SELECTOR.read_text()
        witness = WITNESS.read_text()
        self.assertIn("originalGroupVerifiedIdentityCount()", ui)
        self.assertIn("ClassicOriginalMusicSelection.djIntentCount()", ui)
        self.assertIn("LAN-/Lampenfreigabe weiterhin gesperrt.", ui)
        self.assertIn("originalMemberKeys = ClassicOriginalGroupMemberWitness.inspect(members)", selector)
        self.assertIn("Original-Classic-Gruppenliste:", ui)
        self.assertIn('com.yeelight.yeelib.device.base.e', witness)
        self.assertIn('getMethod("T")', witness)
        self.assertNotIn("set_power", witness)
        self.assertNotIn("set_rgb", witness)
        self.assertNotIn("start_music", witness)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_real_java_member_identification_refresh_expiry_and_denial(self):
        fixture = """
package com.yeelight.yeelib.device.base;
public class e {
    private final String key;
    public e(String key) { this.key=key; }
    public String T() { return key; }
}
"""
        harness = """
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.*;
import com.yeelight.yeelib.device.base.e;
public final class GroupKeysRuntimeHarness {
  static void ok(boolean result, String label) { if(!result) throw new AssertionError(label); }
  static e m(String id) { return new e(id); }
  public static void main(String[] args) {
    ClassicOriginalMusicSelection.clear();
    ClassicOriginalMusicSelection.observeGroupEntryAt("group",100);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("a")),110);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(110)==-1,"no prior click");
    ClassicOriginalMusicSelection.observeAt("group",200);
    ClassicOriginalMusicSelection.observeGroupEntryAt("wrong",210);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("a")),220);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(220)==-1,"foreign entry");
    ClassicOriginalMusicSelection.observeGroupEntryAt("group",230);
    List<e> current = new ArrayList<>(Arrays.asList(m("lamp-1"),m("lamp-2"),m("lamp-3")));
    ClassicOriginalMusicSelection.observeGroupMembersAt(current,240);
    ok(ClassicOriginalMusicSelection.groupMemberCountAt(240)==3,"real count");
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(240)==3,"verified inherited T keys");
    ok(ClassicOriginalMusicSelection.verifiedDisplayKeysAt(240).equals(Arrays.asList(
       "lamp-1","lamp-2","lamp-3")),"actual order");
    ok(ClassicOriginalMusicSelection.djIntentCountAt(240)==0,"no auto-opt-in");
    ok(!ClassicOriginalMusicSelection.markDjIntentAt("foreign",true,240),"foreign blocked");
    ok(ClassicOriginalMusicSelection.markDjIntentAt("lamp-1",true,240),"exact member opt-in");
    ok(ClassicOriginalMusicSelection.djIntentCountAt(240)==1,"one");
    ok(ClassicOriginalMusicSelection.markAllDjIntentAt(true,241),"all explicit");
    ok(ClassicOriginalMusicSelection.djIntentCountAt(241)==3,"all three");
    current.clear();
    ok(ClassicOriginalMusicSelection.verifiedDisplayKeysAt(241).size()==3,
       "source list not retained");
    List<String> copy=ClassicOriginalMusicSelection.verifiedDisplayKeysAt(241);
    copy.clear();
    ok(ClassicOriginalMusicSelection.verifiedDisplayKeysAt(242).size()==3,
       "returned display keys are defensive");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("lamp-2"),m("lamp-1")),250);
    ok(ClassicOriginalMusicSelection.verifiedDisplayKeysAt(250).equals(Arrays.asList(
       "lamp-2","lamp-1")),"refresh is exact");
    ok(ClassicOriginalMusicSelection.djIntentCountAt(250)==2,"removed member revoked");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("dup"),m("dup")),260);
    ok(ClassicOriginalMusicSelection.groupMemberCountAt(260)==2,"invalid keys retain count");
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(260)==-1,"duplicate keys fail closed");
    ok(ClassicOriginalMusicSelection.djIntentCountAt(260)==0,"ambiguous refresh revokes all");
    ok(!ClassicOriginalMusicSelection.markAllDjIntentAt(true,260),"cannot enroll ambiguity");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("a"),"foreign"),270);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(270)==-1,"foreign OEM class");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("a"),m(" ")),280);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(280)==-1,"blank key");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("a"),m("b\u0001")),290);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(290)==-1,"control chars");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Collections.emptyList(),300);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(300)==0,"valid empty group");
    List<e> tooLarge=new ArrayList<>();
    for (int i=0;i<513;i++) tooLarge.add(m("lamp"+i));
    ClassicOriginalMusicSelection.observeGroupMembersAt(tooLarge,310);
    ok(ClassicOriginalMusicSelection.groupMemberCountAt(310)==513,"count not silently truncated");
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(310)==-1,"bounded identities");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("a"),m("b")),320);
    ok(ClassicOriginalMusicSelection.markAllDjIntentAt(true,320),"select before Back");
    ClassicOriginalMusicSelection.originalGroupClosed();
    ok(ClassicOriginalMusicSelection.djIntentCountAt(321)==2,"intent count on Music return");
    ok(!ClassicOriginalMusicSelection.markDjIntentAt("a",false,321),"closed activity denied");
    ok(ClassicOriginalMusicSelection.groupMemberCountAt(321)==2,"readonly count after back");
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(321)==-1,"keys purged after close");
    ok(ClassicOriginalMusicSelection.verifiedDisplayKeysAt(321).isEmpty(),"no ID leak");
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("spoof")),322);
    ok(ClassicOriginalMusicSelection.groupMemberCountAt(323)==2,"late callback ignored");
    ClassicOriginalMusicSelection.observeGroupEntryAt("group",330);
    ClassicOriginalMusicSelection.observeGroupMembersAt(Arrays.asList(m("new")),340);
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(341)==1,"reopen");
    ok(ClassicOriginalMusicSelection.djIntentCountAt(341)==0,"no carry into new group entry");
    ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(341+
       ClassicOriginalMusicSelection.FRESH_MS)==-1,"expiry");
    ClassicOriginalMusicSelection.clear();
    ok(ClassicOriginalMusicSelection.verifiedDisplayKeysAt(900000).isEmpty(),"clear");
    System.out.println("CLASSIC_ORIGINAL_GROUP_IDENTITY_RUNTIME_PASS");
  }
}
"""
        with tempfile.TemporaryDirectory() as d:
            base=Path(d)
            stub=base/"com/yeelight/yeelib/device/base/e.java"
            stub.parent.mkdir(parents=True)
            stub.write_text(fixture)
            main=base/"GroupKeysRuntimeHarness.java"
            main.write_text(harness)
            subprocess.run(["javac","--release","8","-d",d,
                str(POLICY),str(WITNESS),str(SELECTOR),str(stub),str(main)],
                check=True,capture_output=True,text=True,timeout=30)
            run=subprocess.run(["java","-cp",d,
                "io.github.lxsdd.yeelightclassicdj.ui.GroupKeysRuntimeHarness"],
                check=True,capture_output=True,text=True,timeout=30)
            self.assertIn("CLASSIC_ORIGINAL_GROUP_IDENTITY_RUNTIME_PASS",run.stdout)
if __name__ == "__main__":
    unittest.main()
