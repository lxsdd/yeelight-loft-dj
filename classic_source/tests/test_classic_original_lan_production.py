"""Actual Classic LAN identity parser + OEM GroupMusic selection, pure JVM.

No network call, no APK, no real devices, no ownership/write side effects.
Positive and adversarial cases exercise the REAL Java production paths.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"

STUB = """package com.yeelight.yeelib.device.base;
public class e {
  final String key;
  public e(String value){ key=value; }
  public String T(){ return key; }
}
"""
HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.*;
import com.yeelight.yeelib.device.base.e;
public final class OriginalLanProofHarness {
  static void ok(boolean condition,String name){
    if(!condition)throw new AssertionError(name);
  }
  static String packet(String id,String ip) {
    return "HTTP/1.1 200 OK\r\n" +
      "id: " + id + "\r\n" +
      "Location: yeelight://" + ip + ":55443\r\n" +
      "model: color4\r\n" +
      "support: get_prop set_music set_scene\r\n\r\n";
  }
  static e member(String id){return new e(id);}
  public static void main(String[] args) {
    String a="0x00000000000000a1",b="0x00000000000000b2",c="0x00000000000000c3";
    String pa=packet(a,"192.168.10.21");
    String pb=packet(b,"192.168.10.22");
    ok(ClassicOriginalLanDiscovery.privateIpv4("192.168.10.21"),"private");
    for(String denied:new String[]{"8.8.8.8","0.0.0.0","127.0.0.1",
      "169.254.3.8","192.168.1.0","192.168.1.255","192.168.01.21",
      "192.168.1.321","192.168.1.12\n","fe80::1"}) {
      ok(!ClassicOriginalLanDiscovery.privateIpv4(denied),"foreign ip "+denied);
    }
    ClassicOriginalLanDiscovery.Witness scan=new ClassicOriginalLanDiscovery.Witness();
    ok(!scan.observe(pa,"192.168.10.99",1000),"sender/location spoof rejected");
    ok(!scan.observe(packet(a,"8.8.8.8"),"8.8.8.8",1000),"public excluded");
    ok(!scan.observe(pa.replace("model: color4","model: ceiling1"),
        "192.168.10.21",1000),"wrong model");
    ok(!scan.observe(pa.replace("set_music","set_power"),
        "192.168.10.21",1000),"missing Music");
    ok(!scan.observe(pa.replace("set_scene","set_power"),
        "192.168.10.21",1000),"missing reverse scene method");
    ok(!scan.observe(pa.replace("id: ","id: junk\r\nid: "),
        "192.168.10.21",1000),"duplicate header");
    ok(scan.observe(pa,"192.168.10.21",1000),"genuine first Color4");
    ok(scan.observe(pb,"192.168.10.22",1000),"genuine second Color4");
    ok(scan.resolveAll(Arrays.asList(a,b),1001)!=null,"exact LAN IDs");
    ok(scan.resolveAll(Arrays.asList(a,c),1001)==null,"missing participant");
    ok(scan.resolveAll(Arrays.asList(a,a),1001)==null,"duplicates");
    ok(scan.resolveAll(Arrays.asList(a,b),91001)==null,"strict discovery TTL");
    ok(scan.resolveAll(Arrays.asList(a,b),999)==null,"negative time reversal");
    ClassicOriginalLanDiscovery.Witness conflict=new ClassicOriginalLanDiscovery.Witness();
    ok(conflict.observe(pa,"192.168.10.21",1000),"initial");
    ok(!conflict.observe(packet(a,"192.168.10.23"),"192.168.10.23",1010),
        "same id different endpoints conflict");
    ok(conflict.resolveAll(Collections.singletonList(a),1010)==null,
        "conflict revokes earlier apparent address");
    ClassicOriginalLanDiscovery.Witness colliding=new ClassicOriginalLanDiscovery.Witness();
    ok(colliding.observe(pa,"192.168.10.21",1000),"first owner");
    ok(!colliding.observe(packet(c,"192.168.10.21"),"192.168.10.21",1001),
        "same address different LAN identities");
    ok(colliding.resolveAll(Collections.singletonList(a),1002)==null,
        "address owner ambiguity revokes both");

    ClassicOriginalMusicSelection.clear();
    ClassicOriginalMusicSelection.observeAt("group",1000);
    ClassicOriginalMusicSelection.observeGroupEntryAt("group",1001);
    List<e> members=Arrays.asList(member(a),member(b),member(c));
    ClassicOriginalMusicSelection.observeGroupMembersAt(members,1002);
    ok(ClassicOriginalMusicSelection.djIntentCountAt(1003)==0,"not selected by discovery");
    ok(ClassicOriginalMusicSelection.markDjIntentAt(a,true,1003),"explicit A");
    ok(ClassicOriginalMusicSelection.markDjIntentAt(b,true,1004),"explicit B");
    List<String> originalIds=Arrays.asList(a,b,c);
    ClassicOriginalLanDiscovery.VerifiedTargets proof=scan.resolveAll(
      Arrays.asList(a,b),1005);
    ok(proof!=null,"complete proof");
    ok(!ClassicOriginalMusicSelection.acceptLanProofAt(
      Arrays.asList(a,b,"foreign"),proof,1006),"stale OEM list blocked");
    ok(ClassicOriginalMusicSelection.acceptLanProofAt(originalIds,proof,1006),
       "matching original and protocol identity");
    ok(ClassicOriginalMusicSelection.resolvedOriginalLanIpsAt(1007).equals(
      Arrays.asList("192.168.10.21","192.168.10.22")),"only selected IPs in OEM order");
    ClassicOriginalMusicSelection.OutputLease active =
      ClassicOriginalMusicSelection.leaseForOriginalOutputAt(1007);
    ok(active!=null && active.size()==2,"live consent exact cohort");
    active.targets().clear();
    ok(active.size()==2 && ClassicOriginalMusicSelection.isOutputLeaseCurrent(active),
       "caller cannot mutate immutable live cohort");
    ok(ClassicOriginalMusicSelection.resolvedOriginalLanIpsAt(91007).isEmpty(),
       "LAN proof expiry clears");
    ok(ClassicOriginalMusicSelection.isOutputLeaseCurrent(active),
       "owned session must survive one-time discovery TTL");
    ok(ClassicOriginalMusicSelection.acceptLanProofAt(originalIds,proof,1008),
       "restore current proof");
    ok(!ClassicOriginalMusicSelection.isOutputLeaseCurrent(active),
       "new proof revokes previous output consent");
    ClassicOriginalMusicSelection.OutputLease renewed =
      ClassicOriginalMusicSelection.leaseForOriginalOutputAt(1008);
    ok(renewed!=null && ClassicOriginalMusicSelection.isOutputLeaseCurrent(renewed),
       "renewed consent valid");
    ClassicOriginalMusicSelection.markDjIntentAt(c,true,1009);
    ok(!ClassicOriginalMusicSelection.isOutputLeaseCurrent(renewed),
       "explicit participant change revokes live output");
    ok(ClassicOriginalMusicSelection.resolvedOriginalLanIpsAt(1010).isEmpty(),
       "selection revokes prior endpoint grant");
    ClassicOriginalMusicSelection.markDjIntentAt(c,false,1011);
    ok(ClassicOriginalMusicSelection.acceptLanProofAt(originalIds,proof,1012),
       "re-confirm original pair");
    ClassicOriginalMusicSelection.OutputLease beforeRefresh =
      ClassicOriginalMusicSelection.leaseForOriginalOutputAt(1012);
    ClassicOriginalMusicSelection.observeGroupMembersAt(
      Arrays.asList(member(a),member(c)),1013);
    ok(ClassicOriginalMusicSelection.resolvedOriginalLanIpsAt(1014).isEmpty(),
       "OEM refresh removes authority");
    ok(!ClassicOriginalMusicSelection.isOutputLeaseCurrent(beforeRefresh),
       "OEM membership refresh revokes live output");
    ClassicOriginalMusicSelection.observeAt("another-group",1015);
    ok(ClassicOriginalMusicSelection.resolvedOriginalLanIpsAt(1016).isEmpty(),
       "group switch revokes LAN");
    System.out.println("ORIGINAL_LAN_PROOF_RUNTIME_PASS");
  }
}
"""

class OriginalLanProductionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK")
    def test_real_lan_identity_and_owner_lifecycle(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            stub=root/"com/yeelight/yeelib/device/base/e.java"
            stub.parent.mkdir(parents=True)
            stub.write_text(STUB)
            harness=root/"OriginalLanProofHarness.java"
            harness.write_text(HARNESS)
            sources=["ClassicOriginalLanDiscovery.java","ClassicOriginalMusicSelection.java",
                     "ClassicOriginalGroupMemberWitness.java","ClassicOrderPolicy.java"]
            compiled=subprocess.run(["javac","--release","8","-d",temp,
                *(str(UI/name) for name in sources),str(stub),str(harness)],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            executed=subprocess.run(["java","-cp",temp,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalLanProofHarness"],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(executed.returncode,0,executed.stderr)
            self.assertIn("ORIGINAL_LAN_PROOF_RUNTIME_PASS",executed.stdout)

    def test_actual_original_ui_to_discovery_and_reverse_runtime_wiring(self):
        group=(UI/"ClassicOriginalGroupMemberOrder.java").read_text()
        inline=(UI/"ClassicMusicInlineControls.java").read_text()
        discovery=(UI/"ClassicOriginalLanDiscovery.java").read_text()
        runtime=(ROOT/"bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text()
        self.assertIn("adapter.requestLanCheck(revision)",group)
        self.assertIn("ClassicOriginalLanDiscovery.discover(chosen,localIp)",group)
        self.assertIn("ClassicOriginalMusicSelection.acceptLanProof(",group)
        self.assertIn("ClassicOriginalMusicSelection.resolvedOriginalLanIps()",inline)
        self.assertIn("new CheckpointRuntime()",inline)
        self.assertIn("runtime.startOriginalAudio(targets,",inline)
        self.assertIn("isOutputLeaseCurrent(consent)",inline)
        self.assertIn("originalOutputGuard",runtime)
        self.assertIn("owned.size != selectedLampIps.size",runtime)
        self.assertIn("runtime.submitOriginalFrame(frame)",inline)
        self.assertIn("originalDsp.setFrameObserver(",inline)
        self.assertIn("stopLightOutput();",inline)
        self.assertIn("if (lightRuntime != runtime || epoch != lightEpoch)",inline)
        self.assertIn("Music-Rückkanäle werden geprüft",inline)
        self.assertIn("startOriginalAudio(selectedLampIps: List<String>",runtime)
        self.assertIn("pendingOriginal = OriginalFrame(",runtime)
        self.assertIn("privateIpv4(candidate.getHostAddress())",discovery)
        self.assertNotIn("set_power",inline)
        self.assertNotIn("host.startService(",inline)

if __name__=="__main__":
    unittest.main()
