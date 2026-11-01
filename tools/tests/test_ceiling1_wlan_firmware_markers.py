"""Synthetic-only MediaTek-FOTA fixtures: no manufacturer binaries in CI."""
import hashlib
import lzma
import struct
import unittest
from pathlib import Path
import sys
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ceiling1_wlan_firmware_markers as m


def fixture(main=b"wifi disconnected\x00 DHCP got Failed\x00"):
    plain = (main, b"second-image", b"aux")
    image = bytearray(b"MMM\x00" + struct.pack("<I", 3) + bytes(32 * 3))
    image += bytes(156 - len(image))
    for i, raw in enumerate(plain):
        packed = lzma.compress(raw, format=lzma.FORMAT_ALONE)
        offset = len(image)
        struct.pack_into(
            "<8I", image, 8 + i * 32,
            offset, 0x71000 + i * 0x1000, len(packed), 4096,
            offset + len(packed), 20, 1, 0,
        )
        image += packed + hashlib.sha1(packed).digest()
    return bytes(image)


class Ceiling1WlanFirmwareMarkersTests(unittest.TestCase):
    def scan(self, raw):
        with patch.object(m, "MODEL_SHA256", hashlib.sha256(raw).hexdigest()):
            return m.analyze(raw)

    def test_valid_three_section_image_detects_only_known_indicators(self):
        result = self.scan(fixture())
        self.assertEqual("READ_ONLY_MARKERS_CONFIRMED", result["status"])
        self.assertTrue(result["fota_integrity_checked"])
        self.assertEqual(len(result["markers"]["wifi_event_callbacks"]), 1)
        self.assertEqual(len(result["markers"]["dhcp_ip"]), 1)
        self.assertFalse(result["wifi_firmware_cause_proven"])

    def test_absence_of_strings_never_proves_code_absent(self):
        result = self.scan(fixture(b"some unrelated diagnostics"))
        self.assertEqual(result["status"], "READ_ONLY_MARKERS_CONFIRMED")
        self.assertFalse(any(result["markers"].values()))
        self.assertFalse(result["wifi_firmware_cause_proven"])

    def test_known_sha_is_mandatory(self):
        self.assertEqual(m.analyze(fixture())["status"], "REJECTED")

    def test_bad_container_header(self):
        self.assertEqual(self.scan(b"MMM\x00" + bytes(200))["status"], "REJECTED")

    def test_section_count_altered(self):
        image = bytearray(fixture())
        struct.pack_into("<I", image, 4, 2)
        self.assertEqual(self.scan(bytes(image))["status"], "REJECTED")

    def test_sha1_corruption(self):
        image = bytearray(fixture())
        sig = struct.unpack_from("<I", image, 8 + 4 * 4)[0]
        image[sig] ^= 1
        self.assertEqual(self.scan(bytes(image))["reason"], "section digest mismatch")

    def test_unsupported_section_flag(self):
        image = bytearray(fixture())
        struct.pack_into("<I", image, 8 + 6 * 4, 0)
        self.assertEqual(self.scan(bytes(image))["status"], "REJECTED")

    def test_lzma_corruption(self):
        image = bytearray(fixture())
        offset = struct.unpack_from("<I", image, 8)[0]
        image[offset + 6] ^= 8
        self.assertEqual(self.scan(bytes(image))["status"], "REJECTED")

    def test_truncated_image(self):
        self.assertEqual(self.scan(fixture()[:-1])["status"], "REJECTED")

    def test_no_trailing_bytes_accepted(self):
        self.assertEqual(self.scan(fixture() + b"extra")["status"], "REJECTED")

    def test_file_limit(self):
        self.assertEqual(m.analyze(b"z" * (m.MAX_FILE + 1))["status"], "REJECTED")

    def test_unapproved_string_data_not_returned(self):
        result = self.scan(fixture(b"private-ssid-or-secret wifi connected"))
        self.assertNotIn("private-ssid-or-secret", str(result))
        self.assertIn("wifi connected", str(result))
        self.assertFalse(result["flash_safe"])


if __name__ == "__main__":
    unittest.main()
