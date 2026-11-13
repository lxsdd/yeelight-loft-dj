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
    """Repoint only the root package attribute to a new AXML pool entry.

    Original pool strings can be shared with other manifest attributes and XML
    nodes. No old pool bytes or other string references are changed. The new
    string is appended and only the root package's raw/typed indices move.
    """
    require(type(data) is bytes and 8 <= len(data) <= 8 * 1024 * 1024,
            "PACKAGE_ONLY_AXML_SIZE_INVALID")
    before = parse_binary_xml(data)
    roots = [e for e in before["elements"] if e["tag"] == "manifest"]
    require(len(roots) == 1 and roots[0]["attributes"].get("package") == OLD_PACKAGE,
            "PACKAGE_ONLY_ORIGINAL_PACKAGE_INVALID")
    require(OLD_PACKAGE.isascii() and NEW_PACKAGE.isascii()
            and len(OLD_PACKAGE) < 128 and len(NEW_PACKAGE) < 128,
            "PACKAGE_ONLY_LENGTH_MISMATCH")
    off, pool_info, pool = 8, None, None
    package_attribute = []
    while off < len(data):
        typ, hdr, size = u16(data, off), u16(data, off + 2), u32(data, off + 4)
        require(hdr >= 8 and size >= hdr and off + size <= len(data),
                "PACKAGE_ONLY_XML_CHUNK_INVALID")
        if typ == 1:
            require(pool_info is None and hdr >= 28, "PACKAGE_ONLY_POOL_INVALID")
            count, styles = u32(data, off + 8), u32(data, off + 12)
            flags, string_start, style_start = (
                u32(data, off + 16), u32(data, off + 20), u32(data, off + 24))
            require(count > 0 and count < 200000 and styles < 100000
                    and hdr + (count + styles) * 4 <= string_start <= size
                    and (styles == 0 or style_start >= string_start)
                    and (styles != 0 or style_start == 0)
                    and (style_start == 0 or style_start <= size)
                    and string_start % 4 == 0
                    and (style_start == 0 or style_start % 4 == 0),
                    "PACKAGE_ONLY_STYLE_POOL_INVALID")
            pool = string_pool(data, off, off + size)
            pool_info = (off, hdr, size, count, styles, flags,
                         string_start, style_start)
        elif typ == 0x0102:
            require(pool is not None and hdr >= 16, "PACKAGE_ONLY_XML_ELEMENT_INVALID")
            name_idx = u32(data, off + 20)
            require(name_idx < len(pool), "PACKAGE_ONLY_XML_ELEMENT_INVALID")
            if pool[name_idx] == "manifest":
                attr_start, attr_width, attr_count = (
                    u16(data, off + 24), u16(data, off + 26), u16(data, off + 28))
                require(attr_width >= 20 and attr_count <= 1000,
                        "PACKAGE_ONLY_XML_ATTR_INVALID")
                attrs = off + 16 + attr_start
                require(attrs + attr_width * attr_count <= off + size,
                        "PACKAGE_ONLY_XML_ATTR_INVALID")
                for i in range(attr_count):
                    pos = attrs + attr_width * i
                    ns, name, raw = (u32(data, pos),
                                     u32(data, pos + 4),
                                     u32(data, pos + 8))
                    require(name < len(pool), "PACKAGE_ONLY_XML_ATTR_INVALID")
                    if ns == 0xffffffff and pool[name] == "package":
                        kind, typed = data[pos + 15], u32(data, pos + 16)
                        # The two Android representations may legitimately
                        # use different indices for the same literal.
                        if raw != 0xffffffff:
                            require(raw < len(pool) and pool[raw] == OLD_PACKAGE,
                                    "PACKAGE_ONLY_ROOT_ATTR_REFERENCE_INVALID")
                        if kind == 3:
                            require(typed < len(pool) and pool[typed] == OLD_PACKAGE,
                                    "PACKAGE_ONLY_DUAL_STRING_REFERENCE")
                        require(raw != 0xffffffff or kind == 3,
                                "PACKAGE_ONLY_ROOT_ATTR_REFERENCE_INVALID")
                        package_attribute.append((pos, raw != 0xffffffff, kind == 3))
        off += size
    require(off == len(data) and pool_info is not None and
            len(package_attribute) == 1,
            "PACKAGE_ONLY_ROOT_ATTR_NOT_UNIQUE")
    pool_off, hdr, pool_size, count, styles, flags, string_start, style_start = pool_info
    require(pool_off + pool_size <= package_attribute[0][0],
            "PACKAGE_ONLY_XML_CHUNK_INVALID")

    # Preserve every old string and its index. Inserting an offset table slot
    # shifts the string bytes by 4, so only the pool header and AXML length
    # fields are updated. Resource map and all old reference indices stay.
    old = data[pool_off:pool_off + pool_size]
    offset_table_end = hdr + (count + styles) * 4
    old_string_area = old[string_start:style_start or pool_size]
    old_style_area = old[style_start:] if style_start else b""
    if flags & 0x100:
        value = NEW_PACKAGE.encode("utf-8")
        encoded = bytes((len(NEW_PACKAGE), len(value))) + value + b"\x00"
    else:
        encoded = (struct.pack("<H", len(NEW_PACKAGE)) +
                   NEW_PACKAGE.encode("utf-16-le") + b"\x00\x00")
    pad = b"\x00" * (-(len(old_string_area) + len(encoded)) % 4)
    header = bytearray(old[:hdr])
    struct.pack_into("<I", header, 8, count + 1)
    struct.pack_into("<I", header, 20, string_start + 4)
    if style_start:
        struct.pack_into("<I", header, 24, style_start + 4 + len(encoded) + len(pad))
    # A sorted-string-pool flag must not be retained after appending a new
    # literal whose lexical position is not established.
    struct.pack_into("<I", header, 16, flags & ~1)
    old_offsets = old[hdr:hdr + count * 4]
    style_offsets = old[hdr + count * 4:offset_table_end]
    gap = old[offset_table_end:string_start]
    new_pool = (header + old_offsets + struct.pack("<I", len(old_string_area)) +
                style_offsets + gap + old_string_area + encoded + pad +
                old_style_area)
    require(len(new_pool) % 4 == 0 and len(new_pool) > pool_size,
            "PACKAGE_ONLY_STRING_POOL_REPACK_INVALID")
    struct.pack_into("<I", new_pool, 4, len(new_pool))
    delta = len(new_pool) - pool_size
    buf = bytearray(data[:pool_off]) + new_pool + data[pool_off + pool_size:]
    require(len(buf) == len(data) + delta and len(buf) <= 8 * 1024 * 1024,
            "PACKAGE_ONLY_STRING_POOL_REPACK_INVALID")
    struct.pack_into("<I", buf, 4, len(buf))
    attr_pos, change_raw, change_typed = package_attribute[0]
    attr_pos += delta
    if change_raw:
        struct.pack_into("<I", buf, attr_pos + 8, count)
    if change_typed:
        struct.pack_into("<I", buf, attr_pos + 16, count)
    after = parse_binary_xml(bytes(buf))
    expected = json.loads(json.dumps(before))
    root = next(e for e in expected["elements"] if e["tag"] == "manifest")
    root["attributes"]["package"] = NEW_PACKAGE
    expected["string_count"] += 1
    require(after == expected, "PACKAGE_ONLY_MORE_THAN_PACKAGE_CHANGED")
    # An existing reference to the old package remains in the original string
    # pool; any other attr touching it is semantically unchanged.
    require(pool[count - 1] == string_pool(data, pool_off, pool_off + pool_size)[-1],
            "PACKAGE_ONLY_OLD_POOL_MUTATED")
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
            "PACKAGE_ONLY_STYLE_POOL_INVALID",
            "PACKAGE_ONLY_STRING_POOL_REPACK_INVALID",
            "PACKAGE_ONLY_OLD_POOL_MUTATED",
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
