"""No OEM input: positive/negative synthetic contract of protected diagnosis."""
import contextlib
from io import StringIO
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
# Avoid manufacturer parsing from the synthetic tests. The bound import is
# intentionally mocked *before* import; CI also tests actual parser elsewhere.
import types

_synthetic_original_audit = types.ModuleType("private_startup_audit")
_synthetic_original_audit.original_prebuild_report = lambda path: None
_synthetic_first = types.ModuleType("classic_first")
_synthetic_first.ORIGINAL_SHA256 = "abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2"
with patch.dict(sys.modules, {"private_startup_audit": _synthetic_original_audit, "classic_first": _synthetic_first}):
    from private_ci import readonly_vault_compare as diagnostic

DEX1 = "1" * 64
DEX2 = "2" * 64
XML = "3" * 64
DEVICE = "4" * 64
PIN = diagnostic.ORIGINAL_SHA256


def receipt(*, pin=DEVICE, dex1=DEX1, dex2=DEX2, xml=XML):
    return ("DEVICE=synthetic-device\nAPK_HASH_VERIFIED=PASS\nDEX_ENTRIES=2\n"
            f"classes.dex={dex1}\nclasses2.dex={dex2}\n"
            f"AndroidManifest.xml={xml}\n"
            "APK_SAVED_TO_DISK=NO\nDEVICE_WRITES=NO\n"
            f"INSTALLED_BASE_SHA256={pin}\n")


def original_state(**changes):
    provenance = {
        "missing_count": 3, "missing_classes_waived": 0,
    }
    classes = {
        "original_missing_activity_count": 3,
        "original_unresolved_direct_parent_edges": 6,
        "installed_split_set_verified": False,
        "actual_classloader_verified": False,
        "runtime": "NOT_TESTED", "missing_classes_waived": 0,
    }
    baseline = {
        "status": "PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED",
        "real_oem_read": True, "runtime": "NOT_TESTED",
        "original_missing_activity_provenance": provenance,
        "original_classpath_provenance": classes,
        "manifest_missing_required_count": 3,
        "manifest_missing_by_role": {"activity": 3},
        "original_total_missing_parent_edges": 6,
        "original_missing_startup_parent_edges": 0,
        "original_declared_shared_libraries": 1,
    }
    baseline.update(changes)
    return baseline


def archive_report(*, archive=PIN, xml=XML, dex1=DEX1, dex2=DEX2):
    return {
        "archive_sha256": archive,
        "dex_names": ("classes.dex", "classes2.dex"),
        "dex_hashes": (dex1, dex2),
        "manifest_hash": xml,
    }


