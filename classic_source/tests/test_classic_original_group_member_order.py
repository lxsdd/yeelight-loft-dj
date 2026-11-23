"""Original GroupMusic ListAdapter DnD is installed in the actual onCreate path."""
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from classic_first import GateFailure
import clone_original_group_music_hook as hook
from test_clone_original_group_music_hook import ENTRY

class GroupAdapterOrderingSourceTests(unittest.TestCase):
    def test_actual_source_bridge_orders_display_without_touching_members(self):
        java=(ROOT/"bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalGroupMemberOrder.java").read_text()
        source=hook.patch_text(ENTRY)
        self.assertIn(hook.ORDER_BRIDGE,source)
        self.assertEqual(source.count(hook.ORDER_BRIDGE),1)
        self.assertIn("ListAdapter getWrappedAdapter()",java)
        self.assertIn("original.getView(sourcePosition,priorOriginal,parent)",java)
        self.assertIn("original.getItem(originalIndex(position))",java)
        self.assertIn("original.getItemId(originalIndex(position))",java)
        self.assertIn("original.isEnabled(originalIndex(position))",java)
        self.assertIn("original.getItemViewType(originalIndex(position))",java)
        self.assertIn("ClassicOriginalGroupMemberWitness.inspect(models)",java)
        self.assertIn("ClassicOrderPolicy.Surface.GROUP_MEMBERS",java)
        self.assertIn("applyUserDropAtIndex(",java)
        self.assertIn("undoLastUserDrop(",java)
        self.assertIn("scope.equals(ClassicOriginalMusicSelection.originalGroupDisplayScope())",java)
        self.assertIn("list.setAdapter(original)",java)
        self.assertIn("DataSetObserver",java)
        for prohibited in ("set_power","set_rgb","start_music","music_on","openSocket","setGroupMembers"):
            self.assertNotIn(prohibited,java)
    def test_range_registers_are_supported_without_spare_locals(self):
        changed=ENTRY.replace(
            "invoke-virtual {v5, v6}, Landroid/widget/ListView;",
            "invoke-virtual/range {v5 .. v6}, Landroid/widget/ListView;")
        out=hook.patch_text(changed)
        self.assertIn("invoke-static/range {v5 .. v6}, "+hook.ORDER_BRIDGE,out)
    def test_noncontiguous_and_missing_original_setter_are_rejected(self):
        mutations=[
           ENTRY.replace("invoke-virtual {v5, v6}","invoke-virtual/range {v5 .. v7}"),
           ENTRY.replace("invoke-virtual {v5, v6}","invoke-virtual/range {v5 .. p6}"),
           ENTRY.replace("invoke-virtual {v5, v6}","invoke-virtual {v5}"),
           ENTRY.replace("invoke-virtual {v5, v6}","invoke-interface {v5, v6}"),
           ENTRY.replace("invoke-virtual {v5, v6}","invoke-virtual {v5, v6, v7}"),
           ENTRY.replace("invoke-virtual {v5, v6}, Landroid/widget/ListView;->setAdapter(Landroid/widget/ListAdapter;)V\n",""),
        ]
        for text in mutations:
            with self.subTest(case=text[-60:]):
                with self.assertRaises(GateFailure):
                    hook.patch_text(text)
if __name__=="__main__":
    unittest.main()
