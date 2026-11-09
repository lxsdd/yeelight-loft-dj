#!/usr/bin/env python3
"""Read-only Thumb literal-reference research on *one* verified ceiling1 OEM image.

Extends the pre-existing SHA256/MediaTek-FOTA-LZMA verifier. Maps a *candidate*
MT7697 code/data address using the flash alias 0x10000000 + section load offset.
Finds matching 32-bit pointer values and matching Thumb PC-relative LDR
encodings in the decompressed image. Presence of an encoding in raw bytes is
a candidate, NOT a fully reconstructed control-flow graph or firmware bug.
No extracted binary bytes, user data, internet, device access or flash writes.
"""
from __future__ import annotations
import argparse
import json
import lzma
from pathlib import Path
import struct

from ceiling1_wlan_firmware_markers import analyze as verify_stock, MARKERS, MAX_UNPACK

FLASH_ALIAS = 0x10000000
MAX_REFS = 12


def word_offsets(haystack: bytes, word: int) -> list[int]:
    """Aligned 32-bit little-endian references only, never arbitrary substrings."""
    if not 0 <= word <= 0xffffffff:
        return []
    needle = struct.pack("<I", word)
    return [at for at in range(0, len(haystack) - 3, 4)
            if haystack[at:at+4] == needle][:MAX_REFS]


def thumb_literal_candidates(image: bytes, word_offset: int, address_base: int) -> list[dict]:
    """T1 and T2 PC-relative literal-load *encoding candidates*.

    T1: 16-bit LDR Rt,[PC,#imm8*4] 0x4800..0x4fff.
    T2: 32-bit LDR.W with first halfword 0xf8df (add) or 0xf85f (sub).
    PC in Thumb is instruction address + 4, word-aligned for literal loads.
    No general disassembly is claimed; data can look like instructions.
    """
    if (word_offset < 0 or word_offset >= len(image) or
            word_offset & 3 or address_base & 3):
        return []
    refs = []
    for pos in range(0, len(image)-1, 2):
        insn = struct.unpack_from("<H", image, pos)[0]
        pc_aligned = (address_base + pos + 4) & ~3
        if (insn & 0xf800) == 0x4800:
            target = pc_aligned + ((insn & 0xff) << 2)
            if target == address_base + word_offset:
                refs.append({"instruction_offset_hex": hex(pos),
                             "candidate_opcode": "thumb16_ldr_literal_T1"})
        if pos+4 <= len(image) and insn in (0xf8df, 0xf85f):
            second = struct.unpack_from("<H", image, pos+2)[0]
            target = pc_aligned + (second & 0xfff) * (1 if insn==0xf8df else -1)
            if target == address_base + word_offset:
                refs.append({"instruction_offset_hex": hex(pos),
                             "candidate_opcode": "thumb32_ldr_literal_T2"})
        if len(refs) >= MAX_REFS:
            break
    return refs



def thumb_bl_target(image: bytes, instruction_offset: int, address_base: int):
    """Decode only Thumb-2 BL (T1) immediate, returning a target or None.

    This is a syntactic decode, not a proof the halfwords are executed code.
    For ARMv7-M, S:I1:I2:imm10:imm11:0 is a 25-bit signed displacement.
    J1 = NOT(I1 XOR S), J2 = NOT(I2 XOR S).
    """
    i=instruction_offset
    if i < 0 or i % 2 or i + 4 > len(image):
        return None
    first, second=struct.unpack_from("<HH",image,i)
    if (first & 0xf800) != 0xf000 or (second & 0xd000) != 0xd000:
        return None
    sign=(first >> 10) & 1
    i1=(~(((second >> 13) & 1) ^ sign)) & 1
    i2=(~(((second >> 11) & 1) ^ sign)) & 1
    displacement=((sign << 24) | (i1 << 23) | (i2 << 22) |
                  ((first & 1023) << 12) | ((second & 2047) << 1))
    if displacement & (1 << 24):
        displacement -= (1 << 25)
    return address_base + i + 4 + displacement


