#!/usr/bin/env python3
"""Read-only Xtensa L32R *candidate* links for exact stock color4 2.1.7_0039.

Offline only. Produces mapped addresses and file offsets, NEVER firmware bytes.
Scanning apparent 24-bit instruction patterns does not disassemble reachable
code. LITBASE-relative instructions, runtime RAM, dispatch branches, timer
callbacks and real quota-refill logic are NOT identified or authenticated.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import struct

from color4_lan_quota_firmware_evidence import (
    MAX_FILE, STOCK_SHA256, STOCK_SIZE, firmware_error_table, inspect as stock_inspect,
)

MAX_LINKS = 32


@dataclass(frozen=True)
class Segment:
    file_at: int
    load_at: int
    length: int
    classification: str

    def contains(self, address: int, size: int = 1) -> bool:
        return self.load_at <= address and address + size <= self.load_at + self.length

    def offset(self, address: int) -> int:
        return self.file_at + address - self.load_at


def _classification(address: int, length: int) -> str:
    end = address + length
    if (0x40080000 <= address < end <= 0x40100000 or
            0x400D0000 <= address < end <= 0x40400000):
        return "possible_instruction_segment"
    if (0x3F400000 <= address < end <= 0x3F800000 or
            0x3FFB0000 <= address < end <= 0x40000000):
        return "data_segment"
    return "unknown_segment"


def mapped_segments(image: bytes) -> list[Segment]:
    """Validate bounded image segment mapping. Never examine header/trailer as code."""
    if len(image) < 24 or len(image) > MAX_FILE or image[0] != 0xE9:
        raise ValueError("invalid image size/magic")
    count = image[1]
    if count not in range(1, 17):
        raise ValueError("invalid segment count")
    cursor = 24
    result: list[Segment] = []
    for _ in range(count):
        if cursor + 8 > len(image):
            raise ValueError("truncated segment header")
        address, length = struct.unpack_from("<II", image, cursor)
        cursor += 8
        if not length or length > MAX_FILE or cursor + length > len(image) or (
                address + length > 0x100000000):
            raise ValueError("truncated/overrun segment")
        segment = Segment(cursor, address, length, _classification(address, length))
        for old in result:
            if max(segment.load_at, old.load_at) < min(
                    segment.load_at + segment.length, old.load_at + old.length):
                raise ValueError("ambiguous overlapping mapped segments")
        result.append(segment)
        cursor += length
    return result


def mapped_word_references(image: bytes, segments: list[Segment], target: int) -> list[dict]:
    """Locate literal 32-bit values, not verified code references."""
    needle = struct.pack("<I", target)
    refs = []
    for seg in segments:
        content = image[seg.file_at:seg.file_at + seg.length]
        pos = 0
        while True:
            at = content.find(needle, pos)
            if at < 0:
                break
            pos = at + 1
            address = seg.load_at + at
            # L32R loads 32-bit aligned literal words.
            if address & 3:
                continue
            refs.append({
                "literal_file_offset_hex": hex(seg.file_at + at),
                "literal_address_hex": hex(address),
                "segment_class": seg.classification,
            })
    return refs


def _candidate_context(image: bytes, segments: list[Segment],
                       seg: Segment, at: int) -> dict:
    """Weak byte-level hints, explicitly NOT verified Xtensa instruction flow.

    An L32R-looking byte sequence may occur inside an array of mapped
    pointers. Examine only a fixed 12-word neighborhood and keep the raw
    candidate even when the context looks like data. Conversely, a nearby
    common ENTRY encoding is a useful triage hint, never a proof of a
    reachable function or its behavior.
    """
    pc = seg.load_at + at
    aligned = pc & ~3
    values = []
    for address in range(aligned - 24, aligned + 24, 4):
        if not seg.contains(address, 4):
            continue
        value = struct.unpack_from("<I", image, seg.offset(address))[0]
        values.append(any(other.contains(value) for other in segments))
    prefix = image[seg.file_at + at - 3:seg.file_at + at] if at >= 3 else b""
    return {
        "nearby_mapped_pointer_words": sum(values),
        "nearby_aligned_words_examined": len(values),
        "pointer_dense_data_warning": len(values) >= 8 and sum(values) >= 8,
        "preceded_by_common_entry_bytes_hint": prefix == bytes((0x36, 0x41, 0x01)),
        "verified_reachable_instruction": False,
    }


def possible_l32r_sites(image: bytes, segments: list[Segment],
                        literal_addresses: set[int]) -> list[dict]:
    """Find raw RI16 *candidates* assuming default PC-relative L32R mode.

    Xtensa opcode: low nibble 0001; t nibble 4..7; imm16 at bits 8..23.
    Target: ((PC + 3) & ~3) + ((0xffff0000 | imm16) << 2).
    Scan each byte because Xtensa instructions are variable length; this
    deliberately over-approximates reachable instructions and may find data.
    """
    candidates: list[dict] = []
    for seg in segments:
        if seg.classification != "possible_instruction_segment":
            continue
        body = image[seg.file_at:seg.file_at + seg.length]
        for at in range(len(body) - 2):
            b0 = body[at]
            if b0 & 0x0F != 1:
                continue
            imm = body[at + 1] | body[at + 2] << 8
            pc = seg.load_at + at
            literal = ((pc + 3) & ~3) + ((imm - 65536) << 2)
            if literal not in literal_addresses:
                continue
            candidates.append({
                "instruction_file_offset_hex": hex(seg.file_at + at),
                "instruction_address_hex": hex(pc),
                "literal_address_hex": hex(literal),
                "target_register": b0 >> 4,
                "verified_decoded_instruction": False,
                "context": _candidate_context(image, segments, seg, at),
            })
    return candidates


def candidate_links(image: bytes, table_address: int,
                    quota_string_address: int) -> dict:
    """Low-level pure synthetic-testable mapper, no stock trust assertion."""
    segments = mapped_segments(image)
    result: dict = {}
    for label, target in (
        ("error_table", table_address),
        ("quota_error_string", quota_string_address),
    ):
        references = mapped_word_references(image, segments, target)
        literals = {int(r["literal_address_hex"], 16) for r in references}
        l32r = possible_l32r_sites(image, segments, literals)
        result[label] = {
            "target_address_hex": hex(target),
            "aligned_pointer_word_count": len(references),
            "pointer_words": references[:MAX_LINKS],
            "candidate_pc_relative_l32r_count": len(l32r),
            "candidate_pc_relative_l32r": l32r[:MAX_LINKS],
            "truncated": len(references) > MAX_LINKS or len(l32r) > MAX_LINKS,
            "code_xref_confirmed": False,
        }
    result["mapped_segment_count"] = len(segments)
    result["xtensa_instruction_flow_disassembled"] = False
    result["live_firmware_quota_state_observed"] = False
    result["firmware_quota_root_cause_proven"] = False
    result["patch_or_flash_safe"] = False
    return result


def analyze_stock(image: bytes) -> dict:
    """Only exact SHA-256/length and validated stock error table can pass."""
    rejected = {
        "status": "REJECTED",
        "stock_sha256_expected": STOCK_SHA256,
        "stock_image_verified": False,
        "firmware_quota_root_cause_proven": False,
        "patch_or_flash_safe": False,
    }
    if len(image) != STOCK_SIZE or len(image) > MAX_FILE:
        return rejected
    baseline = stock_inspect(image)
    if not baseline.get("stock_image_verified"):
        return rejected
    error = firmware_error_table(image)
    if error is None:
        return rejected
    try:
        links = candidate_links(
            image,
            int(error["pointer_table_mapped_address_hex"], 16),
            int(error["quota_error_string_mapped_address_hex"], 16),
        )
    except (ValueError, struct.error):
        return rejected
    return {
        "status": "EXACT_STOCK_CANDIDATE_LINKS_ONLY",
        "stock_image_verified": True,
        "model": baseline["model"],
        "version": baseline["version"],
        "error_table_file_offset_hex": error["pointer_table_file_offset_hex"],
        "firmware_quota_root_cause_proven": False,
        "patch_or_flash_safe": False,
        "analysis": links,
        "warning": "Unverified raw RI16 patterns, not reachable Xtensa code or timer logic",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_image", type=Path,
                        help="existing private color4 OEM image, NEVER upload")
    args = parser.parse_args()
    try:
        with args.stock_image.open("rb") as handle:
            image = handle.read(MAX_FILE + 1)
        result = analyze_stock(image)
    except OSError:
        result = {"status": "REJECTED", "reason": "local image unavailable",
                  "stock_image_verified": False, "patch_or_flash_safe": False}
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0 if result.get("stock_image_verified") else 2


if __name__ == "__main__":
    raise SystemExit(main())
