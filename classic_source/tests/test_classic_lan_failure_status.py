"""Native original UI fixed-error classification (real production Java)."""
import subprocess
import tempfile
import unittest
from pathlib import Path
SOURCE=Path(__file__).resolve().parents[1]/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalLightReport.java"
JAVA="""
package io.github.lxsdd.yeelightclassicdj.ui;
class LanFailureStatusCheck {
  static void yes(boolean ok) { if(!ok)throw new AssertionError(); }
  public static void main(String[] args) {
    String[] codes={"LOCAL_BUDGET","DEVICE_QUOTA","DEVICE_REJECTED","TIMEOUT",
      "NETWORK_UNCERTAIN","OEM_ABI","MUSIC_UNKNOWN","FOREIGN_OWNER",
      "REVERSE_UNCERTAIN","CLEANUP_UNCERTAIN"};
    for(String code:codes) {
      yes(ClassicOriginalLightReport.startFailure(code).startsWith("DJ-Start gesperrt"));
      yes(ClassicOriginalLightReport.uncertain(code).startsWith("DJ-Ausgabe gesperrt"));
    }
    yes(ClassicOriginalLightReport.startFailure("DEVICE_QUOTA").contains("Quota"));
    yes(!ClassicOriginalLightReport.startFailure("TIMEOUT").contains("Quota"));
    yes(ClassicOriginalLightReport.startFailure("10.1.2.3").equals(
      "Lampenverbindung verweigert oder nicht bestätigt"));
    yes(ClassicOriginalLightReport.uncertain("privateOEM").equals(
      ClassicOriginalLightReport.uncertain()));
    System.out.println("PASS native original Classic LAN failure UI");
  }
}
"""
class TestClassicLanFailureStatus(unittest.TestCase):
    def test_actual_java8_production_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/"LanFailureStatusCheck.java").write_text(JAVA,encoding="utf-8")
            cp=subprocess.run(["javac","-encoding","UTF-8","--release","8","-d",str(root),
                str(SOURCE),str(root/"LanFailureStatusCheck.java")],capture_output=True,text=True)
            self.assertEqual(cp.returncode,0,cp.stderr)
            result=subprocess.run(["java","-cp",str(root),
                "io.github.lxsdd.yeelightclassicdj.ui.LanFailureStatusCheck"],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn("PASS native original Classic LAN failure UI",result.stdout)
