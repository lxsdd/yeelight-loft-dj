"""Synthetic positive and negative test vectors only; OEM image private."""
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from color4_quota_indirect_dispatch_audit import (
    Site, enumerate_direct_calls, enumerate_direct_pointers, audit
)


class SyntheticFirmware:
    def __init__(self, code, low=0x40100000):
        self.low = low
        self.codebytes = code
        self.segments = [(low, low + len(code), 0, len(code))]
    def code(self, address):
        return self.low <= address < self.low + len(self.codebytes)
    def data(self, address, length):
        offset = address - self.low
        if offset < 0 or offset + length > len(self.codebytes):
            return None
        return self.codebytes[offset:offset + length]
    def u32(self, address):
        d = self.data(address, 4)
        return int.from_bytes(d, 'little') if d is not None else None


def calln(pc, dest, variant):
    # Xtensa 24-bit CALLN (target = align(pc,4)+4 + sext(imm18)*4).
    displacement = dest - ((pc & ~3) + 4)
    if displacement % 4 or not -(1<<17) <= displacement//4 < 1<<17:
        raise ValueError('bad fixture target')
    imm18 = (displacement // 4) & 0x3ffff
    val = (imm18 << 6) | ((variant & 3) << 4) | 5
    return val.to_bytes(3,'little')


class QuotaIndirectInventoryTests(unittest.TestCase):
    def test_all_calln_types(self):
        pc = 0x40100004
        dest = 0x40100200
        for idx, name in enumerate(('call0', 'call4', 'call8', 'call12')):
            with self.subTest(name=name):
                image = SyntheticFirmware(calln(pc, dest, idx), pc)
                self.assertEqual(enumerate_direct_calls(image, dest), [Site(pc, name)])

    def test_negative_wrong_target_and_decoy_bytes(self):
        pc, dest = 0x40100004, 0x40100200
        wrong = SyntheticFirmware(calln(pc, dest + 8, 2), pc)
        self.assertEqual(enumerate_direct_calls(wrong, dest), [])
        image = SyntheticFirmware(b'\xff'*20, pc)
        self.assertEqual(enumerate_direct_calls(image, dest), [])

    def test_known_absolute_pointer_is_memory_word_only(self):
        value = 0x40115000
        base = 0x40100000
        img = SyntheticFirmware(b'\x00'*4 + value.to_bytes(4,'little') + b'\x00'*4, base)
        self.assertEqual(enumerate_direct_pointers(img, value), [base+4])
        self.assertEqual(enumerate_direct_pointers(img, value+4), [])

    def test_unaligned_nonpointer_not_reported(self):
        value = 0x40115000
        base = 0x40100000
        img = SyntheticFirmware(b'\x00' + value.to_bytes(4,'little') + b'\x00'*4, base)
        self.assertEqual(enumerate_direct_pointers(img, value), [])

    def test_pinned_real_image_withheld_in_public(self):
        path = os.environ.get('COLOR4_STOCK_IMAGE')
        if not path:
            self.skipTest('private original not present in public CI')
        result = audit(Path(path))
        self.assertEqual(len(result['known_error_formatters']), 2)
        self.assertEqual([e['direct_call_candidates_all_CALLN_types'] for e in result['known_error_formatters']], [1,1])
        self.assertEqual([e['mapped_aligned_absolute_pointer_words'] for e in result['known_error_formatters']], [0,0])
        self.assertTrue(result['possible_indirect_CALLX_or_other_formatter_paths'])
        self.assertFalse(result['quota_root_cause_identified'])

    def test_corrupt_image_rejected(self):
        path = os.environ.get('COLOR4_STOCK_IMAGE')
        if not path:
            self.skipTest('private original not present in public CI')
        original=bytearray(Path(path).read_bytes());original[-200] ^= 1
        with tempfile.TemporaryDirectory() as d:
            other=Path(d)/'corrupt.bin';other.write_bytes(original)
            with self.assertRaises(ValueError):audit(other)


if __name__ == '__main__':
    unittest.main()
