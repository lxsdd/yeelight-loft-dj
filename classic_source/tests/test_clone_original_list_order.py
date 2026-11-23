"""Original Classic Scene/Room adapter list-sort hook: source-only and fail-closed."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from classic_first import GateFailure
import clone_original_list_order_hook as hook

def adapter(owner,bind):
    return f"""
.class public {owner}
.super Landroidx/recyclerview/widget/RecyclerView$Adapter;
.method public {hook.CONSTRUCTOR}
    .locals 2
    invoke-direct {{p0}}, {hook.SUPER}
    iput-object p1, p0, {owner}->rows:Ljava/util/List;
    return-void
.end method
.method public {bind}
    .locals 2
    iget-object v0, p0, {owner}->rows:Ljava/util/List;
    invoke-interface {{v0, p2}}, Ljava/util/List;->get(I)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Ljava/lang/Object;
    return-void
.end method
"""
SCENE_OWNER="Lcom/yeelight/cherry/ui/fragment/SceneRecyclerViewAdapter;"
ROOM_OWNER="Lcom/yeelight/cherry/ui/adapter/RoomRecyclerViewAdapter;"
SCENE=adapter(SCENE_OWNER,hook.SCENE_BIND)
ROOM=adapter(ROOM_OWNER,hook.ROOM_BIND)

class OriginalListHookTests(unittest.TestCase):
    def test_real_original_scene_room_constructors_and_rows(self):
        scene,room=hook.patch_text(SCENE,ROOM)
        for changed,original,kind in ((scene,SCENE,"Scene"),(room,ROOM,"Room")):
            self.assertIn("->wrap"+kind+
                "(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",changed)
            self.assertIn("->bind"+kind+"Row(Ljava/lang/Object;Ljava/lang/Object;I)V",changed)
            self.assertIn("move-result-object p1",changed)
            self.assertEqual(changed.count("->get(I)Ljava/lang/Object;"),1)
            self.assertEqual(changed.count(hook.SUPER),1)
            with self.assertRaisesRegex(GateFailure,"ALREADY_PATCHED"):
                hook.patch_text(changed,ROOM) if kind=="Scene" else hook.patch_text(SCENE,changed)
        self.assertNotIn("set_power",scene+room)

    def test_private_abi_drift_fails_without_mutation(self):
        cases=[
            (SCENE.replace("SceneRecyclerViewAdapter;","OtherAdapter;"),ROOM),
            (SCENE.replace(hook.CONSTRUCTOR,"<init>(Ljava/util/ArrayList;)V"),ROOM),
            (SCENE.replace("invoke-direct {p0}", "invoke-static {p0}"),ROOM),
            (SCENE.replace("return-void\n.end method", "throw v0\n.end method",1),ROOM),
            (SCENE.replace("return-void\n.end method\n.method public",
                           "return-void\n.end method\n.method public "+hook.SCENE_BIND+
                           "\n    .locals 1\n    return-void\n.end method\n.method public",1),ROOM),
            (SCENE,ROOM.replace("RoomRecyclerViewAdapter;","Wrong;")),
            (SCENE,ROOM.replace("->get(I)Ljava/lang/Object;","->get(J)Ljava/lang/Object;")),
            (SCENE,ROOM.replace(hook.ROOM_BIND,"b(Landroid/view/ViewGroup;I)V"))
        ]
        for scene,room in cases:
            with self.subTest(scene=len(scene),room=len(room)):
                with self.assertRaises(GateFailure): hook.patch_text(scene,room)

    def test_two_phase_private_stage_no_oem_material_exposed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for relative,content in ((hook.SCENE,SCENE),(hook.ROOM,ROOM)):
                p=root/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
            (root/hook.ROOM).write_text("drift")
            with self.assertRaises(GateFailure): hook.patch_staged_clone(root)
            self.assertEqual((root/hook.SCENE).read_text(),SCENE)
            (root/hook.ROOM).write_text(ROOM)
            receipt=hook.patch_staged_clone(root)
            self.assertEqual(receipt["original_model_adapter_types"],2)
            self.assertFalse(receipt["pixel_acceptance"])
            self.assertEqual(receipt["original_model_or_database_writes"],0)
            with self.assertRaises(GateFailure): hook.patch_staged_clone(root)

    def test_same_original_host_not_second_scene_database(self):
        original=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalListOrder.java").read_text()
        provider=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicDjEntryProvider.java").read_text()
        builder=(ROOT/"build_private_unsigned.py").read_text()
        self.assertIn('ClassicOriginalListOrder.registerCloneContext(app)',provider)
        self.assertIn("smali_report['original_list_sort'] = patch_original_list_order(staged)",builder)
        self.assertIn('SCENE_MODEL = "com.yeelight.yeelib.models.t"',original)
        self.assertIn('ROOM_MODEL = "y3.a"',original)
        self.assertIn('SCENE_LIST, "classic-original-scenes"',original)
        self.assertIn('ROOM_LIST, "classic-original-rooms"',original)
        self.assertIn('orderStore = new ClassicLocalOrderStore(app)',original)
        self.assertIn('OrderedList wrapper = new OrderedList(rows,',original)
        self.assertIn('orderStore, modelName, getter, surface, scope)',original)
        self.assertIn('if (app != null && CLONE.equals(app.getPackageName()) && app != application)',original)
        self.assertNotIn('new ClassicLocalOrderStore(application)',original)
        self.assertIn("getMethod(getter)",original)
        self.assertIn("startDragAndDrop",original)
        self.assertIn("setOnDragListener",original)
        self.assertIn("notifyDataSetChanged",original)
        self.assertIn("undoLastUserDrop",original)
        for forbidden in ("ContentResolver","SQLiteDatabase","set_music(",
                          "set_power(","getRemoteDeviceList(","getContentResolver(",
                          "sendBroadcast(","startService("):
            self.assertNotIn(forbidden,original)

if __name__=="__main__":
    unittest.main()
