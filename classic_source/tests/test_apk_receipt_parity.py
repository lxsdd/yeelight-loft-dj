"""Synthetic-only, negative-heavy read-only APK receipt parity regressions."""
import contextlib
import hashlib
from io import StringIO
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from private_ci.apk_variant_parity import (  # noqa: E402
    VariantBlocked, compare, fingerprint, fingerprint_receipt, main,
)


class ReceiptParityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def apk(self, name, *, manifest=b"fake-manifest", primary=b"fake-dex-1",
            secondary=b"fake-dex-2", third=None, extra=None):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", manifest)
            archive.writestr("classes.dex", primary)
            if secondary is not None:
                archive.writestr("classes2.dex", secondary)
            if third is not None:
                archive.writestr("classes3.dex", third)
            if extra is not None:
                archive.writestr("res/synthetic.txt", extra)
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def receipt(pair, *, device="Pixel 6a (bluejay)", archive_pin=True):
        data = fingerprint(*pair)
        content = [
            "DEVICE=" + device,
            "APK_HASH_VERIFIED=PASS",
            "DEX_ENTRIES=%d" % len(data["dex_names"]),
        ]
        content.extend("%s=%s" % (name, digest)
                       for name, digest in zip(data["dex_names"], data["dex_hashes"]))
        content += [
            "AndroidManifest.xml=" + data["manifest_hash"],
            "APK_SAVED_TO_DISK=NO",
            "DEVICE_WRITES=NO",
        ]
        if archive_pin:
            content.append("INSTALLED_BASE_SHA256=" + data["archive_sha256"])
        return "\n".join(content) + "\n"

    def cli(self, first_pair, second_pair, *, receipt=None, use_receipt=True):
        out = StringIO()
        args = (["--first-receipt-stdin"] if use_receipt else
                ["--first", str(first_pair[0])])
        args += ["--first-sha256", first_pair[1], "--second", str(second_pair[0]),
                 "--second-sha256", second_pair[1]]
        with patch("sys.stdin", StringIO(receipt if receipt is not None else
                                           self.receipt(first_pair))):
            with contextlib.redirect_stdout(out):
                code = main(args)
        return code, json.loads(out.getvalue())

    def test_receipt_and_identical_vault_payload_only_prove_digest_claim(self):
        original = self.apk("vault-fixture.apk")
        code, report = self.cli(original, original)
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "UNATTESTED_RECEIPT_IDENTICAL_ARCHIVE")
        self.assertTrue(report["dex_payloads_equal"])
        self.assertFalse(report["device_receipt_independently_attested"])
        self.assertFalse(report["actual_classloader_verified"])
        self.assertFalse(report["classes_verified"])
        self.assertFalse(report["android_runtime_tested"])
        self.assertTrue(report["diagnostic_only"])

    def test_receipt_equal_dex_and_manifest_repackaged_zip(self):
        first = self.apk("fake-play-store.apk")
        second = self.apk("fake-vault.apk", extra=b"different harmless asset")
        code, report = self.cli(first, second)
        self.assertEqual(code, 0)
        self.assertEqual(report["status"],
                         "UNATTESTED_RECEIPT_DEX_AND_MANIFEST_EQUAL_ARCHIVE_DIFFERS")
        self.assertFalse(report["archive_equal"])
        self.assertTrue(report["manifest_payload_equal"])

    def test_receipt_same_dex_different_manifest(self):
        first = self.apk("app1.apk")
        second = self.apk("app2.apk", manifest=b"different-manifest")
        code, report = self.cli(first, second)
        self.assertEqual(code, 0)
        self.assertEqual(report["status"],
                         "UNATTESTED_RECEIPT_DEX_EQUAL_MANIFEST_DIFFERS")

    def test_receipt_different_dex_payload(self):
        first = self.apk("app1.apk")
        second = self.apk("app2.apk", primary=b"different-dex")
        code, report = self.cli(first, second)
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "UNATTESTED_RECEIPT_DEX_PAYLOAD_DIFFERS")

    def test_receipt_different_dex_layout(self):
        first = self.apk("app1.apk")
        second = self.apk("app2.apk", third=b"third-dex")
        code, report = self.cli(first, second)
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "UNATTESTED_RECEIPT_DEX_LAYOUT_DIFFERS")

    def test_legacy_two_apk_cli_remains_unchanged(self):
        pair = self.apk("app.apk")
        code, report = self.cli(pair, pair, use_receipt=False)
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "IDENTICAL_ARCHIVE")
        self.assertNotIn("device_receipt_independently_attested", report)
        self.assertEqual(compare(fingerprint(*pair), fingerprint(*pair))["status"],
                         "IDENTICAL_ARCHIVE")

    def test_optional_archive_sha_receipt_field(self):
        pair = self.apk("app.apk")
        r = fingerprint_receipt(self.receipt(pair, archive_pin=False), pair[1])
        self.assertEqual(r["archive_sha256"], pair[1])

    def test_invalid_or_unpinned_receipts_are_fail_closed(self):
        pair = self.apk("fixture.apk")
        valid = self.receipt(pair)
        replacements = [
            ("APK_HASH_VERIFIED=PASS", "APK_HASH_VERIFIED=FAILED", "VARIANT_RECEIPT_READ_ONLY_UNVERIFIED"),
            ("DEVICE_WRITES=NO", "DEVICE_WRITES=YES", "VARIANT_RECEIPT_READ_ONLY_UNVERIFIED"),
            ("APK_SAVED_TO_DISK=NO", "APK_SAVED_TO_DISK=YES", "VARIANT_RECEIPT_READ_ONLY_UNVERIFIED"),
            ("DEX_ENTRIES=2", "DEX_ENTRIES=3", "VARIANT_RECEIPT_DEX_LAYOUT"),
            ("DEX_ENTRIES=2", "DEX_ENTRIES=0", "VARIANT_RECEIPT_DEX_COUNT"),
            ("DEX_ENTRIES=2", "DEX_ENTRIES=999", "VARIANT_RECEIPT_DEX_COUNT"),
            ("classes2.dex=", "classes4.dex=", "VARIANT_RECEIPT_DEX_LAYOUT"),
            ("INSTALLED_BASE_SHA256=", "INSTALLED_BASE_SHA256=" + "f" * 64, "VARIANT_RECEIPT_ARCHIVE_PIN_MISMATCH"),
        ]
        for old, new, expected in replacements:
            with self.subTest(old=old, new=new[:30]):
                changed = valid.replace(old, new, 1)
                with self.assertRaisesRegex(VariantBlocked, expected):
                    fingerprint_receipt(changed, pair[1])
        with self.assertRaisesRegex(VariantBlocked, "VARIANT_RECEIPT_ARCHIVE_PIN_MISMATCH"):
            fingerprint_receipt(valid, "0" * 64)
        with self.assertRaisesRegex(VariantBlocked, "VARIANT_PIN_INVALID"):
            fingerprint_receipt(valid, "not-a-hash")

    def test_duplicate_unknown_line_and_non_printable_rejected(self):
        pair = self.apk("fixture.apk")
        valid = self.receipt(pair)
        variants = [
            (valid + "DEVICE=Pixel 9\n", "VARIANT_RECEIPT_DUPLICATE"),
            (valid + "OEM_CLASS_NAME=private\n", "VARIANT_RECEIPT_UNKNOWN_FIELD"),
            (valid.replace("DEX_ENTRIES=2", "DEX_ENTRIES=2\x00"), "VARIANT_RECEIPT_FIELD"),
            (valid + "not_an_assignment\n", "VARIANT_RECEIPT_FIELD"),
            (valid + "\n", "VARIANT_RECEIPT_FIELD"),
            (valid + "DEVICE=foo=bar\n", "VARIANT_RECEIPT_FIELD"),
            (valid + "EXTRA=" + "x" * 20000, "VARIANT_RECEIPT_SHAPE"),
        ]
        for candidate, expected in variants:
            with self.subTest(expected=expected, input_length=len(candidate)):
                with self.assertRaisesRegex(VariantBlocked, expected):
                    fingerprint_receipt(candidate, pair[1])

    def test_invalid_digest_never_echos_synthetic_private_input(self):
        pair = self.apk("private-original-should-not-appear.apk")
        receipt = self.receipt(pair).replace(
            "AndroidManifest.xml=" + fingerprint(*pair)["manifest_hash"],
            "AndroidManifest.xml=private-blob")
        code, report = self.cli(pair, pair, receipt=receipt)
        self.assertEqual(code, 2)
        self.assertEqual(report, {"status": "BLOCKED", "reason": "VARIANT_RECEIPT_DIGEST_INVALID"})
        self.assertNotIn("private-blob", json.dumps(report))
        self.assertNotIn("private-original", json.dumps(report))

    def test_receipt_malformed_pin_does_not_open_second_file(self):
        pair = self.apk("fixture.apk")
        with patch("private_ci.apk_variant_parity.fingerprint", side_effect=AssertionError("must not read")):
            code, report = self.cli(pair, pair, receipt="DEVICE=Bad\n")
        self.assertEqual(code, 2)
        self.assertEqual(report["reason"], "VARIANT_RECEIPT_READ_ONLY_UNVERIFIED")

    def test_receiver_does_not_mutate_input_archive(self):
        pair = self.apk("fixture.apk")
        old = pair[0].read_bytes()
        code, report = self.cli(pair, pair)
        self.assertEqual(code, 0)
        self.assertEqual(pair[0].read_bytes(), old)
        self.assertTrue(report["diagnostic_only"])


if __name__ == "__main__":
    unittest.main()
