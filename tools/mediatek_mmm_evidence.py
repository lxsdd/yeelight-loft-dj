#!/usr/bin/env python3
"""Read-only bounded MediaTek MMM FOTA image inspector (no firmware output).

The 20-byte per-section SHA-1 values are container integrity digests, NOT
verified cryptographic OEM signatures or any OTA/flash authorization.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import lzma
from pathlib import Path
import struct

MAGIC = b"MMM\x00"
MAX_FILE = 8 * 1024 * 1024
MAX_SEGMENTS = 8
MAX_UNCOMPRESSED = 8 * 1024 * 1024


def analyze_mmm(data: bytes) -> dict:
    report = {"format": "unknown", "complete_integrity": False,
              "cryptographic_oem_authenticity_verified": False,
              "flash_or_ota_approved": False}
    if len(data) < 12 or not data.startswith(MAGIC) or len(data) > MAX_FILE:
        report["reason"] = "missing MMM header or invalid size"
        return report
    n = struct.unpack_from("<I", data, 4)[0]
    if not 1 <= n <= MAX_SEGMENTS or 8 + n * 32 > len(data):
        report["reason"] = "invalid FOTA section count"
        return report
    report["format"] = "MediaTek_MMM_FOTA_LZMA"
    report["section_count"] = n
    report["sections"] = []
    previous_end = 8 + n * 32
    for index in range(n):
        offset, address, packed_size, partition_size, signature_at, signature_size, flag, reserved = struct.unpack_from(
            "<8I", data, 8 + index * 32)
        section = {"index": index, "offset_hex": hex(offset),
                   "load_address_hex": hex(address),
                   "compressed_bytes": packed_size,
                   "partition_bytes": partition_size}
        report["sections"].append(section)
        if (flag != 1 or reserved != 0 or signature_size != 20 or
                packed_size < 13 or offset < previous_end or
                offset + packed_size > len(data) or
                signature_at != offset + packed_size or
                signature_at + signature_size > len(data)):
            report["reason"] = f"invalid section header #{index}"
            return report
        packed = data[offset:signature_at]
        digest = hashlib.sha1(packed).digest()
        if digest != data[signature_at:signature_at + signature_size]:
            section["compressed_sha1_matches_header"] = False
            report["reason"] = f"SHA-1 mismatch in compressed section #{index}"
            return report
        section["compressed_sha1_matches_header"] = True
        section["sha1_hex"] = digest.hex()
        try:
            unpacker = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE,
                                             memlimit=64 * 1024 * 1024)
            raw = unpacker.decompress(packed, max_length=MAX_UNCOMPRESSED + 1)
        except (lzma.LZMAError, EOFError):
            report["reason"] = f"LZMA invalid in section #{index}"
            return report
        if (not unpacker.eof or unpacker.unused_data or
                len(raw) > MAX_UNCOMPRESSED or len(raw) > partition_size):
            report["reason"] = f"LZMA size or EOS invalid in section #{index}"
            return report
        section["decompressed_bytes"] = len(raw)
        section["decompressed_sha256"] = hashlib.sha256(raw).hexdigest()
        if index == 0:
            idx = raw.find(b"client quota exceeded\x00")
            tokens = (b"lan_ctrl_task\x00", b"start_lan_ctrl\x00",
                      b"cfg_lan_ctrl\x00", b"get_prop\x00", b"set_music\x00")
            report["local_lan_evidence"] = {
                "quota_string_found": idx >= 0,
                "quota_string_offset_in_decompressed_main": hex(idx) if idx >= 0 else None,
                "header_based_mapped_address_candidate": hex(address + idx) if idx >= 0 else None,
                "lan_handler_strings_present": [t[:-1].decode("ascii") for t in tokens if t in raw],
                "quota_timer_root_cause_found": False,
            }
        previous_end = signature_at + signature_size
    if previous_end != len(data):
        report["reason"] = "trailing bytes after final FOTA signature"
        return report
    report["complete_integrity"] = True
    report["result"] = "ALL_SECTIONS_INTEGRITY_OK_NO_OTA_AUTHORIZATION"
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    args = parser.parse_args(argv)
    try:
        with args.file.open("rb") as f:
            data = f.read(MAX_FILE + 1)
        result = analyze_mmm(data)
    except OSError as ex:
        result = {"format": "unknown", "reason": type(ex).__name__,
                  "complete_integrity": False, "flash_or_ota_approved": False}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get("complete_integrity") else 2


if __name__ == "__main__":
    raise SystemExit(main())
