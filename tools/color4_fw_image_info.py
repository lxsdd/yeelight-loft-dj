#!/usr/bin/env python3
"""Read-only, bounded ESP32 app-image integrity metadata inspection.

Never connects to devices and never modifies a source image. An apparently
valid ESP image does NOT establish an OEM model match, signed OTA eligibility,
board compatibility, firmware authenticity, or recoverability.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

MAX_IMAGE_BYTES = 32 * 1024 * 1024
MAX_SEGMENTS = 16
ESP32_HEADER_BYTES = 24


def inspect(data: bytes) -> dict:
    result = {"file_sha256": hashlib.sha256(data).hexdigest(), "size_bytes": len(data),
              "format": "unknown", "installable": False,
              "secure_boot_verified": False, "oem_identity_verified": False}
    if len(data) < ESP32_HEADER_BYTES or data[0] != 0xE9:
        result["reason"] = "missing ESP32 image magic or extended header"
        return result
    seg_count = data[1]
    if not 1 <= seg_count <= MAX_SEGMENTS:
        result["reason"] = "invalid segment count"
        return result
    result["format"] = "esp32-image-candidate"
    result["segment_count"] = seg_count
    result["chip_id_raw"] = struct.unpack_from("<H", data, 12)[0]
    result["appended_digest_flag"] = data[23]
    if data[23] not in (0, 1):
        result["reason"] = "invalid appended digest flag"
        return result
    pos = ESP32_HEADER_BYTES
    checksum = 0xEF
    for _ in range(seg_count):
        if pos + 8 > len(data):
            result["reason"] = "truncated segment header"
            return result
        _load, length = struct.unpack_from("<II", data, pos)
        pos += 8
        if length > MAX_IMAGE_BYTES or pos + length > len(data):
            result["reason"] = "invalid or truncated segment data"
            return result
        for byte in data[pos:pos + length]:
            checksum ^= byte
        pos += length
    # Espressif: pad to next 16-byte boundary; its last byte is XOR checksum.
    checksum_pos = (pos // 16 + 1) * 16 - 1
    if checksum_pos >= len(data):
        result["reason"] = "missing image checksum footer"
        return result
    result["image_checksum_valid"] = data[checksum_pos] == checksum
    result["footer_padding_zero"] = all(b == 0 for b in data[pos:checksum_pos])
    digest_start = checksum_pos + 1
    image_end = digest_start + (32 if data[23] == 1 else 0)
    if image_end > len(data):
        result["reason"] = "truncated appended SHA-256"
        return result
    result["appended_sha256_valid"] = (
        hashlib.sha256(data[:digest_start]).digest() == data[digest_start:image_end]
        if data[23] == 1 else None
    )
    result["parsed_image_end"] = image_end
    result["trailing_bytes"] = len(data) - image_end
    result["four_byte_vendor_trailer_candidate"] = len(data) - image_end == 4
    result["structural_integrity_ok"] = (
        result["image_checksum_valid"] and result["footer_padding_zero"] and
        result["appended_sha256_valid"] is not False
    )
    result["reason"] = ("only structural checks passed; OEM signature, CRC trailer, "
                        "chip/board compatibility and actual OTA safety NOT verified"
                        if result["structural_integrity_ok"] else "structural integrity failed")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path, help="local OEM firmware image (never uploaded)")
    args = parser.parse_args(argv)
    try:
        with args.image.open("rb") as file:
            data = file.read(MAX_IMAGE_BYTES + 1)
        if len(data) > MAX_IMAGE_BYTES:
            print(json.dumps({"format": "unknown", "reason": "file too large", "installable": False}))
            return 2
        print(json.dumps(inspect(data), sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError) as exc:
        print(json.dumps({"format": "unknown", "reason": type(exc).__name__, "installable": False}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
