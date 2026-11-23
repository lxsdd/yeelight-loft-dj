"""Compile the REAL shared scene output profile and actual DjRuntime DTO.

Original SceneFragment and original MusicActivity must use the same immutable
gain/pulse-envelope/timer/spatial settings. No APK, Android device, lamp or
user preference is read or written by this test.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
RUNTIME = ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/runtime"
GALLERY = UI/"ClassicSceneGalleryPanel.java"
INLINE = UI/"ClassicMusicInlineControls.java"
PROFILE = UI/"ClassicSceneOutputProfile.java"
HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import io.github.lxsdd.yeelightclassicdj.runtime.DjRuntime;
public class OriginalSceneOutputHarness {
    static void yes(boolean ok,String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    static DjRuntime.SceneSettings make(int scene,int speed,int gain,int pulse,
                                        boolean beat,int trail,int range) {
        return ClassicSceneOutputProfile.fromSelection(
            new ClassicSceneUiState.Selection(scene,speed,true,trail,range,
                                               gain,beat,4,pulse));
    }
    public static void main(String[] args) {
        double[] gains={.5,1.,2.,4.};
        int[][] shapes={{12,65,160,900},{8,100,70,330},{12,90,90,600}};
        double[] gamma={2.2,1.2,2.0};
        long revision=ClassicSceneUiState.revision();
        for (int scene=0;scene<7;scene++)
          for(int gain=0;gain<4;gain++)
            for(int shape=0;shape<3;shape++) {
                DjRuntime.SceneSettings p=make(scene,4,gain,shape,false,4,1);
                yes(p.scene.equals(ClassicSceneUiState.SCENES[scene]),"scene identity");
                yes(p.sensitivity==gains[gain],"original gain");
                yes(p.pulseMin==shapes[shape][0] &&
                    p.pulseMax==shapes[shape][1] &&
                    p.attackMs==shapes[shape][2] &&
                    p.decayMs==shapes[shape][3] &&
                    p.gamma==gamma[shape],"pulse shape passed to engine");
                yes(p.pulseBpm==180 && !p.beatSync,"timer settings");
                yes(p.spatialSpeed==200 && p.reverse && p.trailLength==8 &&
                    p.spatialMin==5 && p.spatialMax==60,
                    "spatial geometry retained");
                yes(p.spatialRgb==0xFF2E79 && p.pulseRgb==0xFF2E79,
                    "consistent Classic colour contract");
            }
        for(int ci=0;ci<ClassicSceneUiState.COLORS.length;ci++) {
            ClassicSceneUiState.Selection c =
                new ClassicSceneUiState.Selection(6,4,false,4,1,1,false,2,1,ci);
            DjRuntime.SceneSettings p=ClassicSceneOutputProfile.fromSelection(c);
            yes(p.spatialRgb==ClassicSceneUiState.COLORS[ci] &&
                p.pulseRgb==ClassicSceneUiState.COLORS[ci],
                "user-selected colour must reach pulse and spatial output");
        }
        for(int i=0;i<3;i++) {
            DjRuntime.SceneSettings p=make(3,2,1,i,true,2,0);
            yes(p.beatSync && p.pulseBpm==180,"beat pulse selection");
        }
        yes(ClassicSceneUiState.revision()==revision,
            "pure mapping cannot change or persist scene selection");
        boolean rejected=false;
        try {
            ClassicSceneOutputProfile.fromSelection(
                new ClassicSceneUiState.Selection(3,2,false,2,0,1,true,2,3));
        } catch(IllegalArgumentException expected) { rejected=true; }
        yes(rejected,"unknown pulse shape must fail closed");
        rejected=false;
        try { ClassicSceneOutputProfile.fromSelection(null); }
        catch(IllegalArgumentException expected) { rejected=true; }
        yes(rejected,"null scene not accepted");
        System.out.println("CLASSIC_SHARED_SCENE_PROFILE_PASS");
    }
}
"""
class OriginalSceneOutputProfileTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_compiled_production_profile_and_dj_runtime_dto(self):
        with tempfile.TemporaryDirectory(prefix="classic-profile-") as temp:
            root=Path(temp)
            ctx=root/"android/content/Context.java"
            proj=root/"android/media/projection/MediaProjection.java"
            ctx.parent.mkdir(parents=True)
            proj.parent.mkdir(parents=True)
            ctx.write_text("package android.content; public class Context {}")
            proj.write_text("package android.media.projection; public class MediaProjection {}")
            harness=root/"OriginalSceneOutputHarness.java"
            harness.write_text(HARNESS,encoding="utf-8")
            files=[ctx,proj,RUNTIME/"DjRuntime.java",
                   UI/"ClassicSceneUiState.java",PROFILE,harness]
            cmd=["javac","--release","8","-d",temp]+[str(p) for p in files]
            built=subprocess.run(cmd,capture_output=True,text=True,timeout=35)
            self.assertEqual(built.returncode,0,built.stderr)
            run=subprocess.run(["java","-cp",temp,
                "io.github.lxsdd.yeelightclassicdj.ui.OriginalSceneOutputHarness"],
                capture_output=True,text=True,timeout=35)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("CLASSIC_SHARED_SCENE_PROFILE_PASS",run.stdout)

    def test_both_original_production_hosts_use_exact_one_profile(self):
        ui=INLINE.read_text("utf-8")
        gallery=GALLERY.read_text("utf-8")
        for s in (ui,gallery):
            self.assertIn("ClassicSceneOutputProfile.fromSelection(",s)
        self.assertIn("runtime.applySceneSettings(",ui)
        self.assertIn("profile.gamma",gallery)
        self.assertIn("profile.sensitivity",gallery)
        self.assertIn("profile.pulseMin",gallery)
        self.assertIn("profile.spatialMin",gallery)
        self.assertIn("ClassicSceneUiState.COLOR_NAMES",gallery)
        self.assertIn("ClassicSceneUiState.COLORS[selected.colorIndex]",ui)
        self.assertIn("ClassicSceneOutputProfile.fromSelection(",gallery)
        self.assertIn("config.putExtra(ClassicDjService.EXTRA_PULSE_COLOR",gallery)
        self.assertIn("config.putExtra(ClassicDjService.EXTRA_SPATIAL_RGB",gallery)
        self.assertIn("(double) profile.pulseRgb",gallery)
        self.assertIn("(double) profile.spatialRgb",gallery)
        self.assertNotIn("new double[]{0.5,1.0,1.5,2.0}",ui)
        self.assertNotIn("double[][] shapes =",gallery)

if __name__=="__main__":
    unittest.main()
