"""Only synthetic bytes and fake cloud metadata. No downloads or credentials."""
import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from color4_stock_artifact_audit import audit, _unique_pairs


class StockImageAuditTest(unittest.TestCase):
    fw = b"synthetic image bytes for tests, not a Yeelight binary"

    def meta(self, **changes):
        r = {"version": "2.1.7_0039",
             "md5": hashlib.md5(self.fw).hexdigest(),
             "url": "https://example.invalid/private?Signature=DO_NOT_LOG",
             "safe_url": "https://example.invalid?serviceToken=DO_NOT_LOG"}
        r.update(changes)
        return r

    def test_correct_version_and_md5_is_not_proof_of_ota_eligibility(self):
        result = audit(self.meta(), self.fw)
        self.assertEqual("METADATA_AND_BYTES_MATCH_ONLY", result["status"])
        self.assertFalse(result["installable"])
        self.assertFalse(result["oem_signature_verified"])
        self.assertFalse(result["proves_quota_fix"])
        self.assertFalse(result["metadata_model_field_present"])

    def test_nested_xiaomi_response_is_accepted_without_exposing_urls(self):
        result = audit({"code": 0, "result": self.meta()}, self.fw)
        self.assertEqual(result["status"], "METADATA_AND_BYTES_MATCH_ONLY")
        self.assertNotIn("DO_NOT_LOG", str(result))
        self.assertNotIn("Signature", str(result))

    def test_older_version_request_cannot_silently_return_current(self):
        result = audit(self.meta(), self.fw, expected_version="2.1.7_0038")
        self.assertEqual("REJECTED", result["status"])
        self.assertIn("version", result["reason"])

    def test_wrong_model_field_cannot_be_accepted(self):
        result = audit(self.meta(model="yeelink.light.ceiling1"), self.fw)
        self.assertEqual("REJECTED", result["status"])
        self.assertIn("model", result["reason"])

    def test_correct_explicit_model_field_is_not_authenticity(self):
        result = audit(self.meta(model="yeelink.light.color4"), self.fw)
        self.assertTrue(result["metadata_model_field_present"])
        self.assertFalse(result["oem_signature_verified"])

    def test_corrupt_binary_is_rejected_even_with_valid_version(self):
        result = audit(self.meta(), self.fw + b"x")
        self.assertEqual("REJECTED", result["status"])
        self.assertIn("MD5", result["reason"])

    def test_missing_md5_fails(self):
        m = self.meta()
        del m["md5"]
        self.assertEqual("REJECTED", audit(m, self.fw)["status"])

    def test_missing_version_fails(self):
        m = self.meta()
        del m["version"]
        self.assertEqual("REJECTED", audit(m, self.fw)["status"])

    def test_bad_version_and_md5_types_fail(self):
        for invalid in (None, 391, ["2.1.7_0039"]):
            self.assertEqual("REJECTED", audit(self.meta(version=invalid), self.fw)["status"])
            self.assertEqual("REJECTED", audit(self.meta(md5=invalid), self.fw)["status"])

    def test_empty_binary_and_huge_binary_fail_closed(self):
        self.assertEqual("REJECTED", audit(self.meta(), b"")["status"])
        self.assertEqual("REJECTED", audit(self.meta(), b"x" * (32 * 1024 * 1024 + 1))["status"])

    def test_duplicate_keys_rejected_not_last_one_wins(self):
        with self.assertRaises(ValueError):
            _unique_pairs([("version", "2.1.7_0038"), ("version", "2.1.7_0039")])

    def test_stray_unknown_cloud_fields_cannot_trigger_installation(self):
        r = audit(self.meta(install=True, autoUpgrade=True), self.fw)
        self.assertFalse(r["installable"])
        self.assertFalse(r["oem_signature_verified"])


if __name__ == "__main__":
    unittest.main()
