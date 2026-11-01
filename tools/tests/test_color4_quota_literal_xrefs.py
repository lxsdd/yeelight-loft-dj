"""Synthetic Xtensa L32R candidate-link tests. NEVER store actual OEM bytes."""
import json
import struct
import sys
from pathlib import Path
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import color4_lan_quota_firmware_evidence as firmware
import color4_quota_literal_xrefs as xref


def fixture(*, include_instruction=True, duplicate_code=False):
    """Two mapped synthetic ESP32 segments; not an authentic OEM image."""
    drom = bytearray(512)
    code = bytearray(128)
    drom_start = 0x3F400000
    code_start = 0x400D0000
    strings = []
    cursor = 32
    for name in firmware.ERRORS:
        drom[cursor:cursor + len(name) + 1] = name + b"\0"
        strings.append(drom_start + cursor)
        cursor += len(name) + 1
    table_at = 256
    for i, addr in enumerate(strings):
        struct.pack_into("<I", drom, table_at + i * 4, addr)
    table_address = drom_start + table_at
    quota_address = strings[5]
    # IROM L32R literal pool: two 32-bit target-address constants.
    struct.pack_into("<II", code, 16, table_address, quota_address)
    if include_instruction:
        # RI16: op0 0001, t=1/2, imm16=-16 words (PC=80 -> 16).
        code[80:83] = bytes([0x11, 0xF0, 0xFF])
        code[84:87] = bytes([0x21, 0xF0, 0xFF])
    header = bytearray(24)
    header[0] = 0xE9
    header[1] = 2 if not duplicate_code else 3
    image = (
        bytes(header) +
        struct.pack("<II", drom_start, len(drom)) + drom +
        struct.pack("<II", code_start, len(code)) + code
    )
    if duplicate_code:
        image += struct.pack("<II", code_start + 4, len(code)) + code
    return image, table_address, quota_address


class QuotaLiteralXrefTests(unittest.TestCase):
    def test_two_targets_have_distinct_l32r_candidate_sites(self):
        image, table, quota = fixture()
        report = xref.candidate_links(image, table, quota)
        self.assertEqual(report["mapped_segment_count"], 2)
        t = report["error_table"]
        q = report["quota_error_string"]
        self.assertEqual(t["target_address_hex"], hex(table))
        self.assertEqual(t["candidate_pc_relative_l32r_count"], 1)
        self.assertEqual(q["candidate_pc_relative_l32r_count"], 1)
        self.assertEqual(t["candidate_pc_relative_l32r"][0]["target_register"], 1)
        self.assertEqual(q["candidate_pc_relative_l32r"][0]["target_register"], 2)
        self.assertEqual(
            t["candidate_pc_relative_l32r"][0]["instruction_address_hex"],
            hex(0x400D0000 + 80))
        self.assertEqual(
            q["candidate_pc_relative_l32r"][0]["literal_address_hex"],
            hex(0x400D0000 + 20))
        self.assertFalse(t["code_xref_confirmed"])
        self.assertFalse(report["firmware_quota_root_cause_proven"])

    def test_error_table_is_exactly_resolved_but_root_cause_unproven(self):
        image, table, quota = fixture()
        verified = firmware.firmware_error_table(image)
        self.assertEqual(int(verified["pointer_table_mapped_address_hex"], 16), table)
        self.assertEqual(int(verified["quota_error_string_mapped_address_hex"], 16), quota)
        result = xref.candidate_links(image, table, quota)
        self.assertFalse(result["patch_or_flash_safe"])
        self.assertFalse(result["xtensa_instruction_flow_disassembled"])

    def test_no_instructions_means_no_inferred_code_xrefs(self):
        image, table, quota = fixture(include_instruction=False)
        links = xref.candidate_links(image, table, quota)
        self.assertGreater(links["error_table"]["aligned_pointer_word_count"], 0)
        self.assertEqual(links["error_table"]["candidate_pc_relative_l32r_count"], 0)
        self.assertEqual(links["quota_error_string"]["candidate_pc_relative_l32r_count"], 0)

    def test_unaligned_pointer_is_not_a_l32r_literal_pool_word(self):
        image, _, quota = fixture(include_instruction=False)
        data = bytearray(image)
        code_start = 24 + 8 + 512 + 8
        # Purposefully misaligned byte pattern cannot be a direct L32R literal.
        data[code_start + 97:code_start + 101] = struct.pack("<I", quota)
        refs = xref.mapped_word_references(
            bytes(data), xref.mapped_segments(bytes(data)), quota)
        self.assertTrue(all(int(r["literal_address_hex"], 16) % 4 == 0 for r in refs))
        self.assertNotIn(hex(0x400D0000 + 97),
            [r["literal_address_hex"] for r in refs])

    def test_invalid_magic_truncation_and_segment_counts_fail_closed(self):
        image, *_ = fixture()
        for invalid in (b"", b"\0" * len(image), image[:27]):
            with self.assertRaises(ValueError):
                xref.mapped_segments(invalid)
        raw = bytearray(image)
        raw[1] = 17
        with self.assertRaises(ValueError):
            xref.mapped_segments(bytes(raw))
        raw = bytearray(image)
        struct.pack_into("<I", raw, 28, len(image) + 1)
        with self.assertRaises(ValueError):
            xref.mapped_segments(bytes(raw))

    def test_overlapping_mapped_segments_rejected(self):
        image, *_ = fixture(duplicate_code=True)
        with self.assertRaisesRegex(ValueError, "overlapping"):
            xref.mapped_segments(image)

    def test_unknown_segment_is_not_interpreted_as_code(self):
        image, table, quota = fixture()
        data = bytearray(image)
        # Replace IROM address with an arbitrary non-executable mapping.
        code_header = 24 + 8 + 512
        struct.pack_into("<I", data, code_header, 0x20000000)
        refs = xref.candidate_links(bytes(data), table, quota)
        self.assertEqual(refs["error_table"]["candidate_pc_relative_l32r_count"], 0)
        self.assertEqual(refs["quota_error_string"]["candidate_pc_relative_l32r_count"], 0)

    def test_exact_stock_signature_is_mandatory_even_for_valid_fixture(self):
        image, *_ = fixture()
        result = xref.analyze_stock(image)
        self.assertEqual(result["status"], "REJECTED")
        self.assertFalse(result["stock_image_verified"])
        self.assertFalse(result["firmware_quota_root_cause_proven"])
        self.assertFalse(result["patch_or_flash_safe"])

    def test_bad_hash_stays_rejected_even_if_length_matches(self):
        image = b"\xE9" + b"\0" * (firmware.STOCK_SIZE - 1)
        result = xref.analyze_stock(image)
        self.assertEqual(result["status"], "REJECTED")
        self.assertNotIn("analysis", result)

    def test_output_is_small_and_contains_no_raw_image_or_tokens(self):
        image, table, quota = fixture()
        report = json.dumps(xref.candidate_links(image, table, quota))
        self.assertNotIn("192.168.", report)
        self.assertNotIn("token", report)
        self.assertNotIn("firmware_bytes", report)
        self.assertFalse(xref.candidate_links(image, table, quota)["patch_or_flash_safe"])

    def test_synthetic_benchmark_remains_bounded(self):
        image, table, quota = fixture()
        huge = bytearray(image)
        # Point scan only operates within mapped payload regions.
        huge.extend(b"x" * 2000)
        report = xref.candidate_links(bytes(huge), table, quota)
        self.assertEqual(report["mapped_segment_count"], 2)
        self.assertFalse(report["live_firmware_quota_state_observed"])


if __name__ == "__main__":
    unittest.main()
