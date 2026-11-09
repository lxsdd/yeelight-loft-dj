"""Fully synthetic literal-load and FOTA fixtures. Never upload OEM bytes."""
import hashlib
import lzma
import struct
import sys
import unittest
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import ceiling1_thumb_literal_xrefs as m
import ceiling1_wlan_firmware_markers as verifier


def fota(main, base_offset=0x71000):
    sections=(main,b"second",b"third")
    data=bytearray(b"MMM\x00"+struct.pack("<I",3)+bytes(96))
    data.extend(bytes(156-len(data)))
    for i,raw in enumerate(sections):
        packed=lzma.compress(raw,format=lzma.FORMAT_ALONE)
        p=len(data)
        struct.pack_into("<8I",data,8+i*32,p,base_offset+i*0x1000,
                         len(packed),max(4096,len(raw)),p+len(packed),20,1,0)
        data.extend(packed+hashlib.sha1(packed).digest())
    return bytes(data)


def test_image_with_t1_xref():
    # String @0x40, 32-bit pointer @0x20, Thumb T1 LDR @0x10:
    # aligned PC 0x14 plus 0xc equals 0x20; T1 literal imm8 == 3.
    image=bytearray(bytes(0x100))
    name=b"wifi_station_disconnected_event_handler"
    image[0x40:0x40+len(name)]=name
    struct.pack_into("<I",image,0x20,0x10071040)
    struct.pack_into("<H",image,0x10,0x4903)
    return bytes(image)


