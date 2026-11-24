"""Only synthetic private static writer direct-call graph differential tests."""
import sys
import tempfile
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "private_ci"))
import static_field_caller_audit as a
from static_field_writer_audit import WriterBlocked
from smali_authority_reassembly import rewrite_literals

WRITERS = {("La/b/Globals;", "setup()V"): "synthetic method body"}
CALL = "    invoke-static {}, La/b/Globals;->setup()V\n"


class StaticCallerRegression(unittest.TestCase):
    def test_exact_writer_only_in_eligible_dex_and_role_classification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/"smali").mkdir()
            (root/"smali_classes2/a/b").mkdir(parents=True)
            path = root/"smali_classes2/a/b/Main.smali"
            path.write_text(
                ".class public La/b/Main;\n"
                ".method public onCreate()V\n"
                "    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n"
                "    if-eqz v0, :cond_0\n" + CALL +
                "    :cond_0\n    return-void\n.end method\n"
                ".method public ignore()V\n"
                "    invoke-static {}, La/b/Globals;->other()V\n"
                "    return-void\n.end method\n", encoding="utf-8")
            found = a.direct_writer_callers(root, WRITERS)
            self.assertEqual(set(found), {("La/b/Main;", "onCreate()V")})
            result = a.assess_direct_writer_callers(
                found, found, set(), {("activity", "a.b.Main")},
                rewrite_literals)
            self.assertEqual(result, (
                "RETAINED_ACTIVITY_CALLER", "CALLER_METHODS_IDENTICAL",
                "IDENTITY_REFERENCE_AND_BRANCH"))
            rewritten = {k: v.replace("return-void", "nop\n    return-void")
                         for k, v in found.items()}
            self.assertEqual(a.assess_direct_writer_callers(
                found, rewritten, set(), set(), rewrite_literals)[1],
                "CALLER_METHODS_DIFFER")
            self.assertEqual(a.assess_direct_writer_callers(
                found, {}, set(), set(), rewrite_literals)[1],
                "CALLER_SET_DIFFERS")
            self.assertEqual(a.assess_direct_writer_callers(
                found, found, {("provider", "a.b.Main")}, set(),
                rewrite_literals)[0], "REMOVED_PROVIDER_CALLER")
            self.assertEqual(a.assess_direct_writer_callers(
                {}, {}, set(), set(), rewrite_literals), (
                "NO_DIRECT_CALLERS", "NO_DIRECT_CALLERS", "NO_DIRECT_CALLERS"))
            path.unlink()
            path.symlink_to(root/"smali/missing.smali")
            with self.assertRaisesRegex(WriterBlocked,
                                        "WRITER_CALLER_UNSAFE_FILE"):
                a.direct_writer_callers(root, WRITERS)

    def test_reject_unknown_inputs_and_no_identity_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for dex in ("smali", "smali_classes2"):
                (root/dex).mkdir()
            with self.assertRaisesRegex(WriterBlocked,
                                        "WRITER_CALLER_SCOPE_INVALID"):
                a.direct_writer_callers(root, {})
            with self.assertRaisesRegex(WriterBlocked,
                                        "WRITER_CALLER_TARGET_INVALID"):
                a.direct_writer_callers(root, {("Lsecret/Class;", "not-valid"): ""})
            with self.assertRaisesRegex(WriterBlocked,
                                        "WRITER_CALLER_INPUT_INVALID"):
                a.assess_direct_writer_callers({}, {}, ["secret"], set(),
                                               rewrite_literals)
        for group in (a.CALLER_ROUTES, a.CALLER_RELATIONS, a.CALLER_CONTEXTS):
            self.assertTrue(all(word == word.upper() and "/" not in word
                                and "secret" not in word.lower() for word in group))


if __name__ == "__main__":
    unittest.main()
