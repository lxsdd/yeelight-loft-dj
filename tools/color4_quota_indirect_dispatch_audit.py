"""Bounded SHA-pinned offline control-transfer inventory for Color4 OEM error emitters.

Only *static* direct CALLN encodings and literal pointer words are inventoried.
The Xtensa core also supports CALLXn/JX via registers, runtime generated code,
callbacks and unresolved dataflow. Absence of a direct call does not establish
absence of a runtime error, quota limiter, or 49.7-day timeout defect.

No hardware, network, firmware mutation, OEM bytes in output or upload.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import argparse
import json

from color4_xtensa_controlflow_audit import FirmwareImage, decode, EXPECTED
from color4_quota_status_origin_audit import WRAPPERS, inspect_wrapper_call, QUOTA_TABLE_INDEX

ERROR_TABLE_ADDR = 0x3FFC01B8
ERROR_STRING_POINTER = 0x3F40FD74
ENTRY_POINTS = (0x40115D08, 0x40122834)


@dataclass(frozen=True)
class Site:
    address: int
    mnemonic: str


def enumerate_direct_calls(firmware, target: int) -> list[Site]:
    """Enumerate every 3-byte CALLN encoding in mapped executable segments.

    Byte offset candidates are not instruction-boundary evidence. The two
    known hits are independently checked by the established CFG/provenance
    validator. Inclusion of unrelated bytes can only create false positives,
    not conceal a correctly encoded direct call in the scanned segments.
    """
    sites = []
    for low, high, _, _ in firmware.segments:
        if not firmware.code(low):
            continue
        for address in range(low, high - 2):
            instruction = decode(firmware, address)
            if (instruction is not None and instruction.kind == 'call' and
                    instruction.target == target):
                sites.append(Site(address, instruction.mnemonic))
    return sites


def enumerate_direct_pointers(firmware, target: int) -> list[int]:
    """Return mapped aligned target-pointer words (not proven callback usages)."""
    locations = []
    for low, high, _, _ in firmware.segments:
        for address in range((low + 3) & ~3, high - 3, 4):
            if firmware.u32(address) == target:
                locations.append(address)
    return locations


def audit(image_path) -> dict:
    firmware = FirmwareImage(image_path)
    if firmware.u32(ERROR_TABLE_ADDR + QUOTA_TABLE_INDEX * 4) != ERROR_STRING_POINTER:
        raise ValueError('quota string pointer not in expected exact OEM table slot')
    by_target = []
    for wrapper in WRAPPERS:
        caller = inspect_wrapper_call(firmware, wrapper)
        target = wrapper[-1]
        sites = enumerate_direct_calls(firmware, target)
        words = enumerate_direct_pointers(firmware, target)
        expected_direct = [Site(wrapper[3], 'call8')]
        # If stock image evolves or the decoder changes, never silently claim
        # "only invalid command" without repeating analysis at every new site.
        if sites != expected_direct:
            raise ValueError('uncertified new or missing direct invocation')
        if words:
            raise ValueError('uncertified mapped absolute pointer to formatter')
        if caller['callee_a3_status_index'] != 1:
            raise ValueError('direct caller is not pinned to invalid-command index')
        by_target.append({
            'formatter': hex(target),
            'verified_direct_callsite': hex(wrapper[3]),
            'call_kind': 'CALL8',
            'literal_status_index': 1,
            'direct_call_candidates_all_CALLN_types': len(sites),
            'mapped_aligned_absolute_pointer_words': len(words),
        })
    return {
        'original_sha256': EXPECTED,
        'stock_error_table_quota_string_index': QUOTA_TABLE_INDEX,
        'known_error_formatters': by_target,
        'direct_known_formatter_calls_explain_quota': False,
        'possible_indirect_CALLX_or_other_formatter_paths': True,
        'live_firmware_version_and_48_day_uptime_verified': False,
        'quota_root_cause_identified': False,
        'only_control_transfer_encoding_inventory_not_complete_disassembly': True,
        'hardware_used': False,
        'original_binary_exported': False,
    }


def main():
    p = argparse.ArgumentParser(description='Read-only pinned Color4 control-transfer provenance')
    p.add_argument('image', type=Path)
    args = p.parse_args()
    try:
        print(json.dumps(audit(args.image), indent=2, sort_keys=True))
    except (ValueError, OSError):
        p.exit(2, 'REJECTED: stock image identity or complete known-call inventory changed\n')


if __name__ == '__main__':
    main()
