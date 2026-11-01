"""No OEM images in CI. Format/overflow/corruption tests use synthetic data."""
import hashlib
import struct
import unittest
from unittest.mock import patch
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import oem_image_evidence as mod


def sample_esp(with_sha=True, with_crc=True, with_error=False):
    header = bytearray(24)
    header[0] = 0xE9
    header[1] = 1
    header[23] = int(with_sha)
    body = bytearray(144)
    struct.pack_into("<I", body, 0, 0xABCD5432)
    body[48:57] = b"miio_app\x00"
    if with_error:
        body.extend(b"client quota exceeded\x00")
    data = bytearray(header + struct.pack("<II", 0x3F400020, len(body)) + body)
    checksum = 0xEF
    for byte in body:
        checksum ^= byte
    data.extend(b"\x00" * ((-len(data) - 1) % 16))
    data.append(checksum)
    if with_sha:
        data.extend(hashlib.sha256(data).digest())
    if with_crc:
        data.extend(struct.pack("<I", mod.xiaomi_crc(data)))
    return bytes(data)


class OEMImageEvidenceTests(unittest.TestCase):
    def test_valid_image_layers(self):
        r = mod.esp32(sample_esp())
        self.assertTrue(r["structure_ok"])
        self.assertTrue(r["checksum_valid"])
        self.assertTrue(r["internal_sha256_valid"])
        self.assertTrue(r["xiaomi_four_byte_crc_valid"])
        self.assertEqual(r["app"]["project"], "miio_app")

    def test_optional_internal_digest(self):
        self.assertTrue(mod.esp32(sample_esp(with_sha=False))["structure_ok"])

    def test_crc_required_for_known_xiaomi_container(self):
        self.assertFalse(mod.esp32(sample_esp(with_crc=False))["structure_ok"])

    def test_bad_crc_rejected(self):
        data = bytearray(sample_esp())
        data[-1] ^= 1
        self.assertFalse(mod.esp32(data)["structure_ok"])

    def test_bad_internal_digest_rejected(self):
        data = bytearray(sample_esp())
        data[-5] ^= 1
        self.assertFalse(mod.esp32(data)["internal_sha256_valid"])

    def test_invalid_segment_number(self):
        data = bytearray(sample_esp())
        data[1] = 0
        self.assertFalse(mod.esp32(data)["structure_ok"])

    def test_overrun_segment_rejected(self):
        data = bytearray(sample_esp())
        struct.pack_into("<I", data, 28, 9000000)
        self.assertFalse(mod.esp32(data)["structure_ok"])

    def test_error_text_is_not_a_proven_clock_bug(self):
        r = mod.inspect(sample_esp(with_error=True), "yeelink.light.color4")
        self.assertTrue(r["structure"]["local_lan_error"]["error_string_found"])
        self.assertFalse(r["quota_timer_root_cause_proven"])
        self.assertFalse(r["installable"])

    def test_mmm_format_is_not_esp32(self):
        fixture = b"MMM\x00" + struct.pack("<II", 3, 156) + bytes(144) + b"Y"*1000
        r = mod.mmm(fixture)
        self.assertTrue(r["header_plausible"])
        self.assertEqual(r["cpu_from_container"], "UNKNOWN_NOT_PROVEN")

    def test_incorrect_mmm_header(self):
        fixture = b"MMM\x00" + struct.pack("<II", 3, 999999)
        self.assertFalse(mod.mmm(fixture)["header_plausible"])

    def test_not_mmm_or_esp(self):
        self.assertEqual(mod.mmm(b"not a stock image")["format"], "unknown")
        self.assertEqual(mod.esp32(b"not a stock image")["format"], "unknown")

    def test_exact_catalogue_match_needs_both_hashes(self):
        data = sample_esp()
        row = ("2.1.7_0039", len(data), hashlib.md5(data).hexdigest(),
               hashlib.sha256(data).hexdigest())
        with patch.dict(mod.KNOWN, {"yeelink.light.color4": row}):
            self.assertTrue(mod.inspect(data, "yeelink.light.color4")["known_stock_catalogue_match"])
            self.assertFalse(mod.inspect(data+b"X", "yeelink.light.color4")["known_stock_catalogue_match"])

    def test_wrong_model_does_not_inherit_catalogue(self):
        self.assertFalse(mod.inspect(sample_esp(), "yeelink.light.ceiling1")["known_stock_catalogue_match"])

    def test_size_limit_fails_closed(self):
        self.assertEqual(mod.inspect(b"\x00"*(mod.MAX_BYTES+1), "yeelink.light.color4")["status"], "OVERSIZE_REJECTED")

    def test_invalid_model_not_silently_accepted(self):
        with self.assertRaises(ValueError):
            mod.inspect(b"", "yeelink.light.ceiling3")


if __name__ == "__main__":
    unittest.main()
