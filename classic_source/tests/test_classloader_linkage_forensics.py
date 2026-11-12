#!/usr/bin/env python3
"""Pure synthetic positive/negative Classic ART-linkage and privacy checks."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import types
import unittest
import warnings
from unittest import mock
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "private_ci"))
from classloader_linkage_forensics import (
    ForensicsBlocked, bucket, summarize_linkage, target_crash_descriptor,
    load_dex_graphs, verify_compiler, safe_main, stage_guard,
)

A = "Ltest/fixture/Target;"
B = "Ltest/fixture/Parent;"
C = "Ltest/fixture/MissingLib;"
JAVA = "Ljava/lang/Object;"


class StaticLinkageTests(unittest.TestCase):
    def test_valid_dex_flat_and_obfuscated_class_names(self):
        # FullClassName accepts no package plus DEX-only digits and '-'.
        for name in ("La;", "L1;", "L-;", "L$;", "Lpkg/9;", "Lpkg/-$$Lambda$3;"):
            with self.subTest(name=name):
                graph = {A: [name], name: [JAVA]}
                origins = {A: "SECONDARY_OEM_DEX", name: "PRIMARY_OEM_DEX"}
                report = summarize_linkage(graph, dict(graph), origins, A)
                self.assertEqual(report["clone_direct_unresolved_parent_bucket"], "NONE")
                self.assertEqual(report["clone_transitive_unresolved_parent_bucket"], "NONE")
                self.assertTrue(report["target_parents_preserved"])
                self.assertFalse(report["art_class_loading_verified"])
                direct = summarize_linkage({name: [JAVA]}, {name: [JAVA]},
                                           {name: "SECONDARY_OEM_DEX"}, name)
                self.assertEqual(direct["clone_target_definition"], "PRESENT")
                self.assertFalse(direct["art_class_loading_verified"])

    def test_valid_flat_name_does_not_waive_missing_parent(self):
        root = "L1;"
        report = summarize_linkage({A: [root]}, {A: [root]},
                                   {A: "SECONDARY_OEM_DEX"}, A)
        self.assertEqual(report["clone_direct_unresolved_parent_bucket"], "ONE")
        self.assertEqual(report["clone_transitive_unresolved_parent_bucket"], "ONE")
        self.assertFalse(report["art_class_loading_verified"])

    def test_invalid_dex_class_descriptors_still_fail_closed(self):
        for malformed in ("L;", "L/bad;", "Lbad//Name;", "Lbad/;",
                          "Lbad Name;", "Lbad[Array;", "L../secret;",
                          "Lbad.Type;", "Lbad/Type;extra;"):
            with self.subTest(malformed=malformed):
                with self.assertRaisesRegex(ForensicsBlocked, "^CLASS_GRAPH_INVALID$"):
                    summarize_linkage({A: [malformed]}, {A: [malformed]},
                                      {A: "SECONDARY_OEM_DEX"}, A)

    def test_existing_target_no_parent_gaps_still_unverified(self):
        g = {A: [B], B: [JAVA]}
        result = summarize_linkage(g, dict(g), {A: "SECONDARY_OEM_DEX",
                                                  B: "PRIMARY_OEM_DEX"}, A)
        self.assertEqual(result["clone_target_origin"], "SECONDARY_OEM_DEX")
        self.assertEqual(result["clone_direct_unresolved_parent_bucket"], "NONE")
        self.assertEqual(result["clone_transitive_platform_assumed_bucket"], "ONE")
        self.assertTrue(result["target_parents_preserved"])
        self.assertFalse(result["art_class_loading_verified"])

    def test_direct_missing_parent_not_inferred_to_load(self):
        g = {A: [C], B: [JAVA]}
        r = summarize_linkage(g, dict(g),
                              {A: "SECONDARY_OEM_DEX", B: "PRIMARY_OEM_DEX"}, A)
        self.assertEqual(r["clone_direct_unresolved_parent_bucket"], "ONE")
        self.assertEqual(r["clone_transitive_unresolved_parent_bucket"], "ONE")
        self.assertFalse(r["clone_graph_has_new_missing_parent"])

    def test_transitive_missing_parent_distinct_from_direct(self):
        g = {A: [B], B: [C]}
        r = summarize_linkage(g, dict(g),
                              {A: "SECONDARY_OEM_DEX", B: "PRIMARY_OEM_DEX"}, A)
        self.assertEqual(r["clone_direct_unresolved_parent_bucket"], "NONE")
        self.assertEqual(r["clone_transitive_unresolved_parent_bucket"], "ONE")

    def test_changed_clone_inheritance_is_detected(self):
        orig = {A: [B], B: [JAVA]}
        clone = {A: [C], B: [JAVA]}
        r = summarize_linkage(orig, clone,
                              {A: "SECONDARY_OEM_DEX", B: "PRIMARY_OEM_DEX"}, A)
        self.assertFalse(r["target_parents_preserved"])
        self.assertTrue(r["clone_graph_has_new_missing_parent"])

    def test_different_missing_parents_same_count_is_still_a_regression(self):
        orig = {A: [C]}
        clone = {A: ["Ltest/fixture/OtherMissing;"]}
        r = summarize_linkage(orig, clone, {A: "SECONDARY_OEM_DEX"}, A)
        self.assertEqual(r["clone_direct_unresolved_parent_bucket"], "ONE")
        self.assertTrue(r["clone_graph_has_new_missing_parent"])

    def test_missing_clone_target_produces_no_false_positive(self):
        r = summarize_linkage({A: [JAVA]}, {B: [JAVA]},
                              {B: "NATIVE_BRIDGE_DEX"}, A)
        self.assertEqual(r["clone_target_definition"], "ABSENT")
        self.assertEqual(r["clone_direct_unresolved_parent_bucket"],
                         "TARGET_NOT_DEFINED")
        self.assertFalse(r["art_class_loading_verified"])

    def test_invalid_graph_descriptor_or_origin_is_rejected(self):
        with self.assertRaisesRegex(ForensicsBlocked, "TARGET_INVALID"):
            summarize_linkage({A: []}, {A: []}, {A: "SECONDARY_OEM_DEX"}, "not-dex")
        with self.assertRaisesRegex(ForensicsBlocked, "ORIGIN_INVALID"):
            summarize_linkage({A: []}, {A: []}, {A: "../../PRIV"}, A)
        with self.assertRaisesRegex(ForensicsBlocked, "CLASS_GRAPH_INVALID"):
            summarize_linkage({A: ["Linvalid"]}, {A: []},
                              {A: "SECONDARY_OEM_DEX"}, A)

    def test_bounded_graph_walk_rejects_large_or_cyclic_attack(self):
        import classloader_linkage_forensics as module
        with mock.patch.object(module, "MAX_WALK", 1):
            with self.assertRaisesRegex(ForensicsBlocked, "CLASS_GRAPH_TOO_LARGE"):
                summarize_linkage({A: [B], B: [JAVA]},
                                  {A: [B], B: [JAVA]},
                                  {A: "SECONDARY_OEM_DEX",
                                   B: "PRIMARY_OEM_DEX"}, A)

    def test_buckets_only_allow_constant_status(self):
        self.assertEqual([bucket(i) for i in (0, 1, 3, 4, 16)],
                         ["NONE", "ONE", "TWO_TO_THREE",
                          "FOUR_TO_FIFTEEN", "SIXTEEN_OR_MORE"])

    def test_report_contains_no_private_descriptor_or_path(self):
        report = summarize_linkage({A: [C]}, {A: [C]},
                                   {A: "SECONDARY_OEM_DEX"}, A)
        result = json.dumps(report)
        self.assertNotIn("test/fixture", result)
        self.assertNotIn("MissingLib", result)


class CrashSelectionTests(unittest.TestCase):
    def setUp(self):
        # Source CI has the real smoke parser. The deliberately minimal test
        # double isolates selection from Android, logcat and proprietary data.
        fixture = types.ModuleType("android17_start_smoke")
        fixture.ROOT_CAUSE = re.compile(
            r"^\s*(Caused by:\s*)?([A-Za-z_$][\w.$]*(?:Exception|Error))(?::|$)")
        fixture._android_runtime_message = lambda row: row
        fixture.MISSING_QUOTED = re.compile(
            r'ClassNotFoundException:\s*Didn.t find class\s+"((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*)"')
        fixture.MISSING_DIRECT = re.compile(
            r'ClassNotFoundException:\s*((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*)(?=\s|$)')
        fixture.MAX_FATAL_BLOCK_LINES = 256
        fixture.classify_crash = lambda log: {
            "target_crash": "Process: io.lxsdd.yeelightdj, PID:" in log,
            "exception_category": ("CLASS_LOADER_MISSING" if
                                   "ClassNotFoundException:" in log
                                   else "OTHER_FATAL_RUNTIME")}
        patch = mock.patch.dict(sys.modules, {"android17_start_smoke": fixture})
        patch.start()
        self.addCleanup(patch.stop)

    def test_foreign_crash_cannot_contaminate_target(self):
        log = ("FATAL EXCEPTION: main\nProcess: other.app, PID: 3\n"
               "Caused by: java.lang.ClassNotFoundException: test.fixture.Secret\n"
               "FATAL EXCEPTION: main\nProcess: io.lxsdd.yeelightdj, PID: 4\n"
               "Caused by: java.lang.ClassNotFoundException: test.fixture.Target\n")
        self.assertEqual(target_crash_descriptor(log),
                         ("TARGET_CLASS_IDENTIFIED_PRIVATELY", A))

    def test_ambiguous_classnames_are_not_reported(self):
        log = ("FATAL EXCEPTION: main\nProcess: io.lxsdd.yeelightdj, PID: 4\n"
               "Caused by: java.lang.ClassNotFoundException: test.fixture.Target\n"
               "Caused by: java.lang.ClassNotFoundException: test.fixture.Another\n")
        self.assertEqual(target_crash_descriptor(log)[0],
                         "MISSING_CLASS_AMBIGUOUS_OR_UNPARSEABLE")

    def test_no_target_fatal(self):
        self.assertEqual(target_crash_descriptor(
            "FATAL EXCEPTION: main\nProcess: foreign.app, PID: 3\n"),
            ("NO_TARGET_FATAL", None))

    def test_bad_log_type_and_size(self):
        for bad in (b"binary", "x" * 2_000_001):
            with self.assertRaisesRegex(ForensicsBlocked, "CRASH_LOG_INVALID"):
                target_crash_descriptor(bad)


class CliImportAndStageFailureTests(unittest.TestCase):
    def test_real_script_import_layout_from_clean_python_process(self):
        """Catch the original private_ci -> classic_source import bug pre-merge.

        -I prevents PYTHONPATH/site/user imports from making a broken CI pass.
        The private_ci directory is the *only* initial application path, exactly
        as when GitHub Actions invokes the diagnostic file as a Python script.
        No OEM input or filesystem modification is needed.
        """
        script_dir = Path(__file__).resolve().parents[1] / "private_ci"
        program = (
            "import sys\n"
            f"sys.path.insert(0, {str(script_dir)!r})\n"
            "from pathlib import Path\n"
            "from classloader_linkage_forensics import load_dex_graphs, ForensicsBlocked\n"
            "try:\n"
            "    load_dex_graphs(Path('unavailable-stock.apk'), "
            "Path('unavailable-clone.apk'))\n"
            "except ForensicsBlocked as exc:\n"
            "    assert str(exc) == 'PRIVATE_GRAPH_UNREADABLE'\n"
            "else:\n"
            "    raise AssertionError('expected missing synthetic fixture')\n"
        )
        completed = subprocess.run(
            [sys.executable, "-I", "-c", program], cwd=str(script_dir),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20,
            check=False,
        )
        # Do not forward possible exception tracebacks containing private paths.
        self.assertEqual(completed.returncode, 0, "isolated CLI import regression")

    def test_unexpected_exceptions_become_fixed_phase_codes(self):
        secret = "PRIVATE_OEM_INTERNAL_SYMBOL_SHOULD_NOT_ESCAPE"
        for code in (
            "TARGET_CRASH_CLASSIFICATION_FAILED",
            "PRIVATE_GRAPH_ANALYSIS_FAILED",
            "CLASS_LINKAGE_ANALYSIS_FAILED",
            "ART_VERIFY_EXECUTION_FAILED",
        ):
            with self.subTest(code=code):
                def crash():
                    raise RuntimeError(secret)
                with self.assertRaises(ForensicsBlocked) as captured:
                    stage_guard(code, crash)
                self.assertEqual(str(captured.exception), code)
                self.assertNotIn(secret, str(captured.exception))

    def test_explicit_diagnostic_block_code_survives_phase_guard(self):
        def rejected():
            raise ForensicsBlocked("PRIVATE_GRAPH_UNREADABLE")
        with self.assertRaisesRegex(ForensicsBlocked, "^PRIVATE_GRAPH_UNREADABLE$"):
            stage_guard("PRIVATE_GRAPH_ANALYSIS_FAILED", rejected)

    def test_arbitrary_phase_codes_cannot_leak_private_data(self):
        operation = mock.Mock()
        with self.assertRaisesRegex(ForensicsBlocked, "^STAGE_CODE_INVALID$"):
            stage_guard("private/class/name/from-oem", operation)
        operation.assert_not_called()


class ArchiveAndRunnerTests(unittest.TestCase):
    def test_exact_archive_dex_sets_and_duplicate_rejection(self):
        with tempfile.TemporaryDirectory() as temp:
            original, clone = Path(temp) / "original.apk", Path(temp) / "clone.apk"
            with ZipFile(original, "w") as z:
                z.writestr("classes.dex", b"1")
                z.writestr("classes2.dex", b"2")
            with ZipFile(clone, "w") as z:
                z.writestr("classes.dex", b"1")
                z.writestr("classes2.dex", b"2")
                z.writestr("classes3.dex", b"3")
            fixture = types.ModuleType("private_startup_audit")
            fixture.dex_index = lambda b: {
                b"1": {B: [JAVA]}, b"2": {A: [B]},
                b"3": {"Ltest/fixture/Bridge;": [JAVA]}}[b]
            with mock.patch.dict(sys.modules, {"private_startup_audit": fixture}):
                old, new, locations = load_dex_graphs(original, clone)
                self.assertEqual(locations[A], "SECONDARY_OEM_DEX")
                self.assertEqual(len(old), 2)
                self.assertEqual(len(new), 3)
                with ZipFile(clone, "a") as z:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore")
                        z.writestr("classes2.dex", b"2")
                with self.assertRaisesRegex(ForensicsBlocked, "ZIP_DUPLICATE_MEMBER"):
                    load_dex_graphs(original, clone)

    def test_art_verifier_result_not_mislabeled_load_success(self):
        with self.assertRaisesRegex(ForensicsBlocked, "EMULATOR_SCOPE_INVALID"):
            verify_compiler("physical-device")
        ok = subprocess.CompletedProcess([], 0, b"Success", b"")
        with mock.patch("subprocess.run", return_value=ok) as invoked:
            self.assertEqual(verify_compiler("emulator-5554"),
                             "ART_VERIFY_COMMAND_ACCEPTED")
            self.assertEqual(invoked.call_args.args[0][-1], "io.lxsdd.yeelightdj")
        denied = subprocess.CompletedProcess([], 1, b"Failure", b"")
        with mock.patch("subprocess.run", return_value=denied):
            self.assertEqual(verify_compiler("emulator-5554"),
                             "ART_VERIFY_COMMAND_REJECTED")
        with mock.patch("subprocess.run",
                        side_effect=subprocess.TimeoutExpired([], 120)):
            self.assertEqual(verify_compiler("emulator-5554"),
                             "ART_VERIFY_COMMAND_UNAVAILABLE")

    def test_unapproved_runner_context_fails_before_adb(self):
        from argparse import Namespace
        with mock.patch.dict(os.environ,
                             {"RUNNER_TEMP": "/tmp/fixture",
                              "GITHUB_REPOSITORY": "other/repo"}):
            with mock.patch("subprocess.run") as cmd:
                with self.assertRaisesRegex(ForensicsBlocked,
                                            "OWNER_ONLY_RUNNER_REQUIRED"):
                    safe_main(Namespace(original=Path("a"), candidate=Path("b"),
                                        serial="emulator-5554"))
                cmd.assert_not_called()


if __name__ == "__main__":
    unittest.main()