class ReadonlyVaultCompareTest(unittest.TestCase):
    def checked(self, secret=None, baseline=None, archive=None):
        if secret is None:
            secret = receipt()
        if baseline is None:
            baseline = original_state()
        if archive is None:
            archive = archive_report()
        with patch.object(diagnostic, "fingerprint", return_value=archive) as fp, \
             patch.object(diagnostic, "original_prebuild_report", return_value=baseline) as prebuild:
            report = diagnostic.diagnose("private-fixture-unopened.apk", secret)
        fp.assert_called_once_with("private-fixture-unopened.apk", PIN)
        prebuild.assert_called_once_with(Path("private-fixture-unopened.apk"))
        return report

    def test_known_oem_fail_closed_but_diagnostic_completes(self):
        report = self.checked()
        self.assertEqual(report["status"], "UNATTESTED_RECEIPT_DEX_AND_MANIFEST_EQUAL_ARCHIVE_DIFFERS")
        self.assertFalse(report["archive_equal"])
        self.assertTrue(report["dex_payloads_equal"])
        self.assertEqual(report["original_missing_activities"], 3)
        self.assertEqual(report["original_unresolved_parent_edges"], 6)
        self.assertEqual(report["original_startup_parent_edges"], 0)
        self.assertEqual(report["declared_shared_library_count"], 1)
        for key in ("device_receipt_independently_attested", "actual_classloader_verified",
                    "android_runtime_tested", "private_build_produced", "device_access_performed"):
            self.assertFalse(report[key])
        self.assertEqual(report["missing_classes_waived"], 0)
        self.assertNotIn("private-fixture", json.dumps(report))
        self.assertNotIn(DEX1, json.dumps(report))
        self.assertNotIn(DEVICE, json.dumps(report))

    def test_dex_only_difference_is_not_hidden_by_archive_difference(self):
        report = self.checked(archive=archive_report(dex1="a" * 64))
        self.assertEqual(report["status"], "UNATTESTED_RECEIPT_DEX_PAYLOAD_DIFFERS")
        self.assertFalse(report["dex_payloads_equal"])
        self.assertTrue(report["manifest_payload_equal"])

    def test_manifest_only_difference_is_classified(self):
        report = self.checked(archive=archive_report(xml="b" * 64))
        self.assertEqual(report["status"], "UNATTESTED_RECEIPT_DEX_EQUAL_MANIFEST_DIFFERS")
        self.assertTrue(report["dex_payloads_equal"])
        self.assertFalse(report["manifest_payload_equal"])

    def test_missing_receipt_archive_pin_denied_before_original_apk_read(self):
        bad = receipt().replace("INSTALLED_BASE_SHA256="+DEVICE+"\n", "")
        with patch.object(diagnostic, "fingerprint", side_effect=AssertionError("file read")):
            with self.assertRaises(diagnostic.ReadonlyBlocked):
                diagnostic.diagnose("will-not-open", bad)

    def test_untrusted_malformed_receipt_denied_before_original_apk_read(self):
        bad_values = [receipt().replace("DEVICE_WRITES=NO", "DEVICE_WRITES=YES"),
                      receipt() + "OEM_CLASS_NAME=nonpublic\n",
                      receipt().replace("classes2.dex="+DEX2, "classes2.dex=secret"),
                      receipt().replace("APK_HASH_VERIFIED=PASS", "APK_HASH_VERIFIED=FAIL"),
                      receipt() + "INSTALLED_BASE_SHA256="+DEVICE+"\n"]
        with patch.object(diagnostic, "fingerprint", side_effect=AssertionError("file read")):
            for secret in bad_values:
                with self.subTest(secret_length=len(secret)), self.assertRaises((diagnostic.VariantBlocked, diagnostic.ReadonlyBlocked)):
                    diagnostic.diagnose("not-read", secret)

    def test_mismatched_oem_graph_counters_rejected(self):
        for override in ({"manifest_missing_required_count": 2},
                         {"original_total_missing_parent_edges": 5},
                         {"original_missing_startup_parent_edges": 7},
                         {"real_oem_read": False},
                         {"runtime": "PASS"},
                         {"status": "OEM_ALLOW_ALL"},
                         {"manifest_missing_by_role": {"activity": 2}}):
            with self.subTest(override=override), self.assertRaises(diagnostic.ReadonlyBlocked):
                self.checked(baseline=original_state(**override))

    def test_classloader_claim_and_missing_waiver_rejected(self):
        for key,value in (("actual_classloader_verified", True),
                          ("installed_split_set_verified", True),
                          ("missing_classes_waived", 1),
                          ("runtime", "PASS")):
            baseline = original_state()
            baseline["original_classpath_provenance"][key] = value
            with self.subTest(key=key), self.assertRaises(diagnostic.ReadonlyBlocked):
                self.checked(baseline=baseline)
        baseline = original_state()
        baseline["original_missing_activity_provenance"]["missing_classes_waived"] = 1
        with self.assertRaises(diagnostic.ReadonlyBlocked):
            self.checked(baseline=baseline)

    def test_cli_never_prints_exception_receipt_path_or_hash(self):
        malicious = "private-original-class-sensitive"
        stdout = StringIO()
        with patch.object(diagnostic, "diagnose", side_effect=ValueError(malicious)), \
             patch("sys.stdin", StringIO(receipt())), \
             patch("sys.argv", ["script", "--original", malicious]), \
             contextlib.redirect_stdout(stdout):
            self.assertEqual(diagnostic.main(), 2)
        self.assertEqual(json.loads(stdout.getvalue()),
                         {"status": "BLOCKED", "reason": "READONLY_DIAGNOSTIC_UNVERIFIED"})
        self.assertNotIn(malicious, stdout.getvalue())

    def test_cli_unexpected_args_reject_without_reading_receipt(self):
        for argv in (["script"], ["script", "--original", "a", "--output", "b"],
                     ["script", "--candidate", "a"]):
            out = StringIO()
            with self.subTest(argv=argv), patch("sys.argv", argv), \
                 patch("sys.stdin", side_effect=AssertionError("unexpected stdin")), \
                 contextlib.redirect_stdout(out):
                self.assertEqual(diagnostic.main(), 2)
                self.assertEqual(json.loads(out.getvalue())["status"], "BLOCKED")

    def test_receipt_well_formed_extra_content_blocked(self):
        with self.assertRaises((diagnostic.ReadonlyBlocked, diagnostic.VariantBlocked)):
            diagnostic.diagnose("not-read", "x" * (diagnostic.MAX_RECEIPT + 1))


if __name__ == "__main__":
    unittest.main()
