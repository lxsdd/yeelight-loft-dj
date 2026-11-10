"""Purely synthetic regression for the wired OEM candidate inventory preflight.

This suite uses its own temporary ZIP and DEX fixture. It never reads, logs,
signs or modifies original OEM APKs or any user/device state.
"""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import private_startup_audit as audit
from test_private_startup_audit import model, fake_dex


class PinnedOriginalClasspathAuditTests(unittest.TestCase):
    def fixture(self, *, missing_activity=False, parent_gap=False):
        original, _ = model()
        if missing_activity:
            original["definitions"].pop("Lcom/yeelight/cherry/Activity0;")
        if parent_gap:
            original["definitions"]["Lcom/yeelight/cherry/Activity1;"] = ["Lfixture/AbsentParent;"]
        return original

    def report(self, original, candidate_specs=()):
        with patch.object(audit, "inspect_original"), patch.object(
                audit, "inspect_apk", return_value=original):
            return audit.original_prebuild_report(
                Path("synthetic-no-oem.apk"), candidate_specs=candidate_specs)

    def test_original_complete_classpath_without_candidates(self):
        report = self.report(self.fixture())
        cp = report["original_classpath_provenance"]
        self.assertEqual(cp["original_missing_activity_count"], 0)
        self.assertEqual(cp["original_unresolved_direct_parent_edges"], 0)
        self.assertEqual(cp["activities_without_candidate"], 0)
        self.assertFalse(cp["actual_classloader_verified"])
        self.assertTrue(audit.qualify_original_prebuild(report))

    def test_missing_activity_and_parent_still_block_without_splits(self):
        report = self.report(self.fixture(missing_activity=True, parent_gap=True))
        cp = report["original_classpath_provenance"]
        self.assertEqual(cp["original_missing_activity_count"], 1)
        self.assertEqual(cp["original_unresolved_direct_parent_edges"], 1)
        self.assertEqual(cp["activities_without_candidate"], 1)
        self.assertEqual(cp["parent_edges_without_candidate"], 1)
        self.assertEqual(cp["missing_classes_waived"], 0)
        with self.assertRaisesRegex(audit.AuditBlocked, "ORIGINAL_PREBUILD_REQUIRED_CLASS_UNVERIFIED"):
            audit.qualify_original_prebuild(report)

    def test_pinned_candidate_does_not_remove_original_qualification_gate(self):
        with tempfile.TemporaryDirectory() as td:
            source = Path(td) / "synthetic.zip"
            with ZipFile(source, "w") as z:
                z.writestr("classes.dex", fake_dex())
            sha = hashlib.sha256(source.read_bytes()).hexdigest()
            report = self.report(self.fixture(missing_activity=True, parent_gap=True),
                                 candidate_specs=((source, sha, "split_apk"),))
            cp = report["original_classpath_provenance"]
            self.assertEqual(cp["candidate_sources_by_kind"]["split_apk"], 1)
            self.assertEqual(cp["original_missing_activity_count"], 1)
            self.assertEqual(cp["original_unresolved_direct_parent_edges"], 1)
            self.assertFalse(cp["actual_classloader_verified"])
            self.assertFalse(cp["installed_split_set_verified"])
            self.assertEqual(cp["missing_classes_waived"], 0)
            with self.assertRaisesRegex(audit.AuditBlocked, "ORIGINAL_PREBUILD_REQUIRED_CLASS_UNVERIFIED"):
                audit.qualify_original_prebuild(report)

    def test_pinned_candidate_bad_hash_fails_without_disclosing_path(self):
        with tempfile.TemporaryDirectory() as td:
            source = Path(td) / "private-name-must-not-be-logged.zip"
            with ZipFile(source, "w") as z:
                z.writestr("classes.dex", fake_dex())
            with self.assertRaises(audit.SourceInventoryError) as e:
                self.report(self.fixture(), candidate_specs=((source, "0"*64, "split_apk"),))
            self.assertEqual(str(e.exception), "CANDIDATE_SOURCE_PIN_MISMATCH")
            self.assertNotIn("private-name", str(e.exception))

    def test_fake_report_or_installed_claim_never_passes(self):
        report = self.report(self.fixture())
        report["original_classpath_provenance"]["actual_classloader_verified"] = True
        with self.assertRaisesRegex(audit.AuditBlocked, "ORIGINAL_CLASSPATH_PROVENANCE_REPORT_INVALID"):
            audit.qualify_original_prebuild(report)

    def test_no_vendor_names_in_serialized_aggregate(self):
        report = self.report(self.fixture(missing_activity=True, parent_gap=True))
        text = json.dumps(report, sort_keys=True)
        self.assertNotIn("Lcom/yeelight/", text)
        self.assertNotIn("Lfixture/AbsentParent;", text)
        self.assertNotIn("synthetic-no-oem.apk", text)


if __name__ == "__main__":
    unittest.main()
