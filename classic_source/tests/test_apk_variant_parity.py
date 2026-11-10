"""Only synthetic ZIP fixtures: no manufacturer APK, network or device access."""
import contextlib
import hashlib
from io import StringIO
from pathlib import Path
import tempfile
import unittest
import zipfile

from private_ci.apk_variant_parity import VariantBlocked, compare, fingerprint, main


class VariantParityTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def make(self, name, *, manifest=b"synthetic-xml", dex=b"synthetic-dex-1",
             second=b"synthetic-dex-2", extra=None, duplicate=False):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", manifest)
            archive.writestr("classes.dex", dex)
            if second is not None:
                archive.writestr("classes2.dex", second)
            if extra is not None:
                archive.writestr("assets/readme.txt", extra)
            if duplicate:
                archive.writestr("classes.dex", b"other")
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def fp(self, pair):
        return fingerprint(*pair)

    def test_identical_apk(self):
        first = self.make("original.apk")
        report = compare(self.fp(first), self.fp(first))
        self.assertEqual(report["status"], "IDENTICAL_ARCHIVE")
        self.assertTrue(report["dex_payloads_equal"])
        self.assertFalse(report["classes_verified"])
        self.assertFalse(report["android_runtime_tested"])

    def test_equal_code_repackaged(self):
        a = self.make("first.apk")
        b = self.make("second.apk", extra=b"different ZIP asset")
        report = compare(self.fp(a), self.fp(b))
        self.assertEqual(report["status"], "DEX_AND_MANIFEST_EQUAL_ARCHIVE_DIFFERS")
        self.assertFalse(report["archive_equal"])

    def test_manifest_only_changed(self):
        a = self.make("first.apk")
        b = self.make("second.apk", manifest=b"another-synthetic-xml")
        report = compare(self.fp(a), self.fp(b))
        self.assertEqual(report["status"], "DEX_EQUAL_MANIFEST_DIFFERS")
        self.assertTrue(report["dex_payloads_equal"])

    def test_dex_contents_changed(self):
        a = self.make("first.apk")
        b = self.make("second.apk", second=b"different-synthetic-dex")
        report = compare(self.fp(a), self.fp(b))
        self.assertEqual(report["status"], "DEX_PAYLOAD_DIFFERS")
        self.assertFalse(report["dex_payloads_equal"])

    def test_dex_layout_changed(self):
        a = self.make("first.apk")
        b = self.make("second.apk", second=None)
        report = compare(self.fp(a), self.fp(b))
        self.assertEqual(report["status"], "DEX_LAYOUT_DIFFERS")

    def test_wrong_pin_fails_without_payload_details(self):
        apk = self.make("private-identity-not-to-disclose.apk")
        with self.assertRaisesRegex(VariantBlocked, "VARIANT_PIN_MISMATCH") as exc:
            fingerprint(apk[0], "0" * 64)
        self.assertNotIn("private-identity", str(exc.exception))

    def test_duplicate_zip_member_fails_closed(self):
        with contextlib.redirect_stderr(StringIO()):
            apk = self.make("duplicated.apk", duplicate=True)
        with self.assertRaisesRegex(VariantBlocked, "VARIANT_DUPLICATE_ZIP_MEMBER"):
            self.fp(apk)

    def test_non_existent_or_symlink_input_fails_closed(self):
        with self.assertRaisesRegex(VariantBlocked, "VARIANT_FILE_OR_ARCHIVE_UNREADABLE"):
            fingerprint(self.root / "missing", "0" * 64)
        apk = self.make("original.apk")
        link = self.root / "link.apk"
        link.symlink_to(apk[0])
        with self.assertRaisesRegex(VariantBlocked, "VARIANT_FILE_NOT_REGULAR"):
            fingerprint(link, apk[1])

    def test_cli_never_prints_private_names_or_member_hashes(self):
        a = self.make("secret-private-filename-1.apk")
        b = self.make("secret-private-filename-2.apk", second=b"changed")
        out = StringIO()
        with contextlib.redirect_stdout(out):
            result = main(["--first", str(a[0]), "--first-sha256", a[1],
                           "--second", str(b[0]), "--second-sha256", b[1]])
        self.assertEqual(result, 0)
        self.assertIn("DEX_PAYLOAD_DIFFERS", out.getvalue())
        self.assertNotIn("secret-private-filename", out.getvalue())
        self.assertNotIn(a[1], out.getvalue())


if __name__ == "__main__":
    unittest.main()
