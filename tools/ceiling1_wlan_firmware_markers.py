#!/usr/bin/env python3
"""Fail-closed, read-only ceiling1 Wi-Fi diagnostic marker search.

Accepts ONLY the known stock ceiling1 v1.5.9_0189 image, verifies all three
MediaTek FOTA SHA-1/LZMA sections, and emits whitelisted marker offsets ONLY.
Diagnostic strings do NOT prove execution, a fault, or a flashable fix.
"""
import hashlib
import json
import lzma
from pathlib import Path
import struct

MODEL_SHA256 = "a19801c450c0bd9cd1a3539b6ef4eeabf399b9f99288f86b6b410453c709e3bf"
MAX_FILE = 2 * 1024 * 1024
MAX_UNPACK = 2 * 1024 * 1024
MARKERS = {
    "wifi_event_callbacks": (
        b"wifi_station_disconnected_event_handler",
        b"wifi_station_port_secure_event_handler",
        b"wifi disconnected", b"wifi connected",
    ),
    "association_reconnect": (
        b"Auto connect enabled: try to reconnect",
        b"Try to reconnect to the same BSS",
        b"Association request to the driver failed",
    ),
    "dhcp_ip": (
        b"DHCP got Failed", b"DHCP got IP:", b"start DHCP client failed",
    ),
    "driver_watchdog": (
        b"N9 may crashed, reboot...", b"Init watchdog failed.",
    ),
    "service_cloud": (
        b"[minet] communicate with router timeout",
        b"[minet] communicate with router failed",
        b"minet socket connect failed",
    ),
}


def analyze(raw_image):
    result = {
        "model": "yeelink.light.ceiling1", "firmware_version": "1.5.9_0189",
        "catalogue_sha256_matches": False, "fota_integrity_checked": False,
        "wifi_firmware_cause_proven": False, "quota_root_cause_proven": False,
        "flash_safe": False, "status": "REJECTED",
    }
    if (len(raw_image) > MAX_FILE or
            hashlib.sha256(raw_image).hexdigest() != MODEL_SHA256):
        result["reason"] = "not verified exact ceiling1 original firmware"
        return result
    result["catalogue_sha256_matches"] = True
    if (len(raw_image) < 104 or raw_image[:4] != b"MMM\x00" or
            struct.unpack_from("<I", raw_image, 4)[0] != 3):
        result["reason"] = "unsupported FOTA envelope"
        return result
    previous_end = 8 + 3 * 32
    main_section = None
    for index in range(3):
        offset, address, length, capacity, signature, signature_length, flag, reserved = (
            struct.unpack_from("<8I", raw_image, 8 + index * 32)
        )
        if (offset < previous_end or offset + length > len(raw_image) or
                signature != offset + length or
                signature + signature_length > len(raw_image) or
                signature_length != 20 or flag != 1 or reserved != 0 or
                length > MAX_FILE or capacity > MAX_UNPACK):
            result["reason"] = "invalid FOTA bounds"
            return result
        packed = raw_image[offset:signature]
        if hashlib.sha1(packed).digest() != raw_image[signature:signature + 20]:
            result["reason"] = "section digest mismatch"
            return result
        try:
            decoder = lzma.LZMADecompressor(
                format=lzma.FORMAT_ALONE, memlimit=32 * 1024 * 1024,
            )
            unpacked = decoder.decompress(packed, max_length=MAX_UNPACK + 1)
        except (EOFError, lzma.LZMAError, MemoryError):
            result["reason"] = "LZMA invalid"
            return result
        if (not decoder.eof or decoder.unused_data or
                len(unpacked) > capacity or len(unpacked) > MAX_UNPACK):
            result["reason"] = "invalid unpacked section"
            return result
        if index == 0:
            main_section = unpacked
        previous_end = signature + signature_length
    if previous_end != len(raw_image):
        result["reason"] = "FOTA trailing data"
        return result
    assert main_section is not None
    findings = {}
    for family, indicators in MARKERS.items():
        findings[family] = [
            {"marker": marker.decode("ascii"),
             "offset_hex": hex(main_section.index(marker))}
            for marker in indicators if marker in main_section
        ]
    result.update(
        status="READ_ONLY_MARKERS_CONFIRMED", fota_integrity_checked=True,
        markers=findings,
        reason="compiled diagnostic strings only; firmware bug NOT established",
    )
    return result


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    args = parser.parse_args()
    try:
        with args.image.open("rb") as f:
            data = f.read(MAX_FILE + 1)
        result = analyze(data)
    except OSError as ex:
        result = {"status": "REJECTED", "reason": type(ex).__name__,
                  "flash_safe": False}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get("status") == "READ_ONLY_MARKERS_CONFIRMED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
