"""Synthetic NPE callsite localization/diff; never consumes OEM APK bytes."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "private_ci"))
import null_callsite_comparison as s

PACKAGE = s.PACKAGE


class NullCallsiteRegression(unittest.TestCase):
    def test_target_only_first_null_frame(self):
        foreign = ("FATAL EXCEPTION: main\nProcess: foreign.app, PID: 1\n"
                   "java.lang.NullPointerException: secret\n"
                   "    at foreign.Name.secret(Unknown Source:2)\n")
        target = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE + ", PID: 2\n"
                  "java.lang.NullPointerException: Attempt to invoke virtual method\n"
                  "    at a.b.c.d(Unknown Source:11)\n"
                  "    at android.app.ActivityThread.performResumeActivity(Source:4)\n")
        self.assertEqual(s.target_null_site(foreign + target), ("a.b.c", "d"))
        self.assertIsNone(s.target_null_site(foreign))
        non_null = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE + ", PID: 2\n"
                    "java.lang.ClassNotFoundException: not-yet-loaded\n"
                    "    at a.b.c.d(Unknown Source:2)\n")
        self.assertIsNone(s.target_null_site(non_null))
        forged = ("FATAL EXCEPTION: main\nProcess: " + PACKAGE + ", PID: 2\n"
                  "java.lang.NullPointerException: real\n"
                  "11-13 11:20:11.000  2  2 E WrongTag: "
                  "    at secret.Evil.method(Unknown Source:1)\n")
        self.assertIsNone(s.target_null_site(forged))
        for bad in (b"binary", "A" * (s.MAX_TEXT + 1)):
            with self.assertRaisesRegex(s.NullSiteBlocked,
                                        "NULL_SITE_INPUT_INVALID"):
                s.target_null_site(bad)

    def test_smali_same_known_authority_or_unknown_difference(self):
        stock = (".class public La/b/c;\n"
                 ".method public d()V\n"
                 "    const-string v0, \"com.yeelight.cherry.device\"\n"
                 "    return-void\n.end method\n"
                 ".method private d(I)V\n"
                 "    return-void\n.end method\n")
        expected = stock.replace("com.yeelight.cherry.device",
                                 "io.lxsdd.yeelightdj.device")
        self.assertEqual(s.compare_smali(stock, stock, "d"),
                         "METHOD_TEXT_IDENTICAL")
        self.assertEqual(s.compare_smali(stock, expected, "d"),
                         "KNOWN_AUTHORITY_LITERALS_ONLY")
        altered = expected.replace("    return-void", "    nop\n    return-void", 1)
        self.assertEqual(s.compare_smali(stock, altered, "d"),
                         "OTHER_METHOD_TEXT_DIFFERENCE")
        overload = stock.replace(".method private d(I)V", ".method private e(I)V")
        self.assertEqual(s.compare_smali(stock, overload, "d"),
                         "OVERLOAD_SET_DIFFERS")
        self.assertEqual(s.compare_smali(stock, stock, "absent"),
                         "METHOD_MISSING_OR_AMBIGUOUS")
        repeated = stock + stock[stock.index(".method public"):stock.index(".method private")]
        with self.assertRaisesRegex(s.NullSiteBlocked, "NULL_SITE_DUPLICATE_METHOD"):
            s.compare_smali(repeated, stock, "d")
        with self.assertRaisesRegex(s.NullSiteBlocked, "NULL_SITE_UNTERMINATED_METHOD"):
            s.compare_smali(".method public d()V\n return-void", stock, "d")

    def test_never_run_privately_without_exact_ephemeral_scope(self):
        with self.assertRaisesRegex(s.NullSiteBlocked, "NULL_SITE_PRIVATE_SCOPE_REQUIRED"):
            s.compare_private(Path("/tmp/fake-original.apk"),
                              Path("/tmp/fake-clone.apk"), "", Path("/tmp/none"))
        self.assertTrue(all(isinstance(x, str) and "secret" not in x.lower()
                            for x in s.RELATIONS))

    def test_real_path_subclass_is_accepted_before_private_dex_read(self):
        # Path() is PosixPath/WindowsPath, never exactly the abstract Path type.
        # This positive gate prevents the protected runtime from rejecting its
        # own valid RUNNER_TEMP path before checking a genuine target callsite.
        import tempfile
        import os
        from unittest import mock
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original = root / "private-original/com.yeelight.cherry_3.5.4.apk"
            clone = root / "private-output/classic-loft-dj-TEST-SIGNED.apk"
            original.parent.mkdir()
            clone.parent.mkdir()
            original.write_bytes(b"synthetic original, not OEM bytes")
            clone.write_bytes(b"synthetic clone, not OEM bytes")
            with mock.patch.dict(os.environ, {"RUNNER_TEMP": temp}), \
                 mock.patch.object(s, "digest_file", return_value=s.ORIGINAL_SHA256), \
                 mock.patch.object(s, "target_null_site", return_value=None):
                self.assertEqual(s.compare_private(original, clone, "", root),
                                 "UNAVAILABLE")
            with mock.patch.dict(os.environ, {"RUNNER_TEMP": temp}):
                with self.assertRaisesRegex(s.NullSiteBlocked,
                                            "NULL_SITE_PRIVATE_SCOPE_REQUIRED"):
                    s.compare_private(original, clone, "", Path("/tmp/foreign"))


    def test_null_receiver_instance_static_and_invocation_sources(self):
        # Every source is synthetic; only fixed enums can leave private runner.
        call = "    invoke-virtual {v0}, Lfoo/bar/Baz;->run()V\n"
        prefix = ".method public d()V\n"
        for statement, expected in (
            ("iget-object v0, p0, La/b;->f:Lfoo/bar/Baz;", "INSTANCE_FIELD_LOAD"),
            ("sget-object v0, La/b;->f:Lfoo/bar/Baz;", "STATIC_FIELD_LOAD"),
            ("move-result-object v0", "METHOD_RETURN_VALUE"),
            ("aget-object v0, v1, v2", "ARRAY_ELEMENT"),
            ("const/4 v0, 0x0", "LITERAL_NULL"),
            ("move-object v0, p1", "CALLER_PARAMETER_OR_THIS"),
            ("new-instance v0, Lfoo/bar/Baz;", "NON_NULL_SOURCE_OR_STALE_STACK"),
        ):
            src = prefix + "    " + statement + "\n" + call + ".end method\n"
            self.assertEqual(s.receiver_origin_from_smali(src, "d",
                             ("foo.bar.Baz","run")),expected)
        ambiguity = (prefix + "    iget-object v0, p0, La/b;->f:Lfoo/bar/Baz;\n"
                     "    :cond_0\n" + call + ".end method\n")
        self.assertEqual(s.receiver_origin_from_smali(
            ambiguity, "d", ("foo.bar.Baz","run")),
            "UNKNOWN_OR_BRANCH_AMBIGUOUS")
        duplicated = prefix + "    move-object v0, p1\n" + call + call + ".end method\n"
        self.assertEqual(s.receiver_origin_from_smali(
            duplicated,"d",("foo.bar.Baz","run")),
            "UNKNOWN_OR_BRANCH_AMBIGUOUS")
        self.assertEqual(s.receiver_origin_from_smali(
            prefix+call+".end method\n","d",None), "UNAVAILABLE")
        for bad in (("Secret",), (1,2), ("a.b", "c"*400)):
            with self.assertRaisesRegex(s.NullSiteBlocked,"NULL_RECEIVER_SIGNATURE_INVALID"):
                s.receiver_origin_from_smali(prefix+call+".end method\n","d",bad)
        self.assertTrue(all(v.isupper() for v in s.RECEIVER_ORIGINS))

    def test_null_invocation_target_scoped_and_callee_never_serialized(self):
        target = ("FATAL EXCEPTION: main\nProcess: "+PACKAGE+", PID: 12\n"
                  "java.lang.NullPointerException: Attempt to invoke virtual method "
                  "'void foo.bar.Baz.run()' on a null object reference\n"
                  "    at a.b.c.d(Unknown Source:3)\n")
        foreign = target.replace("Process: "+PACKAGE, "Process: unrelated.app")
        self.assertEqual(s._target_null_invoked(target), ("foo.bar.Baz","run"))
        self.assertIsNone(s._target_null_invoked(foreign))
        self.assertIsNone(s._target_null_invoked(
            target.replace("Attempt to invoke", "Attempt to read")))
        self.assertNotIn("foo.bar.Baz",str(s.RECEIVER_ORIGINS))
        with self.assertRaisesRegex(s.NullSiteBlocked,"NULL_RECEIVER_LOG_INVALID"):
            s._target_null_invoked(b"non-text")



if __name__ == "__main__":
    unittest.main()
