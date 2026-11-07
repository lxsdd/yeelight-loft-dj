"""Synthetic fail-closed tests; no OEM image or hardware needed."""
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from color4_quota_status_origin_audit import decode_regwrite, audit, WRAPPERS


def movi(dst, immediate):
    # LSAI MOVI: 12 signed immediate bits split across s and imm8.
    v = immediate & 0xfff
    return bytes([0x02 | (dst << 4), 0xa0 | ((v >> 8) & 0xf), v & 0xff])


def movin(dst, immediate):
    # MOVI.N: sign-correct encoding of the 7-bit signed immediate.
    v = immediate & 0x7f
    t = (v >> 4) & 0x7
    return bytes([0x0c | (t << 4), (dst & 0xf) | ((v & 0xf) << 4)])


def movn(dest, src):
    return bytes([0x0d | (dest << 4), src & 0xf])


def addi(dest, src, imm):
    return bytes([0x02 | (dest << 4), (0xc << 4) | src, imm & 0xff])


class QuotaArgumentTests(unittest.TestCase):
    def test_movi_status_index(self):
        for n in [0, 1, 5, 127, -1, -256, 255, 2047, -2048]:
            with self.subTest(n=n):
                w = decode_regwrite(movi(11, n))
                self.assertEqual((w.dest, w.immediate, w.mnemonic), (11, n, 'MOVI'))

    def test_movi_n_status_index(self):
        for n in [0, 1, 5, 63, -1, -32]:
            with self.subTest(n=n):
                w = decode_regwrite(movin(11, n))
                self.assertEqual((w.dest, w.immediate, w.mnemonic), (11, n, 'MOVI.N'))

    def test_intervening_mov_n_writes_only_a10(self):
        w = decode_regwrite(movn(10, 3))
        self.assertEqual((w.dest, w.source, w.mnemonic), (10, 3, 'MOV.N'))

    def test_intervening_addi_writes_only_a10(self):
        w = decode_regwrite(addi(10, 3, 40))
        self.assertEqual((w.dest, w.source, w.immediate), (10, 3, 40))

    def test_regwrite_unsupported_fails_closed(self):
        for b in (b'', b'\x02', b'\xff\xff\xff', b'\x00\x00\x00', b'\x01\x00\x00'):
            with self.subTest(b=b):
                self.assertIsNone(decode_regwrite(b))

    def test_wrong_arity_rejected(self):
        for b in (movi(11, 1)[:2], movin(11, 1)+b'\x00', addi(10, 3, 40)[:1]):
            self.assertIsNone(decode_regwrite(b))

    def test_actual_a11_readback_pinned_stock_only(self):
        path = os.environ.get('COLOR4_STOCK_IMAGE')
        if not path:
            self.skipTest('private stock image not present in public CI')
        result = audit(path)
        self.assertEqual(result['original_sha256'],
                         '67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa')
        self.assertEqual([x['callee_a3_status_index'] for x in result['formatter_direct_callers']], [1, 1])
        self.assertFalse(result['direct_callers_explain_quota_status_5'])
        self.assertTrue(result['possible_unresolved_indirect_calls_or_other_quota_paths'])
        self.assertFalse(result['quota_counters_refill_or_49d_bug_proven'])

    def test_wrong_real_image_rejected(self):
        path = os.environ.get('COLOR4_STOCK_IMAGE')
        if not path:
            self.skipTest('private stock image not present in public CI')
        import tempfile
        original = Path(path).read_bytes()
        fake = bytearray(original)
        fake[0x1000] ^= 1
        with tempfile.TemporaryDirectory() as tmp:
            altered = Path(tmp) / 'bad-original.bin'
            altered.write_bytes(fake)
            with self.assertRaises(ValueError):
                audit(altered)


if __name__ == '__main__':
    unittest.main()
