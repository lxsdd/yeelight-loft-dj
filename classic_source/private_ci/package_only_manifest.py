#!/usr/bin/env python3
"""Private binary-AXML package-only ablation of an audited identity control.

Changes exactly the AndroidManifest.xml package string and NO other decoded
manifest attribute or APK entry. Never ships or publishes a test APK.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from apk_readonly_audit import (
    AxmlError, parse_binary_xml, string_pool, u16, u32,
    variable_utf8_length, variable_utf16_length,
)
from classic_first import ORIGINAL_SHA256, OLD_PACKAGE, NEW_PACKAGE, digest_file

class PackageOnlyBlocked(ValueError):
    pass

def require(ok, code):
    if not ok:
        raise PackageOnlyBlocked(code)

def replace_package_only(data):
    """Change only the root manifest package attribute's referenced pool string.

    AXML pools are allowed to contain duplicate literals. Do not infer which
    index backs the root attribute from a global search. Reject any shared
    reference that would change another attribute, or ambiguous raw/typed refs.
    """
    require(type(data) is bytes and 8 <= len(data) <= 8 * 1024 * 1024,
            "PACKAGE_ONLY_AXML_SIZE_INVALID")
    before = parse_binary_xml(data)
    roots = [e for e in before["elements"] if e["tag"] == "manifest"]
    require(len(roots) == 1 and roots[0]["attributes"].get("package") == OLD_PACKAGE,
            "PACKAGE_ONLY_ORIGINAL_PACKAGE_INVALID")
    require(len(OLD_PACKAGE) == len(NEW_PACKAGE) and
            len(OLD_PACKAGE.encode("utf-16-le")) == len(NEW_PACKAGE.encode("utf-16-le")),
            "PACKAGE_ONLY_LENGTH_MISMATCH")
    pool = None
    pool_location = None
    selected = []
    off = 8
    while off < len(data):
        typ, hdr, size = u16(data, off), u16(data, off + 2), u32(data, off + 4)
        require(hdr >= 8 and size >= hdr and off + size <= len(data),
                "PACKAGE_ONLY_XML_CHUNK_INVALID")
        if typ == 1:
            require(pool is None and hdr >= 28, "PACKAGE_ONLY_POOL_INVALID")
            count = u32(data, off + 8)
            require(count <= 200000 and off + hdr + count * 4 <= off + size,
                    "PACKAGE_ONLY_POOL_RANGE_INVALID")
            pool = string_pool(data, off, off + size)
            pool_location = (off, hdr, size, u32(data, off + 16))
        elif typ == 0x0102:
            require(pool is not None and hdr >= 16, "PACKAGE_ONLY_XML_ELEMENT_INVALID")
            name_idx = u32(data, off + 20)
            require(name_idx < len(pool), "PACKAGE_ONLY_XML_ELEMENT_INVALID")
            if pool[name_idx] == "manifest":
                attr_start = u16(data, off + 24)
                attr_width = u16(data, off + 26)
                n = u16(data, off + 28)
                require(attr_width >= 20 and n <= 1000,
                        "PACKAGE_ONLY_XML_ATTR_INVALID")
                attrs = off + 16 + attr_start
                require(attrs + n * attr_width <= off + size,
                        "PACKAGE_ONLY_XML_ATTR_INVALID")
                for i in range(n):
                    pos = attrs + i * attr_width
                    ns_idx, key_idx, raw_idx = (u32(data, pos),
                                                u32(data, pos + 4),
                                                u32(data, pos + 8))
                    require(key_idx < len(pool), "PACKAGE_ONLY_XML_ATTR_INVALID")
                    if ns_idx == 0xffffffff and pool[key_idx] == "package":
                        typed_kind = data[pos + 15]
                        typed_idx = u32(data, pos + 16)
                        idx = raw_idx if raw_idx != 0xffffffff else (
                            typed_idx if typed_kind == 3 else None)
                        require(idx is not None and idx < len(pool) and
                                pool[idx] == OLD_PACKAGE,
                                "PACKAGE_ONLY_ROOT_ATTR_REFERENCE_INVALID")
                        if raw_idx != 0xffffffff and typed_kind == 3:
                            # Exact lexical and typed value must resolve to ONE
                            # index; never change two strings by assumption.
                            require(typed_idx == raw_idx,
                                    "PACKAGE_ONLY_DUAL_STRING_REFERENCE")
                        selected.append(idx)
        off += size
    require(off == len(data) and pool is not None and
            len(selected) == 1 and pool_location is not None,
            "PACKAGE_ONLY_ROOT_ATTR_NOT_UNIQUE")
    pool_off, hdr, size, flags = pool_location
    idx = selected[0]
    pos = pool_off + u32(data, pool_off + 20) + u32(data, pool_off + hdr + 4 * idx)
    require(pool_off <= pos < pool_off + size,
            "PACKAGE_ONLY_STRING_RANGE_INVALID")
    buf = bytearray(data)
    if flags & 0x100:
        _, pos = variable_utf8_length(data, pos)
        length, pos = variable_utf8_length(data, pos)
        require(pos + length + 1 <= pool_off + size and data[pos + length] == 0,
                "PACKAGE_ONLY_UTF8_INVALID")
        require(data[pos:pos + length] == OLD_PACKAGE.encode("utf-8") and
                length == len(NEW_PACKAGE.encode("utf-8")),
                "PACKAGE_ONLY_UTF8_LENGTH_CHANGED")
        buf[pos:pos + length] = NEW_PACKAGE.encode("utf-8")
    else:
        length, pos = variable_utf16_length(data, pos)
        require(pos + length * 2 + 2 <= pool_off + size and
                u16(data, pos + length * 2) == 0,
                "PACKAGE_ONLY_UTF16_INVALID")
        replacement = NEW_PACKAGE.encode("utf-16-le")
        require(data[pos:pos + length * 2] == OLD_PACKAGE.encode("utf-16-le")
                and len(replacement) == length * 2,
                "PACKAGE_ONLY_UTF16_LENGTH_CHANGED")
        buf[pos:pos + length * 2] = replacement
    after = parse_binary_xml(bytes(buf))
    expected = json.loads(json.dumps(before))
    root = next(e for e in expected["elements"] if e["tag"] == "manifest")
    root["attributes"]["package"] = NEW_PACKAGE
    require(after == expected, "PACKAGE_ONLY_MORE_THAN_PACKAGE_CHANGED")
    return bytes(buf)

SRC = "classic-loft-dj-IDENTITY-UNSIGNED.apk"
IDENTITY_SIGNED = "classic-loft-dj-IDENTITY-TEST-SIGNED.apk"
DST = "classic-loft-dj-PACKAGE-UNSIGNED.apk"

def scope(source, output):
    env = os.environ
    root = env.get("RUNNER_TEMP", "")
    require(bool(root) and env.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj"
            and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_ACTOR") == "lxsdd"
            and env.get("GITHUB_REF") == "refs/heads/main"
            and env.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and not env.get("GH_TOKEN") and not env.get("CLASSIC_VAULT_TOKEN"),
            "PACKAGE_ONLY_OWNER_OFFLINE_SCOPE_REQUIRED")
    folder = Path(root) / "private-output"
    require(source == folder / SRC and output == folder / DST
            and source.is_file() and not source.is_symlink()
            and not output.exists(), "PACKAGE_ONLY_PRIVATE_PATH_INVALID")
    preflight = source.with_suffix(source.suffix + ".preflight.json")
    receipt = folder / (IDENTITY_SIGNED + ".test-signing.json")
    require(all(p.is_file() and not p.is_symlink() and p.stat().st_size < 50000
                for p in (preflight, receipt)), "PACKAGE_ONLY_IDENTITY_RECEIPTS_MISSING")
    a = json.loads(preflight.read_text("utf-8"))
    b = json.loads(receipt.read_text("utf-8"))
    require(a.get("status") == "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED"
            and a.get("package") == OLD_PACKAGE
            and a.get("identity_control_offline_only") is True
            and a.get("dex_reassembly_mode") == "raw"
            and a.get("original_oem_dex_intentionally_changed") is False
            and all(v == 0 for v in a.get("controlled_authority_string_remaps", {}).values())
            and a.get("apk_sha256") == digest_file(source)
            and b.get("identity_control_offline_only") is True
            and b.get("package") == OLD_PACKAGE and b.get("signed") is True
            and b.get("unsigned_sha256") == digest_file(source)
            and b.get("source_build_report_sha256") == digest_file(preflight),
            "PACKAGE_ONLY_IDENTITY_INPUT_NOT_QUALIFIED")
    return a

def build(source, output):
    report = scope(source, output)
    temp = output.with_name("." + output.name + ".partial")
    require(not temp.exists(), "PACKAGE_ONLY_TEMP_EXISTS")
    try:
        with zipfile.ZipFile(source) as incoming, zipfile.ZipFile(temp, "w") as outgoing:
            names = incoming.namelist()
            require(len(names) == len(set(names)) and "AndroidManifest.xml" in names
                    and {"classes.dex", "classes2.dex", "classes3.dex"}.issubset(names)
                    and sum(i.file_size for i in incoming.infolist()) < 500 * 1024 * 1024,
                    "PACKAGE_ONLY_INPUT_ZIP_INVALID")
            old_manifest = incoming.read("AndroidManifest.xml")
            new_manifest = replace_package_only(old_manifest)
            for info in incoming.infolist():
                payload = (new_manifest if info.filename == "AndroidManifest.xml"
                           else incoming.read(info.filename))
                outgoing.writestr(info, payload)
        with zipfile.ZipFile(source) as a, zipfile.ZipFile(temp) as b:
            require(a.namelist() == b.namelist(), "PACKAGE_ONLY_ZIP_ENTRIES_CHANGED")
            for name in a.namelist():
                if name == "AndroidManifest.xml":
                    require(b.read(name) == replace_package_only(a.read(name)),
                            "PACKAGE_ONLY_MANIFEST_MISMATCH")
                else:
                    require(a.read(name) == b.read(name),
                            "PACKAGE_ONLY_NON_MANIFEST_BYTES_CHANGED")
                if re.fullmatch(r"classes(?:2|3)?\.dex", name):
                    require(a.getinfo(name).compress_type == zipfile.ZIP_STORED
                            and b.getinfo(name).compress_type == zipfile.ZIP_STORED,
                            "PACKAGE_ONLY_DEX_TRANSPORT_CHANGED")
        os.replace(temp, output)
        result = {
            "status": "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED",
            "package": NEW_PACKAGE,
            "identity_control_offline_only": False,
            "package_only_control_offline_only": True,
            "dex_reassembly_mode": "raw",
            "original_oem_dex_intentionally_changed": False,
            "controlled_authority_string_remaps":
                report["controlled_authority_string_remaps"],
            "apk_sha256": digest_file(output),
            "signed": False, "installed": False,
            "runtime": "NOT_TESTED", "physical_device_tested": False,
            "only_binary_manifest_package_changed": True,
        }
        output.with_suffix(output.suffix + ".preflight.json").write_text(
            json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
        return {"status": "PRIVATE_PACKAGE_ONLY_MANIFEST_ABLATION_PREPARED",
                "only_binary_manifest_package_changed": True,
                "runtime": "NOT_TESTED", "raw_private_payload_published": False}
    finally:
        if temp.exists():
            temp.unlink()

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    try:
        print(json.dumps(build(a.source, a.output), sort_keys=True))
        return 0
    except PackageOnlyBlocked as exc:
        # Only hardcoded public source codes, never OEM values or exception
        # messages from arbitrary dependencies.
        reasons = frozenset({
            "PACKAGE_ONLY_AXML_SIZE_INVALID",
            "PACKAGE_ONLY_ORIGINAL_PACKAGE_INVALID",
            "PACKAGE_ONLY_LENGTH_MISMATCH",
            "PACKAGE_ONLY_XML_CHUNK_INVALID",
            "PACKAGE_ONLY_POOL_INVALID",
            "PACKAGE_ONLY_POOL_RANGE_INVALID",
            "PACKAGE_ONLY_XML_ELEMENT_INVALID",
            "PACKAGE_ONLY_XML_ATTR_INVALID",
            "PACKAGE_ONLY_ROOT_ATTR_REFERENCE_INVALID",
            "PACKAGE_ONLY_DUAL_STRING_REFERENCE",
            "PACKAGE_ONLY_ROOT_ATTR_NOT_UNIQUE",
            "PACKAGE_ONLY_STRING_RANGE_INVALID",
            "PACKAGE_ONLY_UTF8_INVALID",
            "PACKAGE_ONLY_UTF16_INVALID",
            "PACKAGE_ONLY_UTF8_LENGTH_CHANGED",
            "PACKAGE_ONLY_UTF16_LENGTH_CHANGED",
            "PACKAGE_ONLY_MORE_THAN_PACKAGE_CHANGED",
            "PACKAGE_ONLY_OWNER_OFFLINE_SCOPE_REQUIRED",
            "PACKAGE_ONLY_PRIVATE_PATH_INVALID",
            "PACKAGE_ONLY_IDENTITY_RECEIPTS_MISSING",
            "PACKAGE_ONLY_IDENTITY_INPUT_NOT_QUALIFIED",
            "PACKAGE_ONLY_TEMP_EXISTS",
            "PACKAGE_ONLY_INPUT_ZIP_INVALID",
            "PACKAGE_ONLY_ZIP_ENTRIES_CHANGED",
            "PACKAGE_ONLY_MANIFEST_MISMATCH",
            "PACKAGE_ONLY_NON_MANIFEST_BYTES_CHANGED",
            "PACKAGE_ONLY_DEX_TRANSPORT_CHANGED",
        })
        code = str(exc)
        print("PRIVATE_PACKAGE_ONLY_CONTROL_BLOCKED_" +
              (code if code in reasons else "UNCLASSIFIED"),
              file=sys.stderr)
        return 2
    except (AxmlError, OSError, KeyError, ValueError, UnicodeError,
            zipfile.BadZipFile):
        print("PRIVATE_PACKAGE_ONLY_CONTROL_BLOCKED_INPUT_OR_AXML_INVALID",
              file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
