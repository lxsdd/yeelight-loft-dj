"""Actual original FavoriteSceneAdapter setter + binder hook, no OEM data."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from classic_first import GateFailure
import clone_original_favorite_order_hook as hook

ORIGINAL = f"""
.class public {hook.OWNER}
.super Landroidx/recyclerview/widget/RecyclerView$Adapter;
.method public {hook.CTOR}
    .locals 2
    invoke-direct {{p0}}, {hook.SUPER}
    const/4 v0, 0x0
    return-void
.end method
.method public {hook.SETTER}
    .locals 2
    invoke-interface {{v0}}, Ljava/util/List;->clear()V
    invoke-interface {{v0, p1}}, Ljava/util/List;->addAll(Ljava/util/Collection;)Z
    move-result v1
    invoke-virtual {{p0}}, Landroidx/recyclerview/widget/RecyclerView$Adapter;->notifyDataSetChanged()V
    return-void
.end method
.method public {hook.BINDER}
    .locals 2
    invoke-interface {{v0, p2}}, Ljava/util/List;->get(I)Ljava/lang/Object;
    move-result-object v0
    return-void
.end method
"""

class FavoriteOriginalHookTests(unittest.TestCase):
    def test_source_verified_constructor_refresh_and_row_entry(self):
        changed = hook.patch_text(ORIGINAL)
        self.assertEqual(changed.count(hook.REGISTER), 1)
        self.assertEqual(changed.count(hook.WRAP), 1)
        self.assertEqual(changed.count(hook.BIND), 1)
        self.assertIn("move-result-object p1", changed)
        self.assertEqual(changed.count("Ljava/util/List;->addAll(Ljava/util/Collection;)Z"), 1)
        self.assertEqual(changed.count("->notifyDataSetChanged()V"), 1)
        with self.assertRaisesRegex(GateFailure, "ALREADY_PATCHED"):
            hook.patch_text(changed)

    def test_reused_favorite_parameter_registers_are_not_read_at_original_return(self):
        source = ORIGINAL.replace("    move-result-object v0\n    return-void",
                                  "    move-result-object v0\n    move-object p2, v0\n    return-void")
        changed = hook.patch_text(source)
        body = hook._method(changed, "classicLoftDjOriginalRow_" + hook.BINDER).group()
        wrapper = hook._method(changed, hook.BINDER).group()
        self.assertIn("move-object p2, v0", body)
        self.assertNotIn(hook.BIND, body)
        self.assertIn(".locals 0", wrapper)
        self.assertLess(wrapper.index("invoke-direct/range"), wrapper.index(hook.BIND))

    def test_source_drift_has_no_fallback(self):
        variants = (
            ORIGINAL.replace("FavoriteSceneAdapter;", "OtherAdapter;"),
            ORIGINAL.replace(hook.CTOR, "<init>(I)V"),
            ORIGINAL.replace("invoke-direct {p0}", "invoke-static {p0}"),
            ORIGINAL.replace("Ljava/util/List;->clear()V", "Ljava/util/List;->remove(I)Ljava/lang/Object;"),
            ORIGINAL.replace("Ljava/util/List;->addAll(Ljava/util/Collection;)Z",
                             "Ljava/util/List;->add(Ljava/lang/Object;)Z"),
            ORIGINAL.replace("Ljava/util/List;->get(I)Ljava/lang/Object;",
                             "Ljava/util/List;->get(J)Ljava/lang/Object;"),
            ORIGINAL.replace("return-void\n.end method", "throw v0\n.end method", 3),
            ORIGINAL + ORIGINAL[ORIGINAL.index(".method public " + hook.BINDER):],
        )
        for bad in variants:
            with self.subTest(size=len(bad)):
                with self.assertRaises(GateFailure):
                    hook.patch_text(bad)

    def test_private_staging_and_production_wiring(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / hook.FAVORITE
            target.parent.mkdir(parents=True)
            target.write_text("wrong OEM", encoding="utf8")
            with self.assertRaises(GateFailure):
                hook.patch_staged_clone(Path(temp))
            self.assertEqual(target.read_text(), "wrong OEM")
            target.write_text(ORIGINAL, encoding="utf8")
            receipt = hook.patch_staged_clone(Path(temp))
            self.assertEqual(receipt["original_records_or_database_writes"], 0)
            self.assertFalse(receipt["runtime_oem_verified"])
            self.assertIn(hook.WRAP, target.read_text())
        builder = (ROOT / "build_private_unsigned.py").read_text()
        bridge = (ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalListOrder.java").read_text()
        self.assertIn("patch_original_favorites(staged)", builder)
        self.assertIn("original_favorites_sort", builder)
        self.assertIn('FAVORITE_ADAPTER =', bridge)
        self.assertIn('FAVORITE_MODEL = "com.yeelight.yeelib.models.m"', bridge)
        self.assertIn('ClassicOrderPolicy.Surface.FAVORITES', bridge)
        self.assertIn('"W"', bridge)
        self.assertIn("new ArrayList<>(ordered)", bridge)
        self.assertIn('getMethod("e", List.class)', bridge)
        self.assertIn("bindFavoriteRow(Object adapter, Object holder, int index)", bridge)
        for forbidden in ("SQLiteDatabase", "ContentResolver", "set_music(", "set_power(",
                          "startService(", "sendBroadcast("):
            self.assertNotIn(forbidden, bridge)

if __name__ == "__main__":
    unittest.main()
