#!/usr/bin/env python3
"""Private Classic-first APK integration preflight and decoded-tree patch preparation.

Never signs, installs, uploads or touches devices. All generated app code is original.
Requires independently decoded proprietary input, anchored to pinned original APK digest.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ORIGINAL_SHA256 = "abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2"
from dex_authority_remap import AUTHORITIES, OLD as OLD_PACKAGE, NEW as NEW_PACKAGE, remap_dex, assert_self_consistent_dex
BRIDGE_NAMESPACE = "io.github.lxsdd.yeelightclassicdj"
BRIDGE_ACTIVITY = BRIDGE_NAMESPACE + ".ui.ClassicDjActivity"
BRIDGE_SERVICE = BRIDGE_NAMESPACE + ".ui.ClassicDjService"
BRIDGE_BUTTON = BRIDGE_NAMESPACE + ".ui.ClassicDjEntryView"
ANDROID = "http://schemas.android.com/apk/res/android"
A = lambda name: "{" + ANDROID + "}" + name
ET.register_namespace("android", ANDROID)

class GateFailure(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise GateFailure(message)


def digest_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def inspect_original(path: Path) -> dict:
    require(path.is_file() and not path.is_symlink(), "ORIGINAL_APK_NOT_REGULAR_FILE")
    digest = digest_file(path)
    require(digest == ORIGINAL_SHA256, "ORIGINAL_APK_SHA256_MISMATCH")
    with zipfile.ZipFile(path) as z:
        entries = z.infolist()
        names = [x.filename for x in entries]
        require(len(names) == len(set(names)), "ORIGINAL_DUPLICATE_ZIP_ENTRY")
        require(len(names) <= 10000, "ORIGINAL_UNEXPECTED_ENTRY_COUNT")
        require(all(not name.startswith(("/", "\\")) and ".." not in Path(name).parts and "\\" not in name for name in names), "ORIGINAL_BAD_ZIP_ENTRY_PATH")
        require(all(not (x.flag_bits & 1) and (x.external_attr >> 16) & stat.S_IFMT(0o170000) != stat.S_IFLNK for x in entries), "ORIGINAL_ZIP_ENCRYPTED_OR_SYMLINK")
        require(sum(x.file_size for x in entries) < 500 * 1024 * 1024, "ORIGINAL_ZIP_TOO_LARGE")
        require("AndroidManifest.xml" in names and "classes.dex" in names and "classes2.dex" in names, "ORIGINAL_APK_MISSING_CORE")
        require("assets/YeelightColor4.xml" in names, "ORIGINAL_COLOR4_PROFILE_MISSING")
    return {"original_sha256": digest, "zip_entries": len(names), "dex_count": len([x for x in names if re.fullmatch(r"classes\d*\.dex", x)]), "original_read_only": True}


def clean_component_name(value: str) -> str:
    if value.startswith("."):
        return OLD_PACKAGE + value
    if "." not in value:
        return OLD_PACKAGE + "." + value
    return value


def check_source_manifest(path: Path) -> None:
    require(path.is_file() and not path.is_symlink(), "DECODED_MANIFEST_NOT_FOUND")
    root = ET.parse(path).getroot()
    require(root.tag == "manifest" and root.get("package") == OLD_PACKAGE, "DECODED_PACKAGE_NOT_ORIGINAL")
    require(root.find("application") is not None, "ORIGINAL_APPLICATION_MISSING")


def patch_manifest(path: Path) -> dict:
    check_source_manifest(path)
    tree = ET.parse(path)
    root = tree.getroot()
    app = root.find("application")
    require(not root.get(A("sharedUserId")), "SHARED_USER_ID_NOT_ISOLATABLE")
    # Component class names must still point at ORIGINAL dex classes after changing app id.
    count = 0
    for elem in list(root) + list(app):
        attrs = []
        if elem is app or elem.tag in ("activity", "activity-alias", "service", "receiver", "provider", "instrumentation"):
            attrs.append("name")
        if elem is app:
            attrs.extend(["appComponentFactory", "backupAgent", "manageSpaceActivity", "zygotePreloadName"])
        if elem.tag == "activity-alias":
            attrs.append("targetActivity")
        if elem.tag == "activity":
            attrs.append("parentActivityName")
        for key in attrs:
            value = elem.get(A(key))
            if value and not value.startswith("android."):
                corrected = clean_component_name(value)
                if corrected != value:
                    elem.set(A(key), corrected)
                    count += 1
    # Real Pixel 6a FATAL EXCEPTION (2026-11-07): FirebaseInitProvider
    # invokes Crashlytics before any Activity. Its DataTransport/Dagger
    # dependency cannot be resolved in the original OEM runtime. For this
    # PRIVATE LAN-first clone only, disable exactly that early-init provider.
    # No OEM bytecode, alternative analytics, cloud credentials or lamp writes.
    firebase = [p for p in app.findall("provider")
                if p.get(A("name")) == "com.google.firebase.provider.FirebaseInitProvider"]
    require(len(firebase) == 1, "FIREBASE_INIT_PROVIDER_NOT_UNIQUE")
    require(firebase[0].get(A("authorities")) == OLD_PACKAGE + ".firebaseinitprovider",
            "FIREBASE_INIT_AUTHORITY_MISMATCH")
    require(firebase[0].get(A("exported"), "false") == "false",
            "FIREBASE_INIT_UNEXPECTED_EXPORT")
    app.remove(firebase[0])
    # Pixel 6a owner traceback after Firebase fix (private run 42421751285):
    # Android cannot instantiate the OEM DeviceInteractionProvider because its
    # declared class is missing at runtime. This old provider is explicitly
    # excluded ONLY in our independent private clone. Other providers remain.
    # Never use a fabricated provider class or rewrite the OEM DEX.
    offline_only = [p for p in app.findall("provider")
                    if p.get(A("name")) == "com.yeelight.yeelib.data.DeviceInteractionProvider"]
    require(len(offline_only) == 1, "DEVICE_PROVIDER_NOT_UNIQUE")
    old_authorities = offline_only[0].get(A("authorities"), "").split(";")
    require(old_authorities and all(a in AUTHORITIES and
            a != OLD_PACKAGE + ".firebaseinitprovider" for a in old_authorities),
            "DEVICE_PROVIDER_AUTHORITY_UNKNOWN")
    require(offline_only[0].get(A("exported"), "false") == "false",
            "DEVICE_PROVIDER_UNEXPECTED_EXPORT")
    app.remove(offline_only[0])
    # Keep original code package classes; only namespace providers and self-defined permissions.
    providers = []
    for provider in app.findall("provider"):
        original = provider.get(A("authorities"))
        require(original and "${" not in original, "PROVIDER_AUTHORITY_UNRESOLVED")
        pieces = original.split(";")
        require(all(p in AUTHORITIES for p in pieces), "PROVIDER_AUTHORITY_NOT_OWNED_FAIL_CLOSED")
        newer = ";".join(NEW_PACKAGE + p[len(OLD_PACKAGE):] for p in pieces)
        provider.set(A("authorities"), newer)
        providers.append((original, newer))
    # Signature permissions defined by the old app must move with their owner.
    # System permissions and unrelated external integration contracts stay untouched.
    for elem in root.iter():
        for key in ("permission", "readPermission", "writePermission"):
            old = elem.get(A(key))
            if old and (old == OLD_PACKAGE or old.startswith(OLD_PACKAGE + ".")):
                elem.set(A(key), NEW_PACKAGE + old[len(OLD_PACKAGE):])
    permissions = []
    for elem in root:
        if elem.tag in ("permission", "permission-group", "permission-tree", "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            value = elem.get(A("name"))
            if value and (value == OLD_PACKAGE or value.startswith(OLD_PACKAGE + ".")):
                elem.set(A("name"), NEW_PACKAGE + value[len(OLD_PACKAGE):])
                permissions.append(value)
    root.set("package", NEW_PACKAGE)
    # Independent installation: API23+ for nonblocking AudioRecord and sidecar dex without legacy multidex bootstrap.
    sdk = root.find("uses-sdk")
    if sdk is None:
        sdk = ET.Element("uses-sdk")
        root.insert(0, sdk)
    min_sdk = sdk.get(A("minSdkVersion"), "1")
    require(min_sdk.isdecimal(), "UNRESOLVED_MIN_SDK")
    if int(min_sdk) < 23:
        sdk.set(A("minSdkVersion"), "23")
    for perm in ("android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE", "android.permission.POST_NOTIFICATIONS"):
        if not any(e.tag.startswith("uses-permission") and e.get(A("name")) == perm for e in root):
            ET.SubElement(root, "uses-permission", {A("name"): perm})
    require(not any(e.tag == "activity" and e.get(A("name")) == BRIDGE_ACTIVITY for e in app), "BRIDGE_ALREADY_PRESENT")
    ET.SubElement(app, "activity", {
        A("name"): BRIDGE_ACTIVITY,
        A("exported"): "false",
        A("label"): "Loft DJ",
        A("theme"): "@android:style/Theme.Material.NoActionBar",
    })
    ET.SubElement(app, "service", {
        A("name"): BRIDGE_SERVICE,
        A("exported"): "false",
        A("foregroundServiceType"): "microphone",
    })
    # This component is declared, but only the existing checkpoint runtime may implement it.
    # No exported services, cloud API, or remote actor permissions are added.
    tree.write(path, encoding="utf-8", xml_declaration=True)
    return {"new_package": NEW_PACKAGE, "canonicalized_component_refs": count, "providers": providers, "permissions_renamed": permissions, "min_sdk": 23, "activity_exported": False,
            "firebase_auto_init": "DISABLED_FOR_PRIVATE_CLONE",
            "cloud_login": "NOT_TESTED",
            "legacy_device_interaction_provider": "DISABLED_PRIVATE_CLONE_ONLY"}


def patch_layout(path: Path) -> None:
    """Add the DJ entry without changing Classic's verified root ViewGroup.

    The pinned OEM main layout is a LinearLayout. A FrameLayout wrapper can
    break OEM code that retrieves or casts the actual inflated root. Preserve
    original root attributes and every pre-existing child index instead.
    """
    require(path.is_file() and not path.is_symlink(), "CLASSIC_MAIN_LAYOUT_MISSING")
    tree = ET.parse(path)
    root = tree.getroot()
    require(root.tag == "LinearLayout", "CLASSIC_LAYOUT_ROOT_CHANGED_FAIL_CLOSED")
    require(not any(node.tag == BRIDGE_BUTTON or node.get(A("id")) == "@+id/loft_dj_button"
                    for node in root.iter()), "LAYOUT_ALREADY_PATCHED")
    # Appending retains the original five children in their exact positions.
    ET.SubElement(root, BRIDGE_BUTTON, {
        A("id"): "@+id/loft_dj_button",
        A("layout_width"): "wrap_content",
        A("layout_height"): "wrap_content",
        A("layout_gravity"): "end",
        A("contentDescription"): "Loft DJ öffnen",
    })
    tree.write(path, encoding="utf-8", xml_declaration=True)


def check_decoded_safety(directory: Path) -> None:
    require(directory.is_dir() and not directory.is_symlink(), "DECODED_DIRECTORY_NOT_FOUND")
    for path in directory.rglob("*"):
        require(not path.is_symlink(), "DECODED_TREE_CONTAINS_SYMLINK")
        if path.is_file():
            require(path.stat().st_size < 120 * 1024 * 1024, "DECODED_FILE_TOO_LARGE")


def verify_decoded_origin(original: Path, decoded: Path) -> None:
    # A same-named decoded directory is not enough: pin original executable dex + profile.
    # Scripted real builds decode this hash-verified original with apktool -s.
    with zipfile.ZipFile(original) as archive:
        for info in archive.infolist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", info.filename) or info.filename == "assets/YeelightColor4.xml":
                local = decoded / info.filename
                require(local.is_file() and not local.is_symlink(), "DECODED_ORIGINAL_FILE_MISSING")
                require(hashlib.sha256(local.read_bytes()).digest() == hashlib.sha256(archive.read(info.filename)).digest(), "DECODED_ORIGINAL_BYTES_MISMATCH")


def prepare(original: Path, decoded: Path, dex: Path, output: Path) -> dict:
    audit = inspect_original(original)
    check_decoded_safety(decoded)
    verify_decoded_origin(original, decoded)
    check_source_manifest(decoded / "AndroidManifest.xml")
    require(dex.is_file() and dex.stat().st_size > 110 and dex.read_bytes()[:8] in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0"), "BRIDGE_DEX_INVALID")
    bridge = dex.read_bytes()
    for klass in (b"Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjActivity;", b"Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjEntryView;", b"Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjService;", b"Lio/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime;"):
        require(klass in bridge, "BRIDGE_DEX_MISSING_REQUIRED_CLASS")
    require(not output.exists(), "OUTPUT_ALREADY_EXISTS")
    require(output.parent.is_dir(), "OUTPUT_PARENT_NOT_FOUND")
    # No output is installed, signed, uploaded, or used against a device.
    with tempfile.TemporaryDirectory(prefix="classic-fork-private-", dir=output.parent) as work:
        tmp = Path(work) / "decoded"
        shutil.copytree(decoded, tmp)
        manifest_info = patch_manifest(tmp / "AndroidManifest.xml")
        layouts = [p for p in (tmp / "res").glob("layout*/activity_main_new.xml") if p.is_file()]
        require(bool(layouts), "MAIN_NAVIGATION_LAYOUT_NOT_FOUND")
        for layout in layouts:
            patch_layout(layout)
        old_dex = list(tmp.glob("classes*.dex"))
        require(bool(old_dex), "DECODED_HAS_NO_DEX")
        index = 2
        while (tmp / f"classes{index}.dex").exists():
            index += 1
        # Manifest migration alone breaks OEM URI constants. Remap *only* the
        # seven already proven provider authorities in copied original DEX.
        remap_audit = {}
        for original_path in old_dex:
            name = original_path.name
            source = original_path.read_bytes()
            if source.startswith(b"dex\n"):
                changed, info = remap_dex(source)
                assert_self_consistent_dex(changed)
                original_path.write_bytes(changed)
                remap_audit[name] = info["changed_string_count"]
            else:
                # Synthetic test fixtures never enter a legitimate pinned APK build.
                require(original.stat().st_size < 4096, "NON_DEX_ORIGINAL_DENIED")
        dest = tmp / f"classes{index}.dex"
        shutil.copyfile(dex, dest)
        require(digest_file(dest) == digest_file(dex), "BRIDGE_COPY_MISMATCH")
        output_report = {
            "status": "DECODED_CLONE_PREPARED_NOT_COMPILED_OR_HARDWARE_TESTED",
            "original": audit,
            "manifest": manifest_info,
            "layout_variants_patched": [str(x.relative_to(tmp)) for x in layouts],
            "sidecar_dex": dest.name,
            "oem_authority_remap": remap_audit,
            "original_oem_dex_intentionally_changed": any(remap_audit.values()),
            "sidecar_sha256": digest_file(dest),
            "post_build_checks_required": ["apktool-build", "package-name", "signature-separation", "main-navigation", "Classic-runtime", "Cloud-login", "device-hardware"],
            "signed": False, "installed": False, "device_commands": 0,
        }
        (tmp / "classic_first_preparation.json").write_text(json.dumps(output_report, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, output)
        return output_report


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    i = sub.add_parser("inspect", help="Read-only identity check of the original Classic APK")
    i.add_argument("--original", required=True, type=Path)
    b = sub.add_parser("prepare", help="Private decoded-tree rewrite only; never signs or installs")
    for name in ("original", "decoded", "bridge-dex", "output"):
        b.add_argument("--" + name, required=True, type=Path)
    args = p.parse_args(argv)
    try:
        if args.cmd == "inspect":
            result = inspect_original(args.original)
        else:
            result = prepare(args.original, args.decoded, args.bridge_dex, args.output)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    except (GateFailure, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(json.dumps({"status":"BLOCKED", "reason": str(error), "signed": False, "installed": False}), file=sys.stderr)
        return 2

if __name__ == "__main__":
    sys.exit(main())
