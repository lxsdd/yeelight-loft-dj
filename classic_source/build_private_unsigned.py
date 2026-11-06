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
from bridge.build_bridge import build as build_sidecar, ToolError, find_sdk_tools, no_oem_class_collisions

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
