"""Synthetic-only firmware format tests; no manufacturer image is committed."""
import hashlib
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from color4_fw_image_info import inspect


def fake_image(payload=b'ABCDEF', *, digest=True, vendor_trailer=b''):
    header = bytearray(24)
    header[0] = 0xE9
    header[1] = 1
    header[23] = 1 if digest else 0
    segment = (0x3f400000).to_bytes(4, 'little') + len(payload).to_bytes(4, 'little')
    raw = bytearray(header + segment + payload)
    checksum = 0xEF
    for b in payload:
        checksum ^= b
    raw.extend(b'\x00' * ((-len(raw) - 1) % 16))
    raw.append(checksum)
    if digest:
        raw.extend(hashlib.sha256(raw).digest())
    raw.extend(vendor_trailer)
    return bytes(raw)


class ImageInspectorTest(unittest.TestCase):
    def test_valid_synthetic_with_digest_and_vendor_trailer(self):
        r = inspect(fake_image(vendor_trailer=b'\x01\x02\x03\x04'))
        self.assertEqual(r['format'], 'esp32-image-candidate')
        self.assertEqual(r['segment_count'], 1)
        self.assertTrue(r['structural_integrity_ok'])
        self.assertTrue(r['image_checksum_valid'])
        self.assertTrue(r['appended_sha256_valid'])
        self.assertEqual(r['trailing_bytes'], 4)
        self.assertTrue(r['four_byte_vendor_trailer_candidate'])
        self.assertFalse(r['installable'])
        self.assertFalse(r['secure_boot_verified'])

    def test_valid_without_digest(self):
        r = inspect(fake_image(digest=False))
        self.assertTrue(r['structural_integrity_ok'])
        self.assertIsNone(r['appended_sha256_valid'])
        self.assertEqual(r['trailing_bytes'], 0)
        self.assertFalse(r['four_byte_vendor_trailer_candidate'])

    def test_wrong_magic_is_not_assumed_to_be_esp32(self):
        self.assertEqual(inspect(b'\x00' * 128)['format'], 'unknown')

    def test_short_input_fails_closed(self):
        self.assertEqual(inspect(b'\xe9\x01')['format'], 'unknown')

    def test_invalid_segment_count(self):
        data = bytearray(fake_image())
        data[1] = 0
        self.assertEqual(inspect(bytes(data))['format'], 'unknown')

    def test_truncated_segment(self):
        data = bytearray(fake_image())
        data[28:32] = (99999).to_bytes(4, 'little')
        self.assertIn('truncated', inspect(bytes(data))['reason'])

    def test_checksum_tamper_caught(self):
        data = bytearray(fake_image(digest=False))
        data[-1] ^= 0x80
        r = inspect(bytes(data))
        self.assertFalse(r['image_checksum_valid'])
        self.assertFalse(r['structural_integrity_ok'])

    def test_sha_tamper_caught(self):
        data = bytearray(fake_image())
        data[-1] ^= 0x80
        r = inspect(bytes(data))
        self.assertFalse(r['appended_sha256_valid'])
        self.assertFalse(r['structural_integrity_ok'])

    def test_padding_tamper_caught(self):
        data = bytearray(fake_image(digest=False))
        data[-2] = 4
        r = inspect(bytes(data))
        self.assertFalse(r['footer_padding_zero'])
        self.assertFalse(r['structural_integrity_ok'])

    def test_random_suffix_not_assumed_vendor_crc(self):
        r = inspect(fake_image(vendor_trailer=b'too long'))
        self.assertEqual(r['trailing_bytes'], 8)
        self.assertFalse(r['four_byte_vendor_trailer_candidate'])

    def test_truncated_hash_is_not_accepted(self):
        r = inspect(fake_image()[:-20])
        self.assertIn('truncated', r['reason'])


if __name__ == '__main__':
    unittest.main()
