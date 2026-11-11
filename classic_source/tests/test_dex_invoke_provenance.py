"""Synthetic DEX code-item evidence, no OEM assets or Android device access."""
import hashlib
import struct
import unittest
import zlib
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dex_invoke_provenance import (
    InvokeProvenanceBlocked, instructions, scan_dex, dex_types, WIDTH,
)

PRESENT = "Lcom/example/Present;"
ACTIVITY = "Lcom/example/MissingActivity;"
PARENT = "Lcom/example/MissingParent;"
OBJECT = "Ljava/lang/Object;"


def uleb(value):
    data = []
    while True:
        part = value & 127
        value >>= 7
        data.append(part | (128 if value else 0))
        if not value:
            return bytes(data)


def signed(raw):
    struct.pack_into("<I", raw, 32, len(raw))
    raw[12:32] = hashlib.sha1(raw[32:]).digest()
    struct.pack_into("<I", raw, 8, zlib.adler32(raw[12:]) & 0xffffffff)
    return bytes(raw)


def fixture(words=None):
    """One valid self-consistent DEX with two method IDs and real code_item."""
    if words is None:
        words = (0x001c, 2, 0x0071, 0, 0, 0x0052, 0, 0x000e)
    strings = [OBJECT, PRESENT, ACTIVITY, PARENT, "invokeTarget", "fieldOne"]
    types = 4
    strings_off = 112
    types_off = strings_off + 4 * len(strings)
    protos_off = types_off + 4 * types
    fields_off = protos_off + 12
    methods_off = fields_off + 8
    class_off = methods_off + 16
    raw = bytearray(class_off + 32)
    raw[:8] = b"dex\n035\0"
    for count_off, offset_off, count, offset in (
        (56, 60, len(strings), strings_off),
        (64, 68, types, types_off),
        (72, 76, 1, protos_off),
        (80, 84, 1, fields_off),
        (88, 92, 2, methods_off),
        (96, 100, 1, class_off),
    ):
        struct.pack_into("<II", raw, count_off, count, offset)
    # method proto shorty string #4, return Object, no params
    struct.pack_into("<III", raw, protos_off, 4, 0, 0)
    struct.pack_into("<HHI", raw, fields_off, 2, 0, 5)
    struct.pack_into("<HHI", raw, methods_off, 2, 0, 4)
    struct.pack_into("<HHI", raw, methods_off + 8, 1, 0, 4)
    # defined Present class has missing Parent as superclass
    struct.pack_into("<IIIIIIII", raw, class_off,
                     1, 1, 3, 0, 0xffffffff, 0, 0, 0)
    for index, value in enumerate(strings):
        struct.pack_into("<I", raw, strings_off + 4 * index, len(raw))
        raw.extend(uleb(len(value)))
        raw.extend(value.encode("ascii"))
        raw.append(0)
    for index in range(types):
        struct.pack_into("<I", raw, types_off + 4 * index, index)
    while len(raw) % 4:
        raw.append(0)
    code_offset = len(raw)
    raw.extend(struct.pack("<HHHHII", 2, 0, 2, 0, 0, len(words)))
    raw.extend(struct.pack("<" + "H" * len(words), *words))
    class_data = len(raw)
    raw.extend(bytes([0, 0, 1, 0, 1, 1]) + uleb(code_offset))
    struct.pack_into("<I", raw, class_off + 24, class_data)
    return signed(raw), code_offset


class DexInvokeProvenanceTests(unittest.TestCase):
    def checked(self, raw):
        return scan_dex(raw, {ACTIVITY}, {PARENT},
                        {PRESENT: (PARENT,)})

    def test_real_dex_verified_invoke_type_field_and_parent_reference(self):
        raw, _ = fixture()
        self.assertIn(ACTIVITY, dex_types(raw))
        hits, calls, counts = self.checked(raw)
        for key in ("activity_type_ids", "activity_method_id_owners",
                    "activity_field_id_owners", "activity_invoke_targets",
                    "activity_type_instruction_targets",
                    "activity_field_access_owners"):
            self.assertEqual(hits[key], {ACTIVITY}, key)
        self.assertEqual(hits["parent_type_ids"], {PARENT})
        self.assertEqual(hits["parent_invoke_targets"], set())
        self.assertEqual(counts["missing_activity_invoke_sites"], 1)
        self.assertEqual(counts["code_items"], 1)
        self.assertEqual(counts["instructions"], 4)
        self.assertFalse(calls)

    def test_wide_literal_cannot_fake_an_invoke(self):
        words = (0x0018, 0x0071, 0x0000, 0x0071, 0x0000,
                 0x000e)
        hits, _, counts = self.checked(fixture(words)[0])
        self.assertFalse(hits["activity_invoke_targets"])
        self.assertEqual(counts["missing_activity_invoke_sites"], 0)
        self.assertEqual(counts["instructions"], 2)

    def test_payload_widths_are_not_mistaken_for_calls(self):
        for payload in ((0x0100, 1, 0, 0, 0x0071, 0),
                        (0x0200, 1, 0x0071, 0, 0, 0),
                        (0x0300, 1, 2, 0, 0x0071)):
            code = b"".join(struct.pack("<H", x) for x in payload)
            parsed = list(instructions(code, 0, len(payload)))
            self.assertEqual(len(parsed), 1)
            self.assertEqual(parsed[0][0], 0)

    def test_invalid_code_reference_and_opcodes_fail_closed(self):
        for words in (
            (0x0071, 99, 0),
            (0x0052, 99),
            (0x001c, 99),
            (0x0073,),
            (0x0100, 2, 0, 0),
            (0x0300, 8, 0xffff, 0xffff),
        ):
            raw, _ = fixture(words)
            with self.subTest(words=words), self.assertRaises(InvokeProvenanceBlocked):
                self.checked(raw)

    def test_invalid_type_and_dex_integrity_never_become_zero_xrefs(self):
        raw, _ = fixture()
        invalid = bytearray(raw)
        invalid[-1] ^= 0x40
        with self.assertRaises(InvokeProvenanceBlocked):
            self.checked(bytes(invalid))
        invalid = bytearray(raw)
        types_off = int.from_bytes(invalid[68:72], "little")
        struct.pack_into("<I", invalid, types_off + 8, 65535)
        with self.assertRaises(InvokeProvenanceBlocked):
            self.checked(signed(invalid))

    def test_unknown_opcode_width_is_rejected(self):
        self.assertEqual(WIDTH[0x73], 0)
        self.assertEqual(WIDTH[0x6e], 3)
        self.assertEqual(WIDTH[0x18], 5)


if __name__ == "__main__":
    unittest.main()