class Ceiling1ThumbLiteralXrefsTest(unittest.TestCase):
    def test_reference_and_literal_with_correct_flash_alias(self):
        b=test_image_with_t1_xref()
        self.assertEqual(m.word_offsets(b,0x10071040),[0x20])
        self.assertEqual(m.thumb_literal_candidates(b,0x20,0x10071000),[
            {"instruction_offset_hex":"0x10","candidate_opcode":"thumb16_ldr_literal_T1"}])

    def test_no_alias_means_no_valid_reference(self):
        b=test_image_with_t1_xref()
        self.assertEqual(m.word_offsets(b,0x71040),[])
        # Relative LDR encodings cannot determine the absolute memory alias.\n        # Only the absolute 32-bit pointer word comparison disambiguates it.\n        self.assertTrue(m.thumb_literal_candidates(b,0x20,0x71000))

    def test_wrong_word_is_not_a_reference(self):
        self.assertEqual(m.word_offsets(test_image_with_t1_xref(),0x10071041),[])

    def test_unaligned_pointer_is_not_counted(self):
        b=bytearray(80)
        b[17:21]=struct.pack("<I",0x10071040)
        self.assertEqual(m.word_offsets(bytes(b),0x10071040),[])

    def test_t2_positive_pcrel_literal(self):
        # at 0x08: PC=0x0c, target=0x30, imm12=0x24
        b=bytearray(80)
        struct.pack_into("<HH",b,8,0xf8df,0x1024)
        r=m.thumb_literal_candidates(bytes(b),0x30,0x10071000)
        self.assertIn({"instruction_offset_hex":"0x8",
                       "candidate_opcode":"thumb32_ldr_literal_T2"},r)

    def test_t2_negative_pcrel_literal(self):
        b=bytearray(80)
        struct.pack_into("<HH",b,0x30,0xf85f,0x1014)
        r=m.thumb_literal_candidates(bytes(b),0x20,0x10071000)
        self.assertIn({"instruction_offset_hex":"0x30",
                       "candidate_opcode":"thumb32_ldr_literal_T2"},r)

    def test_malformed_pointer_locations_rejected(self):
        b=bytes(16)
        for offset in (-1,2,20):
            self.assertEqual(m.thumb_literal_candidates(b,offset,0x10071000),[])

    def test_non_word_aligned_memory_base_rejected(self):
        self.assertEqual(m.thumb_literal_candidates(bytes(32),12,0x10071001),[])

    def test_known_sha_required_by_investigator(self):
        self.assertEqual(m.investigate(fota(test_image_with_t1_xref()))["status"],"REJECTED")

    def test_valid_synthetic_fota_reference_is_not_cause_proof(self):
        raw=fota(test_image_with_t1_xref())
        with patch.object(verifier,"MODEL_SHA256",hashlib.sha256(raw).hexdigest()):
            r=m.investigate(raw)
        self.assertEqual(r["status"],"STATIC_STRING_LITERAL_XREFS_ONLY")
        self.assertEqual(r["candidate_main_memory_base_hex"],"0x10071000")
        entry=r["markers"]["wifi_event_callbacks"][0]
        self.assertEqual(entry["candidate_mapped_address_hex"],"0x10071040")
        self.assertEqual(entry["pointer_words"][0]["pointer_word_offset_hex"],"0x20")
        self.assertTrue(entry["pointer_words"][0]["thumb_literal_encoding_candidates"])
        self.assertTrue(r["fota_sha1_lzma_verified"])
        self.assertFalse(r["verified_runtime_call_graph"])
        self.assertFalse(r["wifi_firmware_root_cause_proven"])
        self.assertFalse(r["flash_safe"])

    def test_bl_zero_displacement_is_pc_plus_four(self):
        b=struct.pack("<HH",0xf000,0xf800)
        self.assertEqual(m.thumb_bl_target(b,0,0x10071000),0x10071004)

    def test_bl_reconnect_candidate_target_matches_independent_thumb_disassembly(self):
        # Real 4-byte opcode sequence from the researched Thumb ISA; a
        # synthetic codeword fixture, not a manufacturer firmware dump.
        b=bytes.fromhex("fef745ff")
        self.assertEqual(m.thumb_bl_target(b,0,0x100d2ee6),0x100d1d74)

    def test_bl_rejects_non_bl_blx_odd_or_truncated(self):
        self.assertIsNone(m.thumb_bl_target(bytes(4),0,0))
        self.assertIsNone(m.thumb_bl_target(struct.pack("<HH",0xf000,0xe800),0,0))
        self.assertIsNone(m.thumb_bl_target(struct.pack("<HH",0xf000,0xf800),1,0))
        self.assertIsNone(m.thumb_bl_target(bytes(1),0,0))

    def test_nearby_branch_candidate_is_not_firmware_bug_proof(self):
        b=bytearray(64)
        struct.pack_into("<HH",b,0x18,0xf000,0xf800)
        r=m.nearby_bl_candidates(bytes(b),0x10,0x10071000)
        self.assertEqual(r,[{
            "bl_instruction_offset_hex":"0x18",
            "candidate_target_address_hex":"0x1007101c",
        }])

    def test_nearby_scan_is_distance_bounded(self):
        b=bytearray(96)
        struct.pack_into("<HH",b,0x30,0xf000,0xf800)
        self.assertEqual(m.nearby_bl_candidates(bytes(b),0x10,0,12),[])
        self.assertEqual(m.nearby_bl_candidates(bytes(b),0x10,0,65),[])

    def test_marker_without_pointer_is_not_a_function_proof(self):
        raw=fota(b"wifi_station_disconnected_event_handler\0")
        with patch.object(verifier,"MODEL_SHA256",hashlib.sha256(raw).hexdigest()):
            r=m.investigate(raw)
        self.assertEqual(r["markers"]["wifi_event_callbacks"][0]["pointer_words"],[])

    def test_gibberish_not_reported_even_if_same_binary(self):
        raw=fota(b"private-SSID key 12345")
        with patch.object(verifier,"MODEL_SHA256",hashlib.sha256(raw).hexdigest()):
            r=m.investigate(raw)
        self.assertNotIn("private-SSID",str(r))
        self.assertFalse(r["flash_safe"])


if __name__=="__main__":
    unittest.main()
