#!/usr/bin/env python3
"""End-to-end PRIVATE, UNSIGNED Classic clone build. Never signs, installs or sends device commands.

Preconditions: exact owner-provided original APK, Android SDK android.jar / d8 / aapt,
`apktool` installed locally. UI/runtime must pass real device acceptance separately.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from classic_first import GateFailure, NEW_PACKAGE, digest_file, inspect_original, prepare
from dex_authority_remap import remap_dex, DexRemapError, assert_self_consistent_dex
from bridge.build_bridge import build as build_sidecar, ToolError, find_sdk_tools, no_oem_class_collisions, dex_descriptors
from apk_readonly_audit import parse_binary_xml, AxmlError

ROOT = Path(__file__).resolve().parent


def must_be_tool(name):
    path = shutil.which(name)
    if not path:
        raise GateFailure(f"TOOL_MISSING_{name.upper()}")
    return path


def run(args, env=None):
    subprocess.run([str(x) for x in args], check=True, timeout=240, env=env)


def verify_unsigned(candidate: Path, original: Path, bridge_dex: Path, aapt: str) -> dict:
    with zipfile.ZipFile(original) as orig, zipfile.ZipFile(candidate) as out:
        initial = {info.filename for info in orig.infolist()}
        generated = {info.filename for info in out.infolist()}
        original_dex = sorted(x for x in initial if re.fullmatch(r'classes(?:\d+)?\.dex', x))
        output_dex = sorted(x for x in generated if re.fullmatch(r'classes(?:\d+)?\.dex', x))
        expected_remaps = {}
        for name in original_dex:
            source = orig.read(name)
            if source.startswith(b"dex\n"):
                expected, detail = remap_dex(source)
                assert_self_consistent_dex(expected)
                expected_remaps[name] = detail["changed_string_count"]
            else:
                # Test-only synthetic fixtures; original build() always SHA-pins real APK.
                expected = source
                expected_remaps[name] = 0
            if name not in generated or hashlib.sha256(expected).digest() != hashlib.sha256(out.read(name)).digest():
                raise GateFailure(f"OEM_DEX_TRANSFORM_MISMATCH_{name}")
        addon = list(set(output_dex) - set(original_dex))
        if len(addon) != 1 or hashlib.sha256(out.read(addon[0])).hexdigest() != digest_file(bridge_dex):
            raise GateFailure("SIDECAR_DEX_MISMATCH")
        # Prevent accidental re-shipping obsolete JAR certificate material.
        if any(re.fullmatch(r'META-INF/[^/]+\.(?:RSA|DSA|EC|SF)', n, flags=re.I) for n in generated):
            raise GateFailure("STALE_SIGNATURE_FILES_PRESENT")
    if b'APK Sig Block 42' in candidate.read_bytes()[-16384:]:
        raise GateFailure("SIGNED_OUTPUT_NOT_PERMITTED")
    badging = subprocess.run([aapt, 'dump', 'badging', str(candidate)], capture_output=True, text=True, timeout=30)
    if badging.returncode != 0:
        raise GateFailure("AAPT_BADGING_FAILED")
    m = re.search(r"^package: name='([^']+)'", badging.stdout, re.M)
    if not m or m.group(1) != NEW_PACKAGE:
        raise GateFailure("NEW_PACKAGE_NOT_IN_FINAL_APK")
    return {
        "status": "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED",
        "apk_sha256": digest_file(candidate),
        "oem_dex_verified": original_dex,
        "controlled_authority_string_remaps": expected_remaps,
        "original_oem_dex_intentionally_changed": any(expected_remaps.values()),
        "sidecar_dex": addon[0],
        "package": NEW_PACKAGE,
        "signed": False, "installed": False, "device_hardware": "NOT_TESTED",
        "classic_ui": "NOT_TESTED", "cloud_login": "NOT_TESTED",
        "dj_checkpoint_adapter": "REQUIRES_PRIVATE_RUNTIME_CLASS",
    }


def verify_final_firebase_boundary(candidate: Path) -> dict:
    """The rebuilt private APK must not accidentally re-enable OEM Firebase auto-init."""
    try:
        with zipfile.ZipFile(candidate) as z:
            root = parse_binary_xml(z.read("AndroidManifest.xml"))
    except (AxmlError, KeyError, zipfile.BadZipFile) as exc:
        raise GateFailure("FINAL_BINARY_MANIFEST_INVALID") from exc
    providers = [e["attributes"] for e in root["elements"] if e["tag"] == "provider"]
    if len(providers) != 5:
        raise GateFailure("FINAL_PROVIDER_COUNT_NOT_FIVE")
    for p in providers:
        if p.get("{http://schemas.android.com/apk/res/android}name") == \
                "com.google.firebase.provider.FirebaseInitProvider":
            raise GateFailure("FINAL_FIREBASE_INIT_REINTRODUCED")
        if p.get("{http://schemas.android.com/apk/res/android}authorities", "").endswith(".firebaseinitprovider"):
            raise GateFailure("FINAL_FIREBASE_AUTHORITY_REINTRODUCED")
        if p.get("{http://schemas.android.com/apk/res/android}name") == \
                "com.yeelight.yeelib.data.DeviceInteractionProvider":
            raise GateFailure("FINAL_MISSING_DEVICE_PROVIDER_REINTRODUCED")
    return {"status": "PRIVATE_EARLY_STARTUP_PROVIDERS_RECONCILED", "remaining_oem_providers": 5,
            "android_startup": "NOT_TESTED", "cloud_login": "NOT_TESTED"}


def verify_final_component_classes(candidate: Path) -> dict:
    """Static preflight: every retained ContentProvider AND Application must resolve.

    Android installs ContentProviders before Activities. Checking only their
    authorities or DEX file hashes missed a real ClassNotFoundException.
    Fail this PRIVATE build rather than sign another APK with an absent class.
    No OEM code or identities are logged; output is only count/status.
    """
    android_name = "{http://schemas.android.com/apk/res/android}name"
    try:
        with zipfile.ZipFile(candidate) as archive:
            manifest = parse_binary_xml(archive.read("AndroidManifest.xml"))
            dex_names = sorted(n for n in archive.namelist()
                               if re.fullmatch(r"classes(?:\d+)?\.dex", n))
            if len(dex_names) < 3:
                raise GateFailure("FINAL_CLONE_MISSING_DEX")
            defined = set()
            for name in dex_names:
                current = dex_descriptors(archive.read(name))
                if defined & current:
                    raise GateFailure("FINAL_CLONE_DUPLICATE_DEX_CLASS")
                defined.update(current)
    except (AxmlError, zipfile.BadZipFile, KeyError, ToolError) as exc:
        raise GateFailure("FINAL_COMPONENT_SCAN_FAILED") from exc
    declarations = [e for e in manifest["elements"] if e["tag"] in ("application", "provider")]
    apps = [e for e in declarations if e["tag"] == "application"]
    providers = [e for e in declarations if e["tag"] == "provider"]
    if len(apps) != 1 or len(providers) != 5:
        raise GateFailure("FINAL_COMPONENT_DECLARATIONS_CHANGED")
    verified = 0
    for e in declarations:
        clazz = e["attributes"].get(android_name)
        if not clazz:
            if e["tag"] == "provider":
                raise GateFailure("FINAL_PROVIDER_CLASS_UNNAMED")
            continue  # platform default Application
        if clazz.startswith("android."):
            continue  # platform-provided Android class
        if clazz.startswith(".") or "." not in clazz:
            raise GateFailure("FINAL_COMPONENT_NAME_NOT_CANONICAL")
        descriptor = "L" + clazz.replace(".", "/") + ";"
        if descriptor not in defined:
            raise GateFailure("FINAL_APP_OR_PROVIDER_CLASS_NOT_IN_APK")
        verified += 1
    return {"status": "FINAL_PROVIDER_AND_APPLICATION_CLASSES_PRESENT",
            "verified_component_classes": verified,
            "provider_count": len(providers), "android_startup": "NOT_TESTED"}


def build(original: Path, output: Path, sdk: Path):
    inspect_original(original)
    android_jar, d8, aapt = find_sdk_tools(sdk)
    # Explicitly reuse Loft DJ's Android SDK, Java and Gradle toolchain.
    # Apktool is the ONLY additional decoder required, pinned to known upstream bytes.
    apktool_jar = os.environ.get("CLASSIC_APKTOOL_JAR")
    if apktool_jar:
        apktool_path = Path(apktool_jar)
        if not apktool_path.is_file() or digest_file(apktool_path) != "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423":
            raise GateFailure("APKTOOL_PIN_MISMATCH")
        apktool = [must_be_tool('java'),'-jar',apktool_path]
    else:
        raise GateFailure('PINNED_APKTOOL_3_0_3_JAR_REQUIRED')
    must_be_tool('javac')
    must_be_tool('jar')
    if output.exists():
        raise GateFailure("OUTPUT_ALREADY_EXISTS")
    if not output.parent.is_dir():
        raise GateFailure("OUTPUT_PARENT_MISSING")
    with tempfile.TemporaryDirectory(dir=output.parent, prefix='.classic-private-build-') as tmpdir:
        temp = Path(tmpdir)
        decoded = temp/'source-decoded'
        bridge = temp/'bridge-build'
        staged = temp/'staged-patch'
        unsigned = temp/'unsigned.apk'
        run([*apktool, 'd', '-s', '-f', '-o', decoded, original])  # -s keeps original dex bytes.
        dex = build_sidecar(sdk, bridge, original_apk=original)
        collision_report = no_oem_class_collisions(original, dex)
        prepare(original, decoded, dex, staged)
        # JSON preparation note does not belong in APK; private report lives next to output.
        (staged/'classic_first_preparation.json').unlink()
        run([*apktool, 'b', '-o', unsigned, staged])
        report = verify_unsigned(unsigned, original, dex, str(aapt))
        report["firebase_early_init_boundary"] = verify_final_firebase_boundary(unsigned)
        report["component_resolution_gate"] = verify_final_component_classes(unsigned)
        report['class_collision_check'] = collision_report
        report['runtime_resolution'] = json.loads((bridge / 'runtime_resolution.json').read_text(encoding='utf-8'))
        report['sdk_platform'] = str(android_jar.relative_to(sdk))
        report['sdk_d8'] = str(d8.relative_to(sdk))
        shutil.copyfile(unsigned, output)
        report_path = output.with_suffix(output.suffix + '.preflight.json')
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    return report


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--original', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--android-sdk', type=Path, required=True)
    args = p.parse_args()
    try:
        result = build(args.original, args.output, args.android_sdk)
        print(json.dumps(result, indent=2))
        return 0
    except (GateFailure, DexRemapError, ToolError, subprocess.SubprocessError, OSError, zipfile.BadZipFile) as e:
        print(json.dumps({'status':'BLOCKED', 'reason':str(e), 'signed':False, 'installed':False}), file=sys.stderr)
        return 2

if __name__ == '__main__':
    sys.exit(main())
