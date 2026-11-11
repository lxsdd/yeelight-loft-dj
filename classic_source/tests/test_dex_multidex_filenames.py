"""Multidex labels must be consistent in every original/supplemental scanner."""
import re
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from class_source_inventory import DEX_RE
from embedded_code_inventory import PRIMARY


class MultidexFilenameTests(unittest.TestCase):
    def test_accepts_contiguous_canonical_two_digit_and_three_digit_names(self):
        for filename in ("classes.dex", "classes2.dex", "classes9.dex",
                         "classes10.dex", "classes11.dex", "classes19.dex",
                         "classes20.dex", "classes100.dex"):
            with self.subTest(filename=filename):
                self.assertIsNotNone(DEX_RE.fullmatch(filename))
                self.assertIsNotNone(PRIMARY.fullmatch(filename))

    def test_rejects_noncanonical_and_nested_names(self):
        for filename in ("classes1.dex", "classes0.dex", "classes01.dex",
                         "classes02.dex", "classes00.dex", "classes.dex.bak",
                         "sub/classes.dex", "classes12.dex/"):
            with self.subTest(filename=filename):
                self.assertIsNone(DEX_RE.fullmatch(filename))
                self.assertIsNone(PRIMARY.fullmatch(filename))

    def test_original_apk_parser_reuses_exact_canonical_pattern(self):
        from private_startup_audit import DEX_RE as ORIGINAL_DEX_RE
        self.assertEqual(ORIGINAL_DEX_RE.pattern, DEX_RE.pattern)


if __name__ == "__main__":
    unittest.main()
