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
import zipfile

PIN = re.compile(r"[0-9a-f]{64}\Z")
DEX = re.compile(r"classes(?:[2-9][0-9]*)?\.dex\Z")
MAX_APK = 128 * 1024 * 1024
MAX_ENTRY = 64 * 1024 * 1024
MAX_DEX = 24
MAX_FILES = 12000
CHUNK = 1024 * 128


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
    for label in ("first", "second"):
        parser.add_argument("--" + label, required=True)
        parser.add_argument("--" + label + "-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        report = compare(fingerprint(args.first, args.first_sha256),
                         fingerprint(args.second, args.second_sha256))
    except VariantBlocked as exc:
        print(json.dumps({"status": "BLOCKED", "reason": str(exc)}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
