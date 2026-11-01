"""Synthetic image fixtures only; no original manufacturer code in GitHub."""
import hashlib
import lzma
from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mediatek_mmm_evidence import analyze_mmm


def make_image(contents=(b"AAAA client quota exceeded\x00 lan_ctrl_task\x00", b"B"*240, b"C"*64)):
    n = len(contents)
    header = bytearray(b"MMM\x00" + struct.pack("<I", n) + bytes(32*n))
    header.extend(b"\x00" * (156-len(header)))
    parts = []
    pos = len(header)
    for i, content in enumerate(contents):
        packed = lzma.compress(content, format=lzma.FORMAT_ALONE, preset=6)
        sha = hashlib.sha1(packed).digest()
        fields = (pos, 0x71000+i*0x10000, len(packed),
                  max(4096, len(content)), pos+len(packed), 20, 1, 0)
        struct.pack_into("<8I", header, 8+i*32, *fields)
        parts.append(packed + sha)
        pos += len(packed)+20
    return bytes(header)+b"".join(parts)


class MMMEvidenceTests(unittest.TestCase):
    def test_three_valid_sections_and_lan_error(self):
        r = analyze_mmm(make_image())
        self.assertTrue(r["complete_integrity"])
        self.assertEqual(r["section_count"], 3)
        self.assertTrue(all(x["compressed_sha1_matches_header"] for x in r["sections"]))
        self.assertTrue(r["local_lan_evidence"]["quota_string_found"])
        self.assertEqual(r["local_lan_evidence"]["header_flash_offset_candidate"],
                         hex(0x71000+5))
        self.assertEqual(r["local_lan_evidence"]["mapped_memory_address_candidate"],
                         hex(0x10071000+5))
        self.assertNotIn("header_based_mapped_address_candidate", r["local_lan_evidence"])
        self.assertFalse(r["local_lan_evidence"]["quota_timer_root_cause_found"])
        self.assertFalse(r["flash_or_ota_approved"])
        self.assertFalse(r["cryptographic_oem_authenticity_verified"])

    def test_bad_sha1_rejected(self):
        data = bytearray(make_image())
        sig = struct.unpack_from("<I", data, 8+4*4)[0]
        data[sig] ^= 1
        self.assertFalse(analyze_mmm(data)["complete_integrity"])

    def test_tampered_lzma_rejected(self):
        data = bytearray(make_image())
        off = struct.unpack_from("<I", data, 8)[0]
        data[off+16] ^= 1
        self.assertFalse(analyze_mmm(data)["complete_integrity"])

    def test_short_data_rejected(self):
        self.assertFalse(analyze_mmm(make_image()[:-6])["complete_integrity"])

    def test_bad_magic(self):
        self.assertEqual(analyze_mmm(b"BAD!")["format"], "unknown")

    def test_count_too_large(self):
        data = bytearray(make_image())
        struct.pack_into("<I", data, 4, 9)
        self.assertFalse(analyze_mmm(data)["complete_integrity"])

    def test_compression_flag_must_be_documented(self):
        data = bytearray(make_image())
        struct.pack_into("<I", data, 8+6*4, 0)
        self.assertFalse(analyze_mmm(data)["complete_integrity"])

    def test_signature_position_must_match_segment(self):
        data = bytearray(make_image())
        struct.pack_into("<I", data, 8+4*4, 9000000)
        self.assertFalse(analyze_mmm(data)["complete_integrity"])

    def test_partition_size_enforced(self):
        data = bytearray(make_image())
        struct.pack_into("<I", data, 8+3*4, 3)
        self.assertFalse(analyze_mmm(data)["complete_integrity"])

    def test_no_lan_string_means_unknown_not_absent(self):
        r = analyze_mmm(make_image((b"no-message",)))
        self.assertTrue(r["complete_integrity"])
        self.assertFalse(r["local_lan_evidence"]["quota_string_found"])
        self.assertFalse(r["local_lan_evidence"]["quota_timer_root_cause_found"])

    def test_trailing_junk_rejected(self):
        self.assertFalse(analyze_mmm(make_image() + b"extra")["complete_integrity"])

    def test_oversize_rejected(self):
        self.assertFalse(analyze_mmm(b"MMM\x00"+b"0"*(8*1024*1024+20))["complete_integrity"])


if __name__ == "__main__":
    unittest.main()
