"""Real original SceneFragment DJ-effect reordering and MusicActivity readback."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui"
GALLERY = (UI / "ClassicSceneGalleryPanel.java").read_text()
MUSIC = (UI / "ClassicMusicInlineControls.java").read_text()
ORDER = (UI / "ClassicEffectOrder.java").read_text()
HOOK = (ROOT / "clone_scene_gallery_hook.py").read_text()

class OriginalSceneDjEffectDndTest(unittest.TestCase):
    def test_original_scene_host_real_user_action_to_persisted_controller(self):
        self.assertIn("->attach(Ljava/lang/Object;Landroid/view/View;)V", HOOK)
        self.assertIn("card.setOnLongClickListener(v -> startEffectDrag(card))", GALLERY)
        self.assertIn("card.setOnDragListener((v, event) -> handleEffectDrop(card, event))", GALLERY)
        self.assertIn("event.getLocalState() != dragSource", GALLERY)
        self.assertIn("effectOrder.move(ClassicSceneUiState.SCENES[sourceIndex], destination)", GALLERY)
        self.assertIn("effectOrder.moveRelative(ClassicSceneUiState.SCENES[s.scene], direction)", GALLERY)
        self.assertIn("effectOrder.undo()", GALLERY)
        self.assertIn("controller.onUserMove(this, id, finalIndex)", ORDER)
        self.assertIn("controller.onUndoLastMove(this)", ORDER)
        self.assertIn("store.read(ClassicOrderPolicy.Surface.SCENE_LIST, SCOPE)", ORDER)

    def test_original_music_reads_same_cloned_user_order_not_new_player(self):
        self.assertIn("effectOrder = new ClassicEffectOrder(activity)", MUSIC)
        self.assertIn("if (effectOrder.refresh()) renderEffectChips()", MUSIC)
        self.assertIn("chips.addView(choices[index], params)", MUSIC)
        self.assertNotIn("ClassicDjService.ACTION_MIC", MUSIC)
        self.assertNotIn("startDragAndDrop", MUSIC)

    def test_owned_scope_not_oem_scene_member_or_network(self):
        for forbidden in ("getRemoteDeviceList", "set_power", "set_music",
                          "HttpURLConnection", "ContentResolver", "SQLiteDatabase",
                          "startService(", "sendBroadcast(", "getDeviceId("):
            self.assertNotIn(forbidden, ORDER)
        self.assertIn('private static final String SCOPE = "loft-dj-effect-cards"', ORDER)
        self.assertIn("SCENE_LIST", ORDER)

if __name__ == "__main__":
    unittest.main()
