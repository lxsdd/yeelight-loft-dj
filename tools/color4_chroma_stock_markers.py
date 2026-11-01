#!/usr/bin/env python3
"""Private read-only stock Color4 Chroma-v2 text-marker inventory.

The OEM image is NEVER committed or copied to CI. Exact SHA-256 and the
already validated 7-segment LAN-error-table integrity are prerequisites.
Printed output contains only allowlisted method names and aggregate counts,
never source bytes, token templates, addresses, user data or firmware chunks.
A compiled string is NOT a verified runtime SUPPORT advertisement.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from color4_lan_quota_firmware_evidence import inspect as verify_stock

MAX_IMAGE_BYTES = 2 * 1024 * 1024
MARKERS = (
    "udp_new", "udp_token", "udp_keep_alive",
    "udp_sess_new", "udp_sess_token", "udp_sess_keep_alive",
    "udp_chroma_sess_new", "chroma", "set_scene", "set_music",
    "client quota exceeded",
)


def count_markers(data: bytes) -> dict[str, dict[str, int]]:
    """Count only allowlisted ASCII needles, never export matching bytes."""
    counts: dict[str, dict[str, int]] = {}
    for marker in MARKERS:
        needle = marker.encode("ascii")
        pos = 0
        total = standalone = suffix = 0
        while True:
            found = data.find(needle, pos)
            if found < 0:
                break
            total += 1
            before_nul = found == 0 or data[found - 1] == 0
            after_nul = found + len(needle) < len(data) and data[found + len(needle)] == 0
            if before_nul and after_nul:
                standalone += 1
            elif after_nul:
                suffix += 1
            pos = found + 1
        counts[marker] = {
            "raw_substring_occurrences": total,
            "standalone_nul_strings": standalone,
            "suffix_of_nul_string": suffix,
            "embedded_or_unterminated": total - standalone - suffix,
        }
    return counts


def inspect_stock_chroma(data: bytes) -> dict:
    # Bound even the verification work; no partial read is accepted.
    if len(data) > MAX_IMAGE_BYTES:
        return {"status": "REJECTED", "stock_image_verified": False,
                "runtime_chroma_capability_proven": False}
    verified = verify_stock(data)
    if not verified.get("stock_image_verified"):
        return {"status": "REJECTED", "stock_image_verified": False,
                "runtime_chroma_capability_proven": False}
    return {
        "status": "STOCK_COMPILED_TEXT_MARKERS_ONLY",
        "model": verified["model"],
        "version": verified["version"],
        "stock_image_verified": True,
        "markers": count_markers(data),
        "runtime_chroma_capability_proven": False,
        "udp_v2_handshake_verified_on_hardware": False,
        "firmware_quota_root_cause_proven": False,
        "firmware_patch_safe": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("private_stock_image", type=Path)
    args = parser.parse_args()
    try:
        with args.private_stock_image.open("rb") as stream:
            data = stream.read(MAX_IMAGE_BYTES + 1)
        result = inspect_stock_chroma(data)
    except (OSError, ValueError):
        result = {"status": "REJECTED", "stock_image_verified": False,
                  "runtime_chroma_capability_proven": False}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result.get("stock_image_verified") else 2


if __name__ == "__main__":
    raise SystemExit(main())
