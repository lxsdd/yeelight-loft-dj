"""Run real Original-Classic selection and user-visible light-state code, no devices."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
UI=ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
REPORT=UI/"ClassicOriginalLightReport.java"
SELECT=UI/"ClassicOriginalMusicSelection.java"
GROUP=UI/"ClassicOriginalGroupMemberOrder.java"
INLINE=UI/"ClassicMusicInlineControls.java"
OEM="""package com.yeelight.yeelib.device.base;
public class e {
    private final String id;
    public e(String id) { this.id=id; }
    public String T() { return id; }
}
"""
HARNESS=r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import com.yeelight.yeelib.device.base.e;
import java.util.*;
public class OriginalLightReportHarness {
    static void ok(boolean b,String what) {
        if (!b) throw new AssertionError(what);
    }
    static List<e> lamps(int count) {
        List<e> out=new ArrayList<>();
        for(int i=0;i<count;i++) out.add(new e("opaque-"+i));
        return out;
    }
    public static void main(String[] args) {
        for (int size : new int[]{1, 4, 14, 32, 128, 512}) {
            ok(ClassicOriginalLightReport.confirmedProgress(
                 "ORIGINAL_CONNECTING_1_OF_" + size + "_LAMPS",size)==1,
                 "exact first confirmed reverse channel");
            ok(ClassicOriginalLightReport.confirmedProgress(
                 "ORIGINAL_CONNECTING_" + size + "_OF_" + size + "_LAMPS",size)==size,
                 "complete progress is not complete cohort ACK");
            ok(ClassicOriginalLightReport.connecting(size,size).contains(
                 size + " von " + size + " Music-Verbindungen bestätigt"),
                 "bounded progress for dynamic fleets");
            ok(ClassicOriginalLightReport.header(
                 ClassicOriginalLightReport.connecting(1,size),size,false,1)
                 .contains("1/" + size + " Verbindungen"),
                 "collapsed original music view must reveal real progress");
            for (String bad : new String[]{
                "ORIGINAL_CONNECTING_0_OF_" + size + "_LAMPS",
                "ORIGINAL_CONNECTING_" + (size+1) + "_OF_" + size + "_LAMPS",
                "ORIGINAL_CONNECTING_01_OF_" + size + "_LAMPS",
                "ORIGINAL_CONNECTING_-1_OF_" + size + "_LAMPS",
                "ORIGINAL_CONNECTING_1_OF_" + (size+1) + "_LAMPS",
                "ORIGINAL_CONNECTING_1_OF_" + size + "_LAMPS_192.168.1.2",
                "DJ_ACTIVE_" + size + "_LAMPS"}) {
                ok(ClassicOriginalLightReport.confirmedProgress(bad,size)==-1,
                   "invalid or foreign progress must be ignored");
            }
        }
        ok(ClassicOriginalLightReport.active(14,false).contains("14 bestätigte Lampen"),
           "full 14-lamp readout, not four");
        ok(ClassicOriginalLightReport.active(32,true).contains("32 bestätigte Lampen"),
           "dynamic cohort above 14");
        ok(ClassicOriginalLightReport.starting(14).contains("werden geprüft") &&
           !ClassicOriginalLightReport.starting(14).contains("bestätigte"),
           "proposed is not owned");
        ok(ClassicOriginalLightReport.uncertain().contains("nicht bestätigt") &&
           !ClassicOriginalLightReport.uncertain().contains("sicher beendet"),
           "unknown STOP must not promise safe cleanup");
        ok(ClassicOriginalLightReport.header(
             ClassicOriginalLightReport.active(14,false),14,true).contains("14 Lampen"),
           "collapsed Original Music UI shows owned count");
        ok(ClassicOriginalMusicSelection.CHANGE_GROUP == ClassicOriginalLightReport.GROUP_CHANGED &&
           ClassicOriginalMusicSelection.CHANGE_ORDER == ClassicOriginalLightReport.ORDER_CHANGED &&
           ClassicOriginalMusicSelection.CHANGE_SELECTION == ClassicOriginalLightReport.SELECTION_CHANGED &&
           ClassicOriginalMusicSelection.CHANGE_DEVICE_ACTION == ClassicOriginalLightReport.DEVICE_ACTION &&
           ClassicOriginalMusicSelection.CHANGE_CONTEXT == ClassicOriginalLightReport.CONTEXT_CHANGED &&
           ClassicOriginalMusicSelection.CHANGE_LAN_RECHECKED == ClassicOriginalLightReport.LAN_RECHECKED,
           "selection and UI reason codes are identical");
        for (int reason=0;reason<=12;reason++) {
            String message=ClassicOriginalLightReport.stopped(reason);
            ok(!message.contains("opaque-") && !message.contains("192.168.") &&
                !message.contains("set_music"),"no original ID or LAN endpoint leak");
        }
        ClassicOriginalMusicSelection.clear();
        ClassicOriginalMusicSelection.observeAt("group",200);
        ClassicOriginalMusicSelection.observeGroupEntryAt("group",210);
        ClassicOriginalMusicSelection.observeGroupMembersAt(lamps(14),220);
        ok(ClassicOriginalMusicSelection.verifiedIdentityCountAt(220)==14,
           "original OEM group permits 14");
        ok(ClassicOriginalMusicSelection.markAllDjIntentAt(true,230),
           "all 14 can be selected for DJ");
        ok(ClassicOriginalMusicSelection.djIntentCountAt(230)==14,"all 14 selected");
        ok(ClassicOriginalMusicSelection.lastOutputChangeReason() ==
           ClassicOriginalLightReport.SELECTION_CHANGED,"user selection reason");
        ClassicOriginalMusicSelection.originalDisplayOrderChanged();
        ok(ClassicOriginalMusicSelection.lastOutputChangeReason() ==
           ClassicOriginalLightReport.ORDER_CHANGED,"DnD reason");
        ClassicOriginalMusicSelection.originalGroupRowAction();
        ok(ClassicOriginalMusicSelection.lastOutputChangeReason() ==
           ClassicOriginalLightReport.DEVICE_ACTION,"OEM row action reason");
        ClassicOriginalMusicSelection.originalGroupMembershipChanged();
        ok(ClassicOriginalMusicSelection.lastOutputChangeReason() ==
           ClassicOriginalLightReport.GROUP_CHANGED,"OEM member-change reason");
        ClassicOriginalMusicSelection.observeGroupMembersAt(lamps(13),240);
        ok(ClassicOriginalMusicSelection.lastOutputChangeReason() ==
           ClassicOriginalLightReport.GROUP_CHANGED,"observed J1 membership change");
        ClassicOriginalMusicSelection.observeAt("other",250);
        ok(ClassicOriginalMusicSelection.lastOutputChangeReason() ==
           ClassicOriginalLightReport.CONTEXT_CHANGED,"group-owner change");
        ok(ClassicOriginalLightReport.header(
           ClassicOriginalLightReport.stopped(ClassicOriginalLightReport.GROUP_CHANGED),
           0,false).contains("Details"),"visible status remains after cleanup");
        System.out.println("CLASSIC_REAL_LIGHT_REPORT_PASS");
    }
}
"""

class OriginalLightReportTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"),"JDK required")
    def test_real_member_selection_and_report_java_with_14_and_32(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            stub=root/"com/yeelight/yeelib/device/base/e.java"
            stub.parent.mkdir(parents=True)
            stub.write_text(OEM,encoding="utf-8")
            harness=root/"OriginalLightReportHarness.java"
            harness.write_text(HARNESS,encoding="utf-8")
            command=["javac","--release","8","-d",d,
                str(UI/"ClassicOrderPolicy.java"),
                str(UI/"ClassicOriginalGroupMemberWitness.java"),
                str(UI/"ClassicOriginalLanDiscovery.java"),
                str(REPORT),str(SELECT),str(stub),str(harness)]
            result=subprocess.run(command,capture_output=True,text=True,timeout=35)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(["java","-cp",d,
              "io.github.lxsdd.yeelightclassicdj.ui.OriginalLightReportHarness"],
              capture_output=True,text=True,timeout=35)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn("CLASSIC_REAL_LIGHT_REPORT_PASS",result.stdout)

    def test_original_ui_wiring_not_an_unconnected_diagnostic(self):
        ui=INLINE.read_text(encoding="utf-8")
        group=GROUP.read_text(encoding="utf-8")
        self.assertIn("ClassicOriginalLightReport.starting(targets.size())",ui)
        self.assertIn("ClassicOriginalLightReport.active(",ui)
        self.assertIn("ClassicOriginalLightReport.confirmedProgress(",ui)
        self.assertIn("lightConnectedCount = progress;",ui)
        self.assertIn("running && lightConfirmed, running ? lightConnectedCount : 0",ui)
        self.assertIn("DJ-Start abbrechen",ui)
        runtime=(ROOT/"bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text()
        coordinator=(ROOT/"checkpoint/src/ClassicMusicCoordinator.kt").read_text()
        engine=(ROOT/"checkpoint/src/ClassicDjEngine.kt").read_text()
        self.assertIn("onConfirmed = { confirmed, total ->",runtime)
        self.assertIn("ORIGINAL_CONNECTING_",runtime)
        self.assertIn("onConfirmed(confirmed, ids.size)",coordinator)
        self.assertIn("coordinator.startGroup(ids, allowNextStart, onConfirmed)",engine)
        self.assertIn("ClassicOriginalLightReport.uncertain()",ui)
        self.assertIn("outputLeaseChangeReason(lease)",ui)
        self.assertIn("shownLightRunning != runningOutput",ui)
        self.assertIn("shownLightConfirmed != lightConfirmed",ui)
        self.assertIn("ClassicOriginalLightReport.header(lightStatus",ui)
        self.assertIn('targets.size() + "_LAMPS"',ui)
        self.assertIn("finishLightSession(old, token, stoppedEpoch)",ui)
        self.assertIn("stopLightOutput(ClassicOriginalLightReport.AUDIO_LOST)",ui)
        self.assertIn("if (!sameMembers) ClassicOriginalMusicSelection.originalGroupMembershipChanged()",group)
        self.assertNotIn("set_power",ui)
        self.assertNotIn("set_rgb",ui)

if __name__=="__main__":
    unittest.main()