def nearby_bl_candidates(image: bytes, instruction_offset: int,
                         address_base: int, max_distance: int = 24):
    """Nearby BL encodings, not semantically proven edges or runtime calls."""
    if (instruction_offset < 0 or instruction_offset & 1 or
            max_distance < 0 or max_distance > 64):
        return []
    result=[]
    for i in range(instruction_offset + 2,
                   min(len(image)-3, instruction_offset + max_distance)+1,2):
        target=thumb_bl_target(image,i,address_base)
        if target is not None:
            result.append({
                "bl_instruction_offset_hex":hex(i),
                "candidate_target_address_hex":hex(target),
            })
    return result


def investigate(raw_image: bytes) -> dict:
    result = {
        "status": "REJECTED", "model": "yeelink.light.ceiling1",
        "version": "1.5.9_0189", "memory_base_is_inferred": True,
        "verified_runtime_call_graph": False,
        "wifi_firmware_root_cause_proven": False, "flash_safe": False,
    }
    stock = verify_stock(raw_image)
    if stock.get("status") != "READ_ONLY_MARKERS_CONFIRMED":
        result["reason"] = stock.get("reason", "not verified stock FOTA")
        return result
    offset, load_offset, packed_size = struct.unpack_from("<III",raw_image,8)
    decoder = lzma.LZMADecompressor(
        format=lzma.FORMAT_ALONE,memlimit=32*1024*1024)
    main = decoder.decompress(raw_image[offset:offset+packed_size],
                              max_length=MAX_UNPACK+1)
    if not decoder.eof or decoder.unused_data or len(main)>MAX_UNPACK:
        result["reason"] = "main section cannot be bounded"
        return result
    # Critical distinction: MMM section header load_offset 0x71000 is a
    # flash *offset*, not the address used by firmware Thumb literal pointers.
    # Validated on this exact image by many independent string-word references.
    base=FLASH_ALIAS+load_offset
    result["candidate_main_memory_base_hex"]=hex(base)
    result["fota_sha1_lzma_verified"]=True
    report={}
    for category,markers in MARKERS.items():
        section=[]
        for marker in markers:
            string_offset=main.find(marker)
            if string_offset<0:
                continue
            mapped=base+string_offset
            pointers=word_offsets(main,mapped)
            references=[]
            for word_off in pointers:
                insns=thumb_literal_candidates(main,word_off,base)
                references.append({
                    "pointer_word_offset_hex":hex(word_off),
                    "thumb_literal_encoding_candidates":insns,
                    "nearby_thumb_bl_encoding_candidates": [
                        candidate for insn in insns
                        for candidate in nearby_bl_candidates(
                            main, int(insn["instruction_offset_hex"],16),
                            base)
                    ][:MAX_REFS],
                })
            section.append({
                "marker":marker.decode("ascii"),
                "decompressed_offset_hex":hex(string_offset),
                "candidate_mapped_address_hex":hex(mapped),
                "pointer_words":references,
                "pointer_reference_count_at_most":len(pointers),
            })
        report[category]=section
    result.update(status="STATIC_STRING_LITERAL_XREFS_ONLY",
                  markers=report,
                  reason="Pointer and Thumb literal encodings do not prove behavior or failure")
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image",type=Path)
    args=parser.parse_args()
    try:
        with args.image.open("rb") as f:
            data=f.read(2*1024*1024+1)
        report=investigate(data)
    except (OSError,ValueError,lzma.LZMAError) as err:
        report={"status":"REJECTED","reason":type(err).__name__,
                "flash_safe":False}
    print(json.dumps(report,indent=2,sort_keys=True))
    return 0 if report.get("status")=="STATIC_STRING_LITERAL_XREFS_ONLY" else 2


if __name__=="__main__":
    raise SystemExit(main())
