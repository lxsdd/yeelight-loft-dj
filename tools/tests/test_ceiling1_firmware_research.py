"""Synthetic reference tests only; no accounts, lamp access or OEM binaries."""
import hashlib
import struct
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ceiling1_firmware_research import inspect, xiaomi_crc32_zero_init

def md5(buf):
    return hashlib.md5(buf).hexdigest()

class Ceiling1FirmwareResearchTests(unittest.TestCase):
    def test_old_firmware_independent_hash_passes_without_proving_ota(self):
        data=b'fake test image - not a Xiaomi binary'
        from unittest.mock import patch
        import ceiling1_firmware_research as m
        with patch.object(m, "INDEPENDENT_MD5", md5(data)), \
             patch.object(m, "INDEPENDENT_BYTES", len(data)):
            r=inspect("1.5.9_0189",md5(data),data)
        self.assertEqual(r["status"],"PROVENANCE_CHECK_ONLY")
        self.assertTrue(r["independent_checksum_confirmed"])
        self.assertFalse(r["installable"])
        self.assertFalse(r["manufacturer_signature_verified"])

    def test_other_version_rejects_even_if_valid_md5(self):
        data=b'fake'
        r=inspect("2.0.6_0194",md5(data),data)
        self.assertEqual(r["status"],"REJECTED")

    def test_cloud_only_is_never_independently_authenticated(self):
        data=b'Q'*715000
        r=inspect("2.0.6_0193",md5(data),data)
        self.assertEqual(r["status"],"PROVENANCE_CHECK_ONLY")
        self.assertFalse(r["independent_checksum_confirmed"])
        self.assertFalse(r["installable"])

    def test_cloud_only_bad_md5_rejects(self):
        r=inspect("2.0.6_0193","f"*32,b"X"*715000)
        self.assertEqual(r["status"],"REJECTED")

    def test_cloud_only_bad_size_rejects(self):
        data=b"x"*10000
        r=inspect("2.0.6_0193",md5(data),data)
        self.assertEqual(r["status"],"REJECTED")

    def test_old_firmware_with_wrong_independent_md5_rejects(self):
        data=b"mock"
        r=inspect("1.5.9_0189",md5(data),data)
        self.assertEqual(r["status"],"REJECTED")

    def test_bogus_md5_format_rejected(self):
        data=b"Z"*715000
        for s in (None, "", "0"*31, "G"*32, ["a"*32]):
            self.assertEqual(inspect("2.0.6_0193",s,data)["status"],"REJECTED")

    def test_truncated_and_oversized_images_rejected(self):
        for data in (b"",b"abc", b"X"*(2*1024*1024+1)):
            self.assertEqual(inspect("2.0.6_0193",md5(data),data)["status"],"REJECTED")

    def test_optional_xiaomi_crc_match_is_not_signature(self):
        body=b'synthetic_crc_fixture'
        data=body+struct.pack("<I",xiaomi_crc32_zero_init(body))
        from unittest.mock import patch
        import ceiling1_firmware_research as m
        with patch.object(m,"INDEPENDENT_MD5",md5(data)), \
             patch.object(m,"INDEPENDENT_BYTES",len(data)):
            r=inspect("1.5.9_0189",md5(data),data)
        self.assertTrue(r["xiaomi_crc_trailer_match_if_present"])
        self.assertFalse(r["manufacturer_signature_verified"])

    def test_wrong_crc_does_not_claim_verified_signature(self):
        data=b'x'*715000
        r=inspect("2.0.6_0193",md5(data),data)
        self.assertFalse(r["manufacturer_signature_verified"])
        self.assertFalse(r["oem_recovery_proven"])

if __name__=="__main__":
    unittest.main()
