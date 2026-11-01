"""Only synthetic bytes; exact OEM image never added to GitHub or public CI."""
import hashlib
import struct
import sys
from pathlib import Path
from unittest.mock import patch
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import color4_lan_quota_firmware_evidence as mod


def artificial_image():
    # Two synthetic mapped ESP32 segments. No proprietary OEM bytes.
    header=bytearray(24)
    header[0]=0xe9
    header[1]=2
    header[23]=1
    ro=bytearray(512)
    words=[]
    at=8
    for label in mod.ERRORS:
        ro[at:at+len(label)+1]=label+b"\0"
        words.append(0x3f400020+at)
        at+=len(label)+1
    table=bytearray(128)
    for n,word in enumerate(words):
        struct.pack_into("<I",table,4+n*4,word)
    b=bytes(header)+struct.pack("<II",0x3f400020,len(ro))+ro+struct.pack("<II",0x3ffb0000,len(table))+table
    return b


class Color4FirmwareEvidenceTests(unittest.TestCase):
    def test_full_ordered_error_pointer_table_in_two_segments(self):
        img=artificial_image()
        table=mod.firmware_error_table(img)
        self.assertIsNotNone(table)
        self.assertEqual(table["quota_error_index_zero_based"],5)
        self.assertEqual(table["quota_error_string_mapped_address_hex"],
                         hex(0x3f400020+sum(len(v)+1 for v in mod.ERRORS[:5])+8))
        self.assertEqual(table["verified_error_names"][5],"client quota exceeded")

    def test_error_table_rejects_corrupted_pointer(self):
        data=bytearray(artificial_image())
        needle=struct.pack("<I",0x3f400020+8)
        location=data.find(needle,24+8+512)
        data[location]^=1
        self.assertIsNone(mod.firmware_error_table(bytes(data)))

    def test_error_table_rejects_ambiguity(self):
        img=artificial_image()
        # A duplicate properly aligned contiguous error vector must fail closed.
        data=bytearray(img)
        first=mod.firmware_error_table(img)
        self.assertIsNotNone(first)
        at=int(first["pointer_table_file_offset_hex"],16)
        data[at+48:at+48+28]=data[at:at+28]
        self.assertIsNone(mod.firmware_error_table(bytes(data)))

    def test_non_exact_hash_is_rejected_without_weakening_verification(self):
        r=mod.inspect(artificial_image())
        self.assertEqual(r["status"],"REJECTED")
        self.assertFalse(r["stock_image_verified"])
        self.assertFalse(r["flash_safe"])

    def test_no_image_is_not_a_model_specific_failure_proof(self):
        self.assertIsNone(mod.firmware_error_table(b""))
        self.assertFalse(mod.inspect(b"")["firmware_timer_bug_proven"])

    def test_string_only_is_insufficient_without_pointer_table(self):
        data=b"prefix\x00client quota exceeded\x00"
        self.assertIsNone(mod.firmware_error_table(data))

    def test_mapped_address_bounds_not_guessed(self):
        image=artificial_image()
        self.assertIsNone(mod.map_offset_to_address(image,len(image)))
        self.assertIsNone(mod.map_offset_to_address(image,-1))
        self.assertIsNone(mod.map_offset_to_address(bytes(5),3))

    def test_method_strings_cannot_prove_music_support(self):
        # A synthetically altered hash cannot bless firmware capability.
        result=mod.inspect(artificial_image())
        self.assertFalse(result["stock_image_verified"])
        self.assertNotIn("music_mode_supported_by_this_lamp",result)

    def test_firmware_id_and_safety_flags_immutable(self):
        self.assertEqual(mod.MODEL,"yeelink.light.color4")
        self.assertEqual(mod.VERSION,"2.1.7_0039")
        self.assertEqual(len(mod.STOCK_SHA256),64)
        self.assertFalse(mod.inspect(b"x")["ota_signature_verified"])


if __name__=="__main__":
    unittest.main()
