#!/usr/bin/env python3
"""Offline, non-flashable validation of candidate ceiling1 stock firmware metadata.

Use this only with original images obtained separately from authorized sources.
Older YLXD01YL/ceiling1 is reportedly MTK7697, NOT the color4 ESP32.
A matching MD5 and optional CRC DO NOT prove authenticity or OTA safety.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

MODEL = "yeelink.light.ceiling1"
VERSION_INDEPENDENT = "1.5.9_0189"
VERSION_CLOUD_ONLY = "2.0.6_0193"
INDEPENDENT_MD5 = "0324c98a9bfde8faf531ab9014231d99"
INDEPENDENT_BYTES = 650224
MIN_CLOUD_ONLY = 700000
MAX_CLOUD_ONLY = 800000
MAX_DATA = 2 * 1024 * 1024
HEX32 = re.compile(r"[0-9a-fA-F]{32}\Z")
TABLE = []
for k in range(256):
    c = k
    for _ in range(8):
        c = (c >> 1) ^ (0xEDB88320 if c & 1 else 0)
    TABLE.append(c)

def xiaomi_crc32_zero_init(data: bytes) -> int:
    value = 0
    for b in data:
        value = (value >> 8) ^ TABLE[(value ^ b) & 255]
    return value & 0xffffffff

def inspect(version: str, reported_md5: str, firmware: bytes) -> dict:
    result = {"model": MODEL, "version": version, "status": "REJECTED",
              "installable": False, "manufacturer_signature_verified": False,
              "oem_recovery_proven": False, "firmware_bug_fixed": False}
    if version not in (VERSION_INDEPENDENT, VERSION_CLOUD_ONLY):
        result["reason"] = "unrecognized firmware version"
        return result
    if not isinstance(reported_md5, str) or not HEX32.fullmatch(reported_md5):
        result["reason"] = "invalid cloud checksum"
        return result
    if not 4 <= len(firmware) <= MAX_DATA:
        result["reason"] = "invalid or oversized image"
        return result
    actual = hashlib.md5(firmware).hexdigest()
    if actual != reported_md5.lower():
        result["reason"] = "cloud MD5 differs from actual image"
        return result
    independent = version == VERSION_INDEPENDENT
    if independent and (actual != INDEPENDENT_MD5 or len(firmware) != INDEPENDENT_BYTES):
        result["reason"] = "known independent catalogue differs"
        return result
    if not independent and not MIN_CLOUD_ONLY <= len(firmware) <= MAX_CLOUD_ONLY:
        result["reason"] = "outside known 2.0.6_0193 size range"
        return result
    result.update(
        status="PROVENANCE_CHECK_ONLY",
        length=len(firmware),
        md5=actual,
        sha256=hashlib.sha256(firmware).hexdigest(),
        independent_checksum_confirmed=independent,
        xiaomi_crc_trailer_match_if_present=(
            xiaomi_crc32_zero_init(firmware[:-4]) ==
            struct.unpack("<I", firmware[-4:])[0]
        ),
        reason="Only file integrity/provenance; MTK image compatibility remains unproved",
    )
    return result

def main(argv=None) -> int:
    import argparse
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("version", choices=(VERSION_INDEPENDENT, VERSION_CLOUD_ONLY))
    p.add_argument("cloud_md5", help="checksum only, never a signed CDN URL")
    p.add_argument("file", type=Path)
    args = p.parse_args(argv)
    try:
        with args.file.open("rb") as f:
            image = f.read(MAX_DATA + 1)
        data = inspect(args.version, args.cloud_md5, image)
    except OSError as exc:
        data = {"status": "REJECTED", "reason": type(exc).__name__, "installable": False}
    print(json.dumps(data, indent=2, sort_keys=True))
    return 0 if data["status"] == "PROVENANCE_CHECK_ONLY" else 2

if __name__ == "__main__":
    sys.exit(main())
