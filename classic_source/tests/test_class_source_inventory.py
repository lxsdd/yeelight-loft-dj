"""Synthetic ZIP/DEX fixtures only. No Yeelight APK, source or real classnames."""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from class_source_inventory import (inventory_candidate_apk, sanitized_candidate,
                                    SourceInventoryError)
from activity_provenance import classify_original_classpath

GRAPH = {"Lfixture/AbsentActivity;": ["Ljava/lang/Object;"]}


def reader(payload):
    if payload == b"fixture-dex":
        return GRAPH
    if payload == b"fixture-dup":
        return GRAPH
    raise ValueError("secret fixture class /path/fake.apk must NOT leak")


class SourceInventoryTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.path = Path(self.work.name) / "secret-candidate.apk"

    def put(self, entries):
        with ZipFile(self.path, "w") as out:
            for key, value in entries:
                out.writestr(key, value)
        return hashlib.sha256(self.path.read_bytes()).hexdigest()

    def load(self, pin, *, kind="split_apk", decoder=reader):
        return inventory_candidate_apk(self.path, pin, kind=kind, dex_reader=decoder)

    def test_pinned_split_candidates_not_loader_proof(self):
        pin = self.put([("classes.dex", b"fixture-dex")])
        r = self.load(pin)
        self.assertTrue(r["pinned_artifact_verified"])
        self.assertFalse(r["installation_verified"])
        self.assertFalse(r["classloader_verified"])
        self.assertEqual(r["dex_files"], 1)
        clean = sanitized_candidate(r)
        self.assertEqual(set(clean), {"kind", "definitions"})
        summary = classify_original_classpath({}, set(GRAPH),
                 system_prefixes=("Ljava/",), supplemental_sources=[clean])
        self.assertEqual(summary["activities_without_candidate"], 0)
        self.assertEqual(summary["original_missing_activity_count"], 1)
        self.assertFalse(summary["actual_classloader_verified"])
        self.assertEqual(summary["missing_classes_waived"], 0)
        self.assertNotIn("fixture", json.dumps(summary))

    def test_declared_library_apk_not_an_accepted_classpath(self):
        pin = self.put([("classes.dex", b"fixture-dex")])
        r = self.load(pin, kind="shared_library")
        self.assertFalse(r["installation_verified"])
        self.assertEqual(r["kind"], "shared_library")

    def test_pin_mismatch_fails_without_looking_at_content(self):
        self.put([("classes.dex", b"fixture-dex")])
        with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_PIN_MISMATCH"):
            self.load("0" * 64)

    def test_duplicate_zip_name_rejected(self):
        pin = self.put([("classes.dex", b"fixture-dex"), ("classes.dex", b"fixture-dup")])
        with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_DUPLICATE_ZIP_ENTRY"):
            self.load(pin)

    def test_duplicate_multidex_classes_rejected(self):
        pin = self.put([("classes.dex", b"fixture-dex"), ("classes2.dex", b"fixture-dup")])
        with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_DUPLICATE_CLASS"):
            self.load(pin)

    def test_missing_or_invalid_dex_structure_rejected(self):
        for entries, error in [([("notclasses.bin", b"no")], "CANDIDATE_SOURCE_NO_PRIMARY_DEX"),
                               ([("classes1.dex", b"fixture-dex")], "CANDIDATE_SOURCE_DEX_NAME"),
                               ([("classes.dex", b"garbage")], "CANDIDATE_SOURCE_UNREADABLE")]:
            with self.subTest(error=error):
                pin = self.put(entries)
                with self.assertRaisesRegex(SourceInventoryError, error):
                    self.load(pin)

    def test_secret_never_appears_in_error(self):
        pin = self.put([("classes.dex", b"secret /private/folder")])
        with self.assertRaises(SourceInventoryError) as e:
            self.load(pin)
        self.assertNotIn("secret", str(e.exception))
        self.assertNotIn("private", str(e.exception))

    def test_invalid_pin_kind_or_decoder_fails(self):
        pin = self.put([("classes.dex", b"fixture-dex")])
        for kw in ({"kind": "unsafe"}, {"decoder": None}):
            with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_BAD_ARGUMENTS"):
                self.load(pin, **kw)

    def test_unverified_source_cannot_cross_classifier_boundary(self):
        with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_UNVERIFIED"):
            sanitized_candidate({"kind": "split_apk", "definitions": GRAPH,
                                 "pinned_artifact_verified": False,
                                 "classloader_verified": False,
                                 "installation_verified": False})


class PinnedSourceListTests(unittest.TestCase):
    def test_list_validation_fail_closed(self):
        from class_source_inventory import pinned_candidate_graphs
        for invalid in (None, [("only-a-path",)], [()] * 17):
            with self.subTest(invalid=str(type(invalid))):
                with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_LIST_INVALID"):
                    pinned_candidate_graphs(invalid, reader)

    def test_no_inventory_is_not_claim_of_classpath_completeness(self):
        from class_source_inventory import pinned_candidate_graphs
        self.assertEqual(pinned_candidate_graphs([], reader), [])
        r = classify_original_classpath({}, set(GRAPH), system_prefixes=("Ljava/",),
                                        supplemental_sources=[])
        self.assertEqual(r["activities_without_candidate"], 1)
        self.assertEqual(r["status"], "ORIGINAL_CLASSPATH_PROVENANCE_UNVERIFIED")

    def test_symlink_not_followed(self):
        with tempfile.TemporaryDirectory() as temp:
            original = Path(temp) / "real.apk"
            with ZipFile(original, "w") as zip_:
                zip_.writestr("classes.dex", b"fixture-dex")
            link = Path(temp) / "link.apk"
            try:
                link.symlink_to(original)
            except (OSError, NotImplementedError):
                self.skipTest("symlinks unavailable")
            sha = hashlib.sha256(original.read_bytes()).hexdigest()
            with self.assertRaisesRegex(SourceInventoryError, "CANDIDATE_SOURCE_UNREADABLE"):
                inventory_candidate_apk(link, sha, kind="split_apk", dex_reader=reader)


if __name__ == "__main__":
    unittest.main()
