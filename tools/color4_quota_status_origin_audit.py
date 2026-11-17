"""Offline SHA-bound provenance audit of the Color4 LAN error-table callers.

Narrow purpose: determine the *actual argument* passed by the two statically
identified direct CALL8 invocations of the JSON LAN error formatters.
This does not find a device quota/refill counter and does not disassemble the
whole Xtensa program. It performs no device I/O and exports no firmware bytes.

Import companion color4_xtensa_controlflow_audit.py from the same directory.
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

from color4_xtensa_controlflow_audit import FirmwareImage, decode, trace, sext, EXPECTED

# Explicitly pinned code sites from the prior SHA-verified control-flow audit.
# Xtensa windowed CALL8 maps caller a10,a11,a12 to callee a2,a3,a4.
# The callee uses its a3 as the error-table index.
WRAPPERS = (
    (0x40116228, 0x4011639B, 0x4011639E, 0x401163A1, 0x40115D08),
    (0x40122964, 0x40122B99, 0x40122B9B, 0x40122B9D, 0x40122834),
)
ERROR_TABLE_ADDR = 0x3FFC01B8
QUOTA_TABLE_INDEX = 5


@dataclass(frozen=True)
class RegisterWrite:
    mnemonic: str
    dest: int
    source: int | None
    immediate: int | None
    size: int


def decode_regwrite(raw: bytes) -> RegisterWrite | None:
    """Limited, fail-closed decoder for immediate/move writes relevant here.

    This is NOT a general Xtensa instruction decoder. A different opcode never
    silently counts as a non-writing instruction. Only the three expected
    instructions MOVI, MOVI.N, MOV.N and ADDI are interpreted.
    """
    if len(raw) < 2:
        return None
    op0 = raw[0] & 15
    size = 2 if 8 <= op0 <= 13 else 3
    if len(raw) != size:
        return None
    val = int.from_bytes(raw, 'little')
    t, s, r = (val >> 4) & 15, (val >> 8) & 15, (val >> 12) & 15
    op1 = (val >> 16) & 15
    if op0 == 2 and r == 10:
        return RegisterWrite('MOVI', t, None, sext(((val >> 16) & 255) | (s << 8), 12), 3)
    if op0 == 12 and t < 8:
        imm = r | ((t & 7) << 4)
        if imm >> 5 == 3:
            imm -= 128
        return RegisterWrite('MOVI.N', s, None, imm, 2)
    if op0 == 13 and r == 0:
        return RegisterWrite('MOV.N', t, s, None, 2)
    if op0 == 2 and r == 12:
        return RegisterWrite('ADDI', t, s, sext((val >> 16) & 255, 8), 3)
    return None


def direct_calls(firmware, target: int) -> list[int]:
    """Raw CALL8 byte candidates; later require valid trace membership.

    Reports *all* decoded direct calls at every code byte offset. No sampled
    instruction boundary is assumed valid just because it looks like CALL8.
    """
    result = []
    for low, high, _, _ in firmware.segments:
        if not firmware.code(low):
            continue
        for pc in range(low, high - 2):
            inst = decode(firmware, pc)
            if inst and inst.kind == 'call' and inst.mnemonic == 'call8' and inst.target == target:
                result.append(pc)
    return result


def inspect_wrapper_call(firmware, sites: tuple[int, int, int, int, int]) -> dict:
    entry, assign_pc, between_pc, call_pc, target = sites
    explored = trace(firmware, entry, max_nodes=3500, max_span=0x1400)
    if explored.unknown or not {assign_pc, between_pc, call_pc}.issubset(explored.visited):
        raise ValueError('required caller path not resolved')
    if direct_calls(firmware, target) != [call_pc]:
        raise ValueError('unreviewed extra direct CALL8 or missing known caller')
    call = decode(firmware, call_pc)
    if call is None or call.mnemonic != 'call8' or call.target != target:
        raise ValueError('unexpected call encoding')
    assignment = decode(firmware, assign_pc)
    intermediate = decode(firmware, between_pc)
    if assignment is None or intermediate is None or assign_pc + assignment.size != between_pc or between_pc + intermediate.size != call_pc:
        raise ValueError('uncertain contiguous instruction sequence')
    write1 = decode_regwrite(firmware.data(assign_pc, assignment.size))
    write2 = decode_regwrite(firmware.data(between_pc, intermediate.size))
    if write1 is None or write1.mnemonic not in {'MOVI', 'MOVI.N'} or write1.dest != 11:
        raise ValueError('CALL8 argument a11 is not a proven immediate')
    if write2 is None or write2.dest != 10 or write2.mnemonic not in {'ADDI', 'MOV.N'}:
        raise ValueError('intervening instruction may overwrite CALL8 a11')
    if write1.immediate is None or not 0 <= write1.immediate <= 6:
        raise ValueError('error table index is not bounded/proven')
    if firmware.u32(ERROR_TABLE_ADDR + 4 * QUOTA_TABLE_INDEX) is None:
        raise ValueError('error table is not mapped')
    return {
        'caller_entry': hex(entry),
        'callsite': hex(call_pc),
        'error_formatter_entry': hex(target),
        'caller_a11_status_index': write1.immediate,
        'callee_a3_status_index': write1.immediate,
        'argument_origin': 'literal_movi_and_intact_call8_window_mapping',
        'is_quota_status_index_5': write1.immediate == QUOTA_TABLE_INDEX,
        'caller_path_syntactically_explored': True,
    }


def audit(path) -> dict:
    firmware = FirmwareImage(path)
    findings = [inspect_wrapper_call(firmware, sites) for sites in WRAPPERS]
    if len(findings) != 2 or any(item['callee_a3_status_index'] != 1 for item in findings):
        raise ValueError('unexpected OEM direct-caller status arguments')
    return {
        'original_sha256': EXPECTED,
        'analysis_kind': 'pinned_direct_callsite_argument_provenance_only',
        'formatter_direct_callers': findings,
        'quota_table_index': QUOTA_TABLE_INDEX,
        'direct_callers_explain_quota_status_5': False,
        'possible_unresolved_indirect_calls_or_other_quota_paths': True,
        'quota_counters_refill_or_49d_bug_proven': False,
        'any_runtime_behavior_or_ota_supported': False,
        'firmware_bytes_exported': False,
    }


def main():
    parser = argparse.ArgumentParser(description='Read-only Color4 pinned-firmware CALL8 status provenance audit')
    parser.add_argument('original_image', help='private original 2.1.7_0039 image; never publish')
    args = parser.parse_args()
    try:
        print(json.dumps(audit(args.original_image), sort_keys=True, indent=2))
    except (OSError, ValueError):
        parser.exit(2, 'REJECTED: image identity or status-provenance proof failed\n')


if __name__ == '__main__':
    main()
