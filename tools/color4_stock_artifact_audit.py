#!/usr/bin/env python3
"""Audit an ALREADY downloaded color4 stock image against LOCAL cloud metadata.

This command makes zero network/device calls, never writes to the firmware,
and never prints signed URLs, credentials or the JSON input. A match proves
only internal metadata consistency, NOT OEM authenticity or OTA eligibility.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

MAX_IMAGE_BYTES = 32 * 1024 * 1024
MAX_METADATA_BYTES = 16 * 1024
MODEL = "yeelink.light.color4"
VERSION = "2.1.7_0039"
MD5_RE = re.compile(r"^[a-fA-F0-9]{32}$")
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+_[0-9]{4}$")


def _unique_pairs(pairs: list[tuple[str, object]]) -> dict:
    output: dict = {}
    for key, value in pairs:
        if key in output:
            raise ValueError("duplicate metadata key")
        output[key] = value
    return output


def audit(metadata: dict, firmware: bytes, *, expected_version: str = VERSION) -> dict:
    # Never reflect URL, tokens, serial IDs or firmware bytes in output.
    outcome = {"status": "REJECTED", "model_expected": MODEL,
               "version_expected": expected_version,
               "installable": False, "oem_signature_verified": False,
               "proves_quota_fix": False}
    if not VERSION_RE.fullmatch(expected_version):
        outcome["reason"] = "invalid expected version"
        return outcome
    if not isinstance(metadata, dict) or len(firmware) == 0 or len(firmware) > MAX_IMAGE_BYTES:
        outcome["reason"] = "invalid metadata or image size"
        return outcome
    # A valid result nested inside an unsuccessful Xiaomi response must never
    # pass: abort without disclosing the cloud response body.
    if "code" in metadata and (type(metadata["code"]) is not int or metadata["code"] != 0):
        outcome["reason"] = "cloud response indicates failure"
        return outcome
    data = metadata.get("result", metadata)
    if not isinstance(data, dict):
        outcome["reason"] = "metadata result must be object"
        return outcome
    # Xiaomi /home/latest_version payload does not usually contain model;
    # we CANNOT assert it matched merely because caller supplied a model.
    reported_model = data.get("model", None)
    if reported_model is not None and reported_model != MODEL:
        outcome["reason"] = "reported model differs from expected color4"
        return outcome
    version = data.get("version")
    if not isinstance(version, str) or version != expected_version:
        outcome["reason"] = "returned version does not equal requested version"
        return outcome
    md5 = data.get("md5")
    if not isinstance(md5, str) or not MD5_RE.fullmatch(md5):
        outcome["reason"] = "missing or invalid response MD5"
        return outcome
    actual_md5 = hashlib.md5(firmware).hexdigest()
    if actual_md5.lower() != md5.lower():
        outcome["reason"] = "firmware MD5 does not match returned metadata"
        return outcome
    outcome.update({
        "status": "METADATA_AND_BYTES_MATCH_ONLY",
        "metadata_model_field_present": reported_model is not None,
        "firmware_size_bytes": len(firmware),
        "firmware_sha256": hashlib.sha256(firmware).hexdigest(),
        "firmware_md5": actual_md5,
        "reason": "Not manufacturer authenticity, image signature, timer proof or flash safety",
    })
    return outcome


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path, help="local Xiaomi JSON metadata")
    parser.add_argument("image", type=Path, help="local original image, never uploaded")
    parser.add_argument("--expected-version", default=VERSION,
                        help="must match version returned by Xiaomi")
    args = parser.parse_args(argv)
    try:
        if args.metadata.stat().st_size > MAX_METADATA_BYTES:
            raise ValueError("metadata too large")
        with args.metadata.open("r", encoding="utf-8") as stream:
            metadata = json.load(stream, object_pairs_hook=_unique_pairs)
        with args.image.open("rb") as stream:
            firmware = stream.read(MAX_IMAGE_BYTES + 1)
        outcome = audit(metadata, firmware, expected_version=args.expected_version)
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError, TypeError) as exc:
        outcome = {"status": "REJECTED", "reason": type(exc).__name__,
                   "installable": False, "oem_signature_verified": False}
    print(json.dumps(outcome, indent=2, sort_keys=True))
    return 0 if outcome["status"] == "METADATA_AND_BYTES_MATCH_ONLY" else 2


if __name__ == "__main__":
    sys.exit(main())
