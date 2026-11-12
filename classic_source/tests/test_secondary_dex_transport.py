"""Synthetic positive/negative ZIP transport and signed parity gates; no OEM APK."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import warnings
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from secondary_dex_transport import normalize_private_multidex, DexTransportBlocked
from private_payload_parity import parity, Refused

class SecondaryDexTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.original = root / "original.apk"
        self.candidate = root / "clone.apk"
        self.signed = root / "signed.apk"
        self.entries = {
            "AndroidManifest.xml": b"XML_BLOB",
            "classes.dex": b"dex\n035\0" + b"a" * 200,
            "classes2.dex": b"dex\n035\0" + b"b" * 200,
            "classes3.dex": b"dex\n035\0" + b"c" * 200,
            "resources.arsc": b"RESOURCE" * 200,
        }
        self.make_fixture()

    def make_fixture(self, *, omit=False, duplicate=False, compressed=True):
        with ZipFile(self.original, "w") as z:
            for name in ("AndroidManifest.xml", "classes.dex", "classes2.dex"):
                z.writestr(name, self.entries[name], compress_type=ZIP_STORED)
        with ZipFile(self.candidate, "w") as z:
            for name, data in self.entries.items():
                if name == "classes2.dex" and omit:
                    continue
                use_deflate = name == "resources.arsc" or (
                    compressed and name in ("classes2.dex", "classes3.dex"))
                z.writestr(name, data, compress_type=ZIP_DEFLATED if use_deflate else ZIP_STORED)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    z.writestr("classes2.dex", self.entries["classes2.dex"])

    def test_normalizes_only_clone_storage_and_preserves_every_payload(self):
        original_digest = hashlib.sha256(self.original.read_bytes()).hexdigest()
        with ZipFile(self.candidate) as z:
            before = {n: z.read(n) for n in z.namelist()}
        result = normalize_private_multidex(self.original, self.candidate)
        self.assertTrue(result["transport_changed"])
        self.assertTrue(result["original_secondary_stored"])
        self.assertFalse(result["candidate_secondary_stored_before"])
        self.assertFalse(result["oem_dex_payload_changed"])
        with ZipFile(self.candidate) as z:
            self.assertEqual({n: z.read(n) for n in z.namelist()}, before)
            self.assertTrue(all(z.getinfo(n).compress_type == ZIP_STORED
                                for n in ("classes.dex", "classes2.dex", "classes3.dex")))
            self.assertEqual(z.getinfo("resources.arsc").compress_type, ZIP_DEFLATED)
        self.assertEqual(hashlib.sha256(self.original.read_bytes()).hexdigest(), original_digest)
        self.assertFalse(normalize_private_multidex(self.original, self.candidate)["transport_changed"])
        with ZipFile(self.candidate, "r") as zin, ZipFile(self.signed, "w") as out:
            for info in zin.infolist():
                out.writestr(info, zin.read(info.filename))
        self.assertEqual(parity(self.candidate, self.signed)["status"],
                         "SIGNED_STARTUP_PAYLOAD_IDENTICAL_NOT_RUNTIME_TESTED")

    def test_rejects_duplicate_and_missing_secondary_without_mutation(self):
        for kwargs, reason in (({"duplicate": True}, "MULTIDEX_DUPLICATE_ZIP_MEMBER"),
                               ({"omit": True}, "MULTIDEX_ENTRY_SET_CHANGED")):
            with self.subTest(kwargs=kwargs):
                self.make_fixture(**kwargs)
                original = self.candidate.read_bytes()
                with self.assertRaisesRegex(DexTransportBlocked, reason):
                    normalize_private_multidex(self.original, self.candidate)
                self.assertEqual(self.candidate.read_bytes(), original)

    def test_rejects_symlink_and_identical_original_output(self):
        with self.assertRaisesRegex(DexTransportBlocked, "MULTIDEX_INPUT_INVALID"):
            normalize_private_multidex(self.original, self.original)
        link = self.candidate.parent / "linked.apk"
        link.symlink_to(self.candidate)
        with self.assertRaisesRegex(DexTransportBlocked, "MULTIDEX_INPUT_INVALID"):
            normalize_private_multidex(self.original, link)

    def test_signed_payload_parity_refuses_compressed_secondary(self):
        with ZipFile(self.candidate, "r") as src, ZipFile(self.signed, "w") as out:
            for info in src.infolist():
                out.writestr(info, src.read(info.filename))
        with self.assertRaisesRegex(Refused, "SIGNED_MULTIDEX_NOT_ZIP_STORED"):
            parity(self.candidate, self.signed)


    def test_private_transport_status_is_allowlisted_and_never_leaks_input(self):
        from secondary_dex_transport import private_multidex_status_lines
        report = {"secondary_dex_transport": {
            "status": "PRIVATE_MULTIDEX_ZIP_STORED_RUNTIME_UNVERIFIED",
            "original_secondary_stored": True,
            "candidate_secondary_stored_before": False,
            "all_candidate_dex_stored_after": True,
            "transport_changed": True,
            "oem_dex_payload_changed": False,
            "oem_private_class_name": "com.yeelight.secret.OEMClass",
            "private_path": "/tmp/private/foo.apk",
        }}
        lines = private_multidex_status_lines(report)
        self.assertEqual(lines, (
            "PRIVATE_CLASSIC_ORIGINAL_SECONDARY_DEX_STORED=YES",
            "PRIVATE_CLASSIC_CLONE_SECONDARY_DEX_PREVIOUSLY_STORED=NO",
            "PRIVATE_CLASSIC_MULTIDEX_ZIP_REPACK_APPLIED=YES",
        ))
        self.assertNotIn("yeelight.secret", str(lines))
        self.assertNotIn("/tmp/private", str(lines))

    def test_private_transport_status_rejects_untyped_or_unverified_data(self):
        from secondary_dex_transport import private_multidex_status_lines, DexTransportBlocked
        base = {"status": "PRIVATE_MULTIDEX_ZIP_STORED_RUNTIME_UNVERIFIED",
                "original_secondary_stored": False,
                "candidate_secondary_stored_before": False,
                "all_candidate_dex_stored_after": True,
                "transport_changed": True,
                "oem_dex_payload_changed": False}
        for change in ({"original_secondary_stored": "True"},
                       {"all_candidate_dex_stored_after": False},
                       {"oem_dex_payload_changed": True},
                       {"status": "FAKE_PASS"},
                       {"transport_changed": None}):
            with self.subTest(change=change):
                val = {**base, **change}
                with self.assertRaises(DexTransportBlocked):
                    private_multidex_status_lines({"secondary_dex_transport": val})


if __name__ == "__main__":
    unittest.main()
