#!/usr/bin/env python3
"""Content-preserving ZIP layout repair for the PRIVATE Classic clone only.

A genuine signed Android17 candidate exposed a missing secondary-OEM-DEX class
despite the installed base APK being in DexPathList. This deterministic packing
change removes compressed classesN.dex as one possible cause. It does NOT claim
the classloader problem is resolved without a further real private emulator run.

Private OEM/bridge dex payload bytes remain unchanged, and no private contents,
paths or class names are returned. Existing zipalign/sign/graph checks still run.
"""
from __future__ import annotations
from copy import copy
import hashlib
import os
from pathlib import Path
import re
import tempfile
from zipfile import ZipFile, ZIP_STORED, BadZipFile

DEX = re.compile(r"classes(?:[2-9][0-9]*)?\.dex\Z")
STOCK = frozenset({"classes.dex", "classes2.dex"})
CANDIDATE = STOCK | frozenset({"classes3.dex"})

class DexTransportBlocked(ValueError):
    pass

def _inventory(archive, expected):
    infos = archive.infolist()
    names = [i.filename for i in infos]
    if len(names) != len(set(names)):
        raise DexTransportBlocked("MULTIDEX_DUPLICATE_ZIP_MEMBER")
    if set(name for name in names if DEX.fullmatch(name)) != expected:
        raise DexTransportBlocked("MULTIDEX_ENTRY_SET_CHANGED")
    if "AndroidManifest.xml" not in names:
        raise DexTransportBlocked("MULTIDEX_MANIFEST_MISSING")
    return infos

def _content_equal(before, after):
    if before.namelist() != after.namelist():
        return False
    for name in before.namelist():
        if hashlib.sha256(before.read(name)).digest() != hashlib.sha256(after.read(name)).digest():
            return False
    return True

def normalize_private_multidex(original: Path, candidate: Path):
    """ZIP_STORED each cloned classesN.dex; never modify original OEM APK or DEX payloads.

    The source APK remains immutable, the rebuilt unsigned APK alone is
    rewritten atomically if needed, and all member data is hash-compared
    before publication to the signer. zipalign later aligns stored entries.
    """
    if (not isinstance(original, Path) or not isinstance(candidate, Path)
            or original == candidate or not original.is_file() or original.is_symlink()
            or not candidate.is_file() or candidate.is_symlink()):
        raise DexTransportBlocked("MULTIDEX_INPUT_INVALID")
    try:
        with ZipFile(original) as stock, ZipFile(candidate) as built:
            _inventory(stock, STOCK)
            entries = _inventory(built, CANDIDATE)
            was_stored = built.getinfo("classes2.dex").compress_type == ZIP_STORED
            original_stored = stock.getinfo("classes2.dex").compress_type == ZIP_STORED
            changed = any(built.getinfo(name).compress_type != ZIP_STORED for name in CANDIDATE)
            report = {
                "status": "PRIVATE_MULTIDEX_ZIP_STORED_RUNTIME_UNVERIFIED",
                "original_secondary_stored": original_stored,
                "candidate_secondary_stored_before": was_stored,
                "all_candidate_dex_stored_after": True,
                "transport_changed": changed,
                "dex_member_count": len(CANDIDATE),
                "oem_dex_payload_changed": False,
                "startup_verified": False,
            }
            if not changed:
                return report
            with tempfile.TemporaryDirectory(
                    dir=candidate.parent, prefix=".private-classic-dex-zip-") as work:
                output_path = Path(work) / "unsigned-transport.apk"
                with ZipFile(output_path, "w", allowZip64=True) as dst:
                    dst.comment = built.comment
                    for info in entries:
                        copied = copy(info)
                        if info.filename in CANDIDATE:
                            copied.compress_type = ZIP_STORED
                        dst.writestr(copied, built.read(info.filename),
                                     compress_type=copied.compress_type)
                with ZipFile(output_path) as check:
                    if not _content_equal(built, check):
                        raise DexTransportBlocked("MULTIDEX_ZIP_PAYLOAD_DRIFT")
                    if any(check.getinfo(n).compress_type != ZIP_STORED for n in CANDIDATE):
                        raise DexTransportBlocked("MULTIDEX_ZIP_NOT_STORED")
                os.replace(output_path, candidate)
            return report
    except (BadZipFile, OSError, RuntimeError, NotImplementedError) as exc:
        raise DexTransportBlocked("MULTIDEX_ARCHIVE_UNREADABLE") from exc


def private_multidex_status_lines(preflight: dict) -> tuple[str, ...]:
    """Only static yes/no values can escape a protected owner's runner."""
    if type(preflight) is not dict:
        raise DexTransportBlocked("MULTIDEX_REPORT_INVALID")
    value = preflight.get("secondary_dex_transport")
    if type(value) is not dict or value.get("status") != "PRIVATE_MULTIDEX_ZIP_STORED_RUNTIME_UNVERIFIED":
        raise DexTransportBlocked("MULTIDEX_REPORT_INVALID")
    keys = ("original_secondary_stored", "candidate_secondary_stored_before",
            "all_candidate_dex_stored_after", "transport_changed", "oem_dex_payload_changed")
    if any(type(value.get(key)) is not bool for key in keys):
        raise DexTransportBlocked("MULTIDEX_REPORT_INVALID")
    if not value["all_candidate_dex_stored_after"] or value["oem_dex_payload_changed"]:
        raise DexTransportBlocked("MULTIDEX_REPORT_UNSAFE")
    return (
        "PRIVATE_CLASSIC_ORIGINAL_SECONDARY_DEX_STORED=" +
        ("YES" if value["original_secondary_stored"] else "NO"),
        "PRIVATE_CLASSIC_CLONE_SECONDARY_DEX_PREVIOUSLY_STORED=" +
        ("YES" if value["candidate_secondary_stored_before"] else "NO"),
        "PRIVATE_CLASSIC_MULTIDEX_ZIP_REPACK_APPLIED=" +
        ("YES" if value["transport_changed"] else "NO"),
    )


if __name__ == "__main__":
    import json
    import sys
    try:
        if sys.argv != [sys.argv[0], "--owner-only-transport-status"]:
            raise DexTransportBlocked("MULTIDEX_REPORT_ARGUMENT_INVALID")
        runtime = os.environ.get("RUNNER_TEMP", "")
        if not runtime or not Path(runtime).is_absolute():
            raise DexTransportBlocked("MULTIDEX_REPORT_RUNNER_REQUIRED")
        report_path = Path(runtime) / "private-output" / "classic-loft-dj-UNSIGNED.apk.preflight.json"
        if not report_path.is_file() or report_path.is_symlink():
            raise DexTransportBlocked("MULTIDEX_REPORT_FILE_INVALID")
        payload = json.loads(report_path.read_text(encoding="utf-8"))
        for line in private_multidex_status_lines(payload):
            print(line)
    except (DexTransportBlocked, OSError, ValueError, TypeError):
        print("PRIVATE_CLASSIC_MULTIDEX_REPORT_BLOCKED", file=sys.stderr)
        sys.exit(2)
