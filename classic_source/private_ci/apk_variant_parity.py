#!/usr/bin/env python3
"""Strict PRIVATE, read-only comparison of two SHA-pinned Classic APKs.

The tool emits only status flags and counts: no proprietary APK bytes, ZIP
entry names, class names, signing material or user/device information. A
comparison does NOT qualify the runtime ClassLoader or Android-17 launch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import sys
import zipfile

PIN = re.compile(r"[0-9a-f]{64}\Z")
DEX = re.compile(r"classes(?:[2-9][0-9]*)?\.dex\Z")
MAX_APK = 128 * 1024 * 1024
MAX_ENTRY = 64 * 1024 * 1024
MAX_DEX = 24
MAX_FILES = 12000
CHUNK = 1024 * 128
MAX_RECEIPT = 16 * 1024


class VariantBlocked(ValueError):
    """Only constant diagnostic codes, not private source descriptions."""


def require(value, code):
    if not value:
        raise VariantBlocked(code)


def _hash_stream(stream, limit):
    digest = hashlib.sha256()
    size = 0
    while True:
        block = stream.read(CHUNK)
        if not block:
            break
        size += len(block)
        require(size <= limit, "VARIANT_ENTRY_TOO_LARGE")
        digest.update(block)
    return digest.hexdigest(), size


def fingerprint(path, expected_sha256):
    require(type(expected_sha256) is str and PIN.fullmatch(expected_sha256),
            "VARIANT_PIN_INVALID")
    require(isinstance(path, (str, Path)), "VARIANT_PATH_INVALID")
    source = Path(path)
    require(not source.is_symlink(), "VARIANT_FILE_NOT_REGULAR")
    try:
        meta = source.stat()
        require(stat.S_ISREG(meta.st_mode) and 0 < meta.st_size <= MAX_APK,
                "VARIANT_FILE_NOT_REGULAR_OR_SIZE")
        with source.open("rb") as stream:
            digest, length = _hash_stream(stream, MAX_APK)
        require(length == meta.st_size, "VARIANT_FILE_CHANGED_DURING_READ")
        require(digest == expected_sha256, "VARIANT_PIN_MISMATCH")
        with zipfile.ZipFile(source) as archive:
            members = archive.infolist()
            require(0 < len(members) <= MAX_FILES, "VARIANT_ARCHIVE_SHAPE")
            names = [m.filename for m in members]
            require(len(names) == len(set(names)), "VARIANT_DUPLICATE_ZIP_MEMBER")
            by_name = {m.filename: m for m in members}
            require("AndroidManifest.xml" in by_name, "VARIANT_MANIFEST_MISSING")
            dex_names = sorted((name for name in names if DEX.fullmatch(name)),
                               key=lambda name: 1 if name == "classes.dex" else int(name[7:-4]))
            require("classes.dex" in dex_names and 1 <= len(dex_names) <= MAX_DEX,
                    "VARIANT_DEX_SHAPE")
            require(not any(re.fullmatch(r"classes[0-9]+\.dex", n) and not DEX.fullmatch(n)
                            for n in names), "VARIANT_DEX_NAME_INVALID")
            require(sum(by_name[n].file_size for n in ("AndroidManifest.xml", *dex_names)) <= MAX_APK,
                    "VARIANT_TOTAL_EXTRACTED_LIMIT")
            results = {}
            for name in ("AndroidManifest.xml", *dex_names):
                member = by_name[name]
                require(not member.is_dir() and member.file_size > 0 and
                        member.file_size <= MAX_ENTRY and member.flag_bits & 1 == 0,
                        "VARIANT_ARCHIVE_ENTRY_INVALID")
                with archive.open(member, "r") as stream:
                    check, observed = _hash_stream(stream, MAX_ENTRY)
                require(observed == member.file_size, "VARIANT_ENTRY_LENGTH_MISMATCH")
                results[name] = check
        # Refuse a raced replacement of the input even when the original pin matched.
        with source.open("rb") as stream:
            digest_again, length_again = _hash_stream(stream, MAX_APK)
        require(digest_again == expected_sha256 and length_again == length,
                "VARIANT_FILE_CHANGED_DURING_READ")
    except VariantBlocked:
        raise
    except (OSError, ValueError, RuntimeError, EOFError, zipfile.BadZipFile,
            OverflowError, NotImplementedError):
        raise VariantBlocked("VARIANT_FILE_OR_ARCHIVE_UNREADABLE") from None
    return {"archive_sha256": digest, "dex_names": tuple(dex_names),
            "dex_hashes": tuple(results[name] for name in dex_names),
            "manifest_hash": results["AndroidManifest.xml"]}


def fingerprint_receipt(text, expected_sha256):
    """Parse a bounded *unattested* read-only APK digest receipt.

    A receipt is input data, not proof that a device ever produced it. The
    caller must establish who collected it; even an exact digest comparison
    cannot waive missing OEM classes or attest the ART runtime classloader.
    Unknown keys are rejected instead of silently accepting a damaged log.
    """
    require(type(expected_sha256) is str and PIN.fullmatch(expected_sha256),
            "VARIANT_PIN_INVALID")
    require(type(text) is str and 0 < len(text) <= MAX_RECEIPT,
            "VARIANT_RECEIPT_SHAPE")
    lines = text.splitlines()
    require(0 < len(lines) <= 64, "VARIANT_RECEIPT_SHAPE")
    fields = {}
    for line in lines:
        require(line.count("=") == 1, "VARIANT_RECEIPT_FIELD")
        key, value = line.split("=", 1)
        require(key not in fields, "VARIANT_RECEIPT_DUPLICATE")
        require(key in ("DEVICE", "APK_HASH_VERIFIED", "DEX_ENTRIES",
                        "AndroidManifest.xml", "APK_SAVED_TO_DISK",
                        "DEVICE_WRITES", "INSTALLED_BASE_SHA256") or DEX.fullmatch(key),
                "VARIANT_RECEIPT_UNKNOWN_FIELD")
        require(bool(value) and len(value) <= 128 and
                all(32 <= ord(c) <= 126 for c in value),
                "VARIANT_RECEIPT_FIELD")
        fields[key] = value
    require(fields.get("APK_HASH_VERIFIED") == "PASS" and
            fields.get("APK_SAVED_TO_DISK") == "NO" and
            fields.get("DEVICE_WRITES") == "NO",
            "VARIANT_RECEIPT_READ_ONLY_UNVERIFIED")
    if "INSTALLED_BASE_SHA256" in fields:
        require(fields["INSTALLED_BASE_SHA256"] == expected_sha256,
                "VARIANT_RECEIPT_ARCHIVE_PIN_MISMATCH")
    count = fields.get("DEX_ENTRIES", "")
    require(count.isdecimal() and len(count) <= 2 and
            1 <= int(count) <= MAX_DEX, "VARIANT_RECEIPT_DEX_COUNT")
    dex_names = ("classes.dex",) + tuple("classes%d.dex" % n
                                             for n in range(2, int(count) + 1))
    actual_names = {key for key in fields if DEX.fullmatch(key)}
    require(actual_names == set(dex_names), "VARIANT_RECEIPT_DEX_LAYOUT")
    digests = [fields.get(name) for name in ("AndroidManifest.xml", *dex_names)]
    require(all(type(digest) is str and PIN.fullmatch(digest)
                for digest in digests), "VARIANT_RECEIPT_DIGEST_INVALID")
    return {"archive_sha256": expected_sha256, "dex_names": dex_names,
            "dex_hashes": tuple(digests[1:]), "manifest_hash": digests[0]}


def compare(first, second):
    """Return only non-sensitive classification; not OEM class equivalence proof."""
    archive_equal = first["archive_sha256"] == second["archive_sha256"]
    manifest_equal = first["manifest_hash"] == second["manifest_hash"]
    dex_layout_equal = first["dex_names"] == second["dex_names"]
    dex_equal = dex_layout_equal and first["dex_hashes"] == second["dex_hashes"]
    status = ("IDENTICAL_ARCHIVE" if archive_equal else
              "DEX_AND_MANIFEST_EQUAL_ARCHIVE_DIFFERS" if dex_equal and manifest_equal else
              "DEX_EQUAL_MANIFEST_DIFFERS" if dex_equal else
              "DEX_PAYLOAD_DIFFERS" if dex_layout_equal else
              "DEX_LAYOUT_DIFFERS")
    return {"status": status, "archive_equal": archive_equal,
            "manifest_payload_equal": manifest_equal,
            "dex_layout_equal": dex_layout_equal,
            "dex_payloads_equal": dex_equal,
            "dex_counts": [len(first["dex_names"]), len(second["dex_names"])],
            "classes_verified": False,
            "actual_classloader_verified": False,
            "android_runtime_tested": False,
            "resources_or_signing_verified": False,
            "source_paths_or_contents_emitted": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    first = parser.add_mutually_exclusive_group(required=True)
    first.add_argument("--first", help="first pinned APK in the protected workspace")
    first.add_argument("--first-receipt-stdin", action="store_true",
                       help="parse a read-only digest receipt from stdin; never trust it as attestation")
    parser.add_argument("--first-sha256", required=True)
    parser.add_argument("--second", required=True)
    parser.add_argument("--second-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        if args.first_receipt_stdin:
            try:
                receipt = sys.stdin.read(MAX_RECEIPT + 1)
            except (OSError, UnicodeError):
                raise VariantBlocked("VARIANT_RECEIPT_UNREADABLE") from None
            first_info = fingerprint_receipt(receipt, args.first_sha256)
        else:
            first_info = fingerprint(args.first, args.first_sha256)
        report = compare(first_info, fingerprint(args.second, args.second_sha256))
        if args.first_receipt_stdin:
            # A caller-supplied hash log is not an authenticated APK read or
            # device proof, regardless of its internal APK_HASH_VERIFIED flag.
            report["status"] = "UNATTESTED_RECEIPT_" + report["status"]
            report["device_receipt_independently_attested"] = False
            report["diagnostic_only"] = True
    except VariantBlocked as exc:
        print(json.dumps({"status": "BLOCKED", "reason": str(exc)}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
