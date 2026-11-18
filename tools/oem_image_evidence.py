#!/usr/bin/env python3
"""Reproduce stock-image structure evidence offline; NEVER flash or contact lamps.

The fixed catalogue hashes are public and identify only stock OEM files.
CRC and ESP-IDF SHA checks are file integrity, never OEM secure-boot proof.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct

MAX_BYTES = 8 * 1024 * 1024
KNOWN = {
    "yeelink.light.color4": ("2.1.7_0039", 1502532,
        "f13ec12c9d53573748054b36a709e75a",
        "67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa"),
    "yeelink.light.ceiling1": ("1.5.9_0189", 650224,
        "0324c98a9bfde8faf531ab9014231d99",
        "a19801c450c0bd9cd1a3539b6ef4eeabf399b9f99288f86b6b410453c709e3bf"),
}


def xiaomi_crc(data):
    """Xiaomi zero-initial CRC32: reflected 0xEDB88320, no final XOR."""
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0xEDB88320 if crc & 1 else 0)
    return crc & 0xFFFFFFFF


def esp32(data):
    out = {"format": "esp32_image_candidate", "structure_ok": False}
    if len(data) < 24 or data[0] != 0xE9:
        return {"format": "unknown"}
    num = data[1]
    digest_flag = data[23]
    if not 1 <= num <= 16 or digest_flag not in (0, 1):
        return out
    pos, xor = 24, 0xEF
    segments = []
    for _ in range(num):
        if pos + 8 > len(data):
            return out
        addr, size = struct.unpack_from("<II", data, pos)
        pos += 8
        if size > MAX_BYTES or pos + size > len(data):
            return out
        body = data[pos:pos + size]
        for byte in body:
            xor ^= byte
        segments.append((pos, addr, size))
        pos += size
    chk = (pos // 16 + 1) * 16 - 1
    if chk >= len(data):
        return out
    chk_ok = data[chk] == xor and not any(data[pos:chk])
    sha_start = chk + 1
    sha_end = sha_start + (32 if digest_flag else 0)
    if sha_end > len(data):
        return out
    sha_ok = (hashlib.sha256(data[:sha_start]).digest() ==
              data[sha_start:sha_end]) if digest_flag else None
    trail = data[sha_end:]
    crc_ok = len(trail) == 4 and trail == struct.pack("<I", xiaomi_crc(data[:-4]))
    desc = {}
    if segments and segments[0][2] > 128:
        first = segments[0][0]
        if struct.unpack_from("<I", data, first)[0] == 0xABCD5432:
            def readstr(at, n=32):
                raw = data[at:at+n].split(b"\x00", 1)[0]
                return raw.decode("ascii") if raw and all(32 <= c < 127 for c in raw) else ""
            desc = {
                "app_version": readstr(first+16),
                "project": readstr(first+48),
                "built_at": (readstr(first+96,16) + " " + readstr(first+80,16)).strip(),
            }
    err_at = data.find(b"client quota exceeded\x00")
    err = {"error_string_found": err_at >= 0}
    if err_at >= 0:
        err["file_offset_hex"] = hex(err_at)
        for offset, address, size in segments:
            if offset <= err_at < offset + size:
                addr = address + err_at - offset
                err["mapped_address_hex"] = hex(addr)
                needle = struct.pack("<I", addr)
                refs = [hex(p) for p in range(len(data) - 3) if data[p:p+4] == needle]
                err["direct_pointer_offsets_hex"] = refs[:12]
                break
    out.update({
        "structure_ok": chk_ok and sha_ok is not False and crc_ok,
        "segments": num, "chip_id": struct.unpack_from("<H", data, 12)[0],
        "checksum_valid": chk_ok, "internal_sha256_valid": sha_ok,
        "xiaomi_four_byte_crc_valid": crc_ok, "extra_bytes": len(trail),
        "app": desc, "local_lan_error": err,
    })
    return out


def mmm(data):
    """Bounded, SHA1-checked MediaTek-LZMA decoding; never exposes OEM bytes."""
    from mediatek_mmm_evidence import analyze_mmm
    return analyze_mmm(data)


def inspect(data, model):
    if model not in KNOWN:
        raise ValueError("unsupported model")
    version, size, md5, sha = KNOWN[model]
    if len(data) > MAX_BYTES:
        return {"status": "OVERSIZE_REJECTED", "installable": False}
    actual_md5 = hashlib.md5(data).hexdigest()
    actual_sha = hashlib.sha256(data).hexdigest()
    exact = len(data) == size and md5 == actual_md5 and sha == actual_sha
    return {
        "model": model, "version_expected": version,
        "size": len(data), "md5": actual_md5, "sha256": actual_sha,
        "known_stock_catalogue_match": exact,
        "structure": esp32(data) if data.startswith(b"\xE9") else mmm(data),
        "quota_timer_root_cause_proven": False,
        "oem_signature_verified": False,
        "recovery_verified": False,
        "installable": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, choices=sorted(KNOWN))
    parser.add_argument("image", type=Path)
    args = parser.parse_args()
    try:
        with args.image.open("rb") as stream:
            data = stream.read(MAX_BYTES + 1)
        report = inspect(data, args.model)
    except OSError as e:
        report = {"error": type(e).__name__, "installable": False}
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report.get("known_stock_catalogue_match") else 2


if __name__ == "__main__":
    raise SystemExit(main())
