#!/usr/bin/env python3
"""Exact-stock-gated, read-only Xtensa CALL8 candidate graph.

This is deliberately NOT a complete Xtensa disassembler. Structural ENTRY
anchors, individual CALL8 opcodes, and reverse byte-level call signatures
cannot prove a reachable function or the cause of Color4 quota failures.
No private OEM bytes are emitted or persisted by the command.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from color4_quota_literal_xrefs import analyze_stock, mapped_segments

MAX_REPORT_ITEMS = 24
MAX_IMAGE_READ = 2 * 1024 * 1024 + 1


def _at_address(image: bytes, segments: list, address: int, size: int = 3):
    for seg in segments:
        if seg.classification == 'possible_instruction_segment' and seg.contains(address, size):
            return image[seg.offset(address):seg.offset(address)+size]
    return None


def call8_target(encoded: bytes, pc: int):
    """ISA CALL8: sign-extended 18-bit word offset from ((PC&~3)+4)."""
    if len(encoded) != 3 or (encoded[0] & 63) != 0x25 or pc < 0 or pc > 0xffffffff:
        return None
    word = int.from_bytes(encoded, 'little')
    offset = (word >> 6) & 0x3ffff
    if offset & 0x20000:
        offset -= 0x40000
    return ((pc & ~3) + 4 * (offset + 1)) & 0xffffffff


def reverse_calls(image: bytes, segments: list, targets: set[int]) -> dict[int, list[int]]:
    """Find possible CALL8 byte triplets; boundaries are unverified."""
    found: dict[int, list[int]] = {target: [] for target in targets}
    for seg in segments:
        if seg.classification != 'possible_instruction_segment':
            continue
        data = image[seg.file_at:seg.file_at+seg.length]
        for pos in range(len(data)-2):
            if data[pos] & 63 != 0x25:
                continue
            pc = seg.load_at + pos
            dest = call8_target(data[pos:pos+3], pc)
            if dest in found:
                found[dest].append(pc)
    return found


def candidate_graph(image: bytes, segments: list, evidence: dict) -> dict:
    """Only ENTRY-anchored, not pointer-dense L32R candidates are prioritized.

    A CALL8 20 bytes after the L32R matches the *observed* wrappers. This
    fixed offset is a triage window, not verified instruction boundaries.
    """
    records = evidence['error_table']['candidate_pc_relative_l32r']
    candidates = []
    for record in records:
        context = record.get('context', {})
        if not (context.get('preceded_by_common_entry_bytes_hint') and
                not context.get('pointer_dense_data_warning')):
            continue
        l32r = int(record['instruction_address_hex'], 16)
        entry_pc = l32r-3
        entry = _at_address(image, segments, entry_pc)
        opcode = _at_address(image, segments, l32r)
        if entry != bytes((0x36, 0x41, 0x01)) or not opcode or opcode[0] & 15 != 1:
            continue
        call_pc = l32r + 20
        call_bytes = _at_address(image, segments, call_pc)
        target = call8_target(call_bytes or b'', call_pc)
        if target is None:
            continue
        helper_entry = _at_address(image, segments, target)
        candidates.append({
            'entry_address_hex': hex(entry_pc),
            'l32r_address_hex': hex(l32r),
            'candidate_call8_address_hex': hex(call_pc),
            'candidate_call8_target_hex': hex(target),
            'callee_has_common_entry_prefix': bool(helper_entry and helper_entry[0] == 0x36),
            'instruction_alignment_verified': False,
            'function_reachable_proven': False,
        })
    candidates.sort(key=lambda c: int(c['entry_address_hex'], 16))
    addresses = {int(c['entry_address_hex'], 16) for c in candidates}
    inbound = reverse_calls(image, segments, addresses)
    for record in candidates:
        entry = int(record['entry_address_hex'], 16)
        sites = inbound[entry]
        record['raw_inbound_call8_count'] = len(sites)
        record['raw_inbound_call8_sites_hex'] = [hex(pc) for pc in sites[:MAX_REPORT_ITEMS]]
        record['inbound_truncated'] = len(sites) > MAX_REPORT_ITEMS
        record['inbound_instruction_boundaries_proven'] = False
    helper_set = {int(c['candidate_call8_target_hex'], 16) for c in candidates}
    common = sorted(helper_set) if len(candidates) >= 2 and len(helper_set) == 1 else []
    reverse = reverse_calls(image, segments, set(common)) if common else {}
    return {
        'candidate_entry_count': len(candidates),
        'candidate_entry_details': candidates[:MAX_REPORT_ITEMS],
        'entry_details_truncated': len(candidates) > MAX_REPORT_ITEMS,
        'shared_helper_candidate_hex': hex(common[0]) if common else None,
        'shared_helper_raw_inbound_call8_count': len(reverse[common[0]]) if common else 0,
        'full_xtensa_disassembly_performed': False,
        'quota_counter_or_refill_function_identified': False,
        'firmware_patch_safe': False,
    }


def analyze_private_stock(image: bytes) -> dict:
    verified = analyze_stock(image)
    if not verified.get('stock_image_verified'):
        return {'status': 'REJECTED', 'stock_image_verified': False,
                'firmware_patch_safe': False}
    return {
        'status': 'STOCK_CALL8_CANDIDATE_GRAPH_ONLY',
        'model': verified['model'], 'version': verified['version'],
        'stock_image_verified': True,
        'graph': candidate_graph(image, mapped_segments(image), verified['analysis']),
        'firmware_patch_safe': False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stock_image', type=Path, help='existing PRIVATE 2.1.7_0039 image')
    args = parser.parse_args()
    try:
        with args.stock_image.open('rb') as stream:
            content = stream.read(MAX_IMAGE_READ)
        result = analyze_private_stock(content)
    except (OSError, ValueError, KeyError):
        result = {'status': 'REJECTED', 'stock_image_verified': False,
                  'firmware_patch_safe': False}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get('stock_image_verified') else 2


if __name__ == '__main__':
    raise SystemExit(main())
