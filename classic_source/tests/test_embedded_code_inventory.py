"""Synthetic bytecode inventory, candidate-only; never accesses manufacturer APK."""
from io import BytesIO
from zipfile import ZipFile
import json
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from embedded_code_inventory import EmbeddedInventoryBlocked, classify_embedded_code

ACTIVITY="Lsynthetic/MissingActivity;"
PARENT="Lsynthetic/MissingParent;"
PRESENT="Lsynthetic/Present;"


def fake_code(marker):
    return b"dex\n039\0" + marker.encode("ascii")


def reader(raw):
    if raw == fake_code("missing-activity"):
        return {ACTIVITY:(PRESENT,)}
    if raw == fake_code("missing-parent"):
        return {PARENT:(PRESENT,),PRESENT:(PARENT,)}
    if raw == fake_code("bad"):
        raise ValueError("secret internal vendor descriptor")
    return {}


def zip_fixture(*, nested=True, corrupt=False, oversize=False):
    buffer=BytesIO()
    with ZipFile(buffer,"w") as archive:
        archive.writestr("classes.dex",fake_code("already-verified-primary"))
        archive.writestr("classes2.dex",fake_code("already-verified-secondary"))
        archive.writestr("assets/private-routing.bin",fake_code("missing-activity"))
        archive.writestr("lib/arm64-v8a/vendor.so",b"\x7fELF"+b"test")
        if nested:
            internal=BytesIO()
            with ZipFile(internal,"w") as nestedzip:
                nestedzip.writestr("module/classes.dex",fake_code("missing-parent"))
            archive.writestr("assets/secret-data.dat",
                             b"PK\x03\x04bogus" if corrupt else internal.getvalue())
    buffer.seek(0)
    return buffer


def inspect(blob, **kw):
    with ZipFile(blob) as archive:
        return classify_embedded_code(
            archive,{PRESENT:(PARENT,)},{ACTIVITY},{PARENT},reader)


class EmbeddedCodeInventoryTests(unittest.TestCase):
    def test_embedded_dex_nested_archive_and_native_symbols_stay_private(self):
        report=inspect(zip_fixture())
        self.assertEqual(report["embedded_dex_containers"],1)
        self.assertEqual(report["embedded_nested_zip_containers"],1)
        self.assertEqual(report["embedded_nested_dex_containers"],1)
        self.assertEqual(report["embedded_native_elf_containers"],1)
        self.assertEqual(report["embedded_missing_activity_candidate_count"],1)
        self.assertEqual(report["embedded_missing_parent_candidate_count"],1)
        self.assertEqual(report["embedded_duplicate_original_definition_count"],1)
        self.assertEqual(report["uninspected_nested_archives"],0)
        self.assertFalse(report["code_candidates_prove_runtime_loader"])
        data=json.dumps(report)
        for token in ("synthetic","private-routing","secret-data","MissingActivity"):
            self.assertNotIn(token,data)

    def test_absent_nested_source_not_evidence_no_external_classpath(self):
        report=inspect(zip_fixture(nested=False))
        self.assertEqual(report["embedded_missing_parent_candidate_count"],0)
        self.assertFalse(report["code_candidates_prove_runtime_loader"])

    def test_malformed_nested_archive_is_unqualified_not_exception_leak(self):
        report=inspect(zip_fixture(corrupt=True))
        self.assertEqual(report["uninspected_nested_archives"],1)
        self.assertEqual(report["embedded_missing_activity_candidate_count"],1)
        self.assertEqual(report["embedded_missing_parent_candidate_count"],0)

    def test_malformed_embedded_dex_fails_closed_without_vendor_text(self):
        buf=BytesIO()
        with ZipFile(buf,"w") as z:
            z.writestr("assets/hidden.bin",fake_code("bad"))
        buf.seek(0)
        with self.assertRaisesRegex(EmbeddedInventoryBlocked,"PRIVATE_EMBEDDED_CODE_UNVERIFIED"):
            inspect(buf)

    def test_duplicate_zip_name_blocks_before_scanning(self):
        import warnings
        buf=BytesIO()
        with ZipFile(buf,"w") as z:
            z.writestr("assets/a",fake_code("missing-activity"))
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                z.writestr("assets/a",fake_code("missing-parent"))
        buf.seek(0)
        with self.assertRaises(EmbeddedInventoryBlocked):
            inspect(buf)

if __name__=="__main__":
    unittest.main()
