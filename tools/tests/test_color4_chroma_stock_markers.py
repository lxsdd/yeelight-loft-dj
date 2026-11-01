"""Synthetic-only tests: private stock bytes never enter public CI."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import color4_chroma_stock_markers as subject


class Color4ChromaStockMarkerTests(unittest.TestCase):
    def test_standalone_and_embedded_markers_are_not_conflated(self):
        sample = (b"\0udp_sess_new\0udp_chroma_sess_new\0"
                  b"user.udp_sess_keep_alive\0"
                  b'{"method":"udp_sess_token","token":"SYNTHETIC"}\0')
        report = subject.count_markers(sample)
        self.assertEqual(report["udp_sess_new"]["raw_substring_occurrences"], 2)
        self.assertEqual(report["udp_sess_new"]["standalone_nul_strings"], 1)
        self.assertEqual(report["udp_chroma_sess_new"]["standalone_nul_strings"], 1)
        self.assertEqual(report["udp_sess_keep_alive"]["suffix_of_nul_string"], 1)
        self.assertEqual(report["udp_sess_token"]["embedded_or_unterminated"], 1)
        self.assertEqual(report["chroma"]["standalone_nul_strings"], 0)

    def test_v1_absence_is_not_promoted_to_v2_runtime_support(self):
        report = subject.count_markers(b"\0udp_sess_new\0udp_chroma_sess_new\0")
        for marker in ("udp_new", "udp_token", "udp_keep_alive"):
            self.assertEqual(report[marker]["raw_substring_occurrences"], 0)
        self.assertNotIn("runtime_chroma_capability_proven", report)

    def test_plain_chroma_inside_longer_method_is_not_standalone(self):
        report = subject.count_markers(b"\0udp_chroma_sess_new\0")
        self.assertEqual(report["chroma"]["raw_substring_occurrences"], 1)
        self.assertEqual(report["chroma"]["standalone_nul_strings"], 0)

    def test_case_sensitive_raw_bytes_not_a_support_parser(self):
        report = subject.count_markers(b"\0UDP_SESS_NEW\0udp_sess_new\0")
        self.assertEqual(report["udp_sess_new"]["standalone_nul_strings"], 1)

    def test_repeated_markers_count_without_emitting_payloads(self):
        payload = b"MY_PRIVATE_TOKEN_ABC"
        report = subject.count_markers(b"\0set_music\0set_music\0" + payload)
        self.assertEqual(report["set_music"]["raw_substring_occurrences"], 2)
        self.assertNotIn(payload.decode(), json.dumps(report))

    def test_unknown_or_wrong_size_image_is_rejected_without_markers(self):
        for sample in (b"", b"\0udp_sess_new\0", b"\0" * 128):
            report = subject.inspect_stock_chroma(sample)
            self.assertEqual(report["status"], "REJECTED")
            self.assertFalse(report["stock_image_verified"])
            self.assertFalse(report["runtime_chroma_capability_proven"])
            self.assertNotIn("markers", report)

    def test_oversized_input_rejected_before_checksum_or_scan(self):
        report = subject.inspect_stock_chroma(b"\0" * (subject.MAX_IMAGE_BYTES + 1))
        self.assertEqual(report["status"], "REJECTED")
        self.assertNotIn("markers", report)

    def test_only_allowlisted_marker_names_are_reported(self):
        report = subject.count_markers(b"\0udp_sess_new\0private_credential\0")
        self.assertEqual(set(report), set(subject.MARKERS))
        self.assertNotIn("private_credential", json.dumps(report))
        self.assertTrue(all(set(x) == {
            "raw_substring_occurrences", "standalone_nul_strings",
            "suffix_of_nul_string", "embedded_or_unterminated",
        } for x in report.values()))

    def test_unterminated_last_byte_is_not_standalone(self):
        report = subject.count_markers(b"\0udp_sess_new")
        self.assertEqual(report["udp_sess_new"]["raw_substring_occurrences"], 1)
        self.assertEqual(report["udp_sess_new"]["standalone_nul_strings"], 0)


if __name__ == "__main__":
    unittest.main()
