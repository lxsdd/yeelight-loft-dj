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

from classic_first import GateFailure, OLD_PACKAGE, NEW_PACKAGE, digest_file, inspect_original, prepare
from dex_authority_remap import (remap_dex, DexRemapError, assert_self_consistent_dex,
                                 _entries, _replacement)
from smali_authority_reassembly import stage_reassembled_oem
from private_ci.oem_ui_reuse_audit import analyze as original_ui_reuse_audit, UiAuditBlocked
from private_ci import private_build_diagnostics as build_diagnostics
from bridge.build_bridge import build as build_sidecar, ToolError, find_sdk_tools, no_oem_class_collisions, dex_descriptors
from apk_readonly_audit import parse_binary_xml, AxmlError
from secondary_dex_transport import normalize_private_multidex, DexTransportBlocked

ROOT = Path(__file__).resolve().parent


def _art_string_ids_sorted(dex: bytes) -> bool:
    """Check DEX string_id ordering. ART rejects out-of-order identifiers.

    For the remapped values, all prefixes are ASCII and direct ordinal
    comparisons are identical to ART's UTF-16 ordering. For arbitrary MUTF-8
    entries, comparing encoded bytes can differ from UTF-16 ordering; the
    trusted apktool assembler owns final sorting, and this function only
    returns False on a definite reversed ASCII pair.
    """
    prev = None
    for _a, _b, text in _entries(dex):
        if prev is not None and prev.isascii() and text.isascii() and prev >= text:
            return False
        prev = text
    return True


def must_be_tool(name):
    path = shutil.which(name)
    if not path:
        raise GateFailure(f"TOOL_MISSING_{name.upper()}")
    return path


def run(args, env=None, *, timeout=240):
    subprocess.run([str(x) for x in args], check=True, timeout=timeout, env=env)


def verify_unsigned(candidate: Path, original: Path, bridge_dex: Path, aapt: str,
                    *, dex_mode="raw", identity_control=False) -> dict:
    if type(identity_control) is not bool:
        raise GateFailure("IDENTITY_ABLATION_BAD_MODE")
    if identity_control and dex_mode != "raw":
        raise GateFailure("IDENTITY_CONTROL_MUST_PRESERVE_OEM_DEX")
    if dex_mode not in ("raw", "smali"):
        raise GateFailure("DEX_MODE_INVALID")
    with zipfile.ZipFile(original) as orig, zipfile.ZipFile(candidate) as out:
        initial = {info.filename for info in orig.infolist()}
        generated = {info.filename for info in out.infolist()}
        original_dex = sorted(x for x in initial if re.fullmatch(r'classes(?:\d+)?\.dex', x))
        output_dex = sorted(x for x in generated if re.fullmatch(r'classes(?:\d+)?\.dex', x))
        expected_remaps = {}
        for name in original_dex:
            source = orig.read(name)
            if source.startswith(b"dex\n"):
                if identity_control:
                    expected = source
                    assert_self_consistent_dex(expected)
                    expected_remaps[name] = 0
                else:
                    expected, detail = remap_dex(source)
                    assert_self_consistent_dex(expected)
                    expected_remaps[name] = detail["changed_string_count"]
            else:
                # Test-only synthetic fixtures; original build() always SHA-pins real APK.
                expected = source
                expected_remaps[name] = 0
            if name not in generated:
                raise GateFailure(f"OEM_DEX_TRANSFORM_MISMATCH_{name}")
            rebuilt = out.read(name)
            if dex_mode == "raw" or not source.startswith(b"dex\n"):
                if hashlib.sha256(expected).digest() != hashlib.sha256(rebuilt).digest():
                    raise GateFailure(f"OEM_DEX_TRANSFORM_MISMATCH_{name}")
            else:
                # Smali reassembly must repair sorted string IDs AND preserve
                # the exact OEM class/parent/interface graph. No code or names
                # leave this private job.
                from private_startup_audit import dex_index
                if dex_index(source) != dex_index(rebuilt):
                    raise GateFailure("OEM_DEX_CLASS_GRAPH_CHANGED")
                original_pool = [raw for _a, _b, raw in _entries(source)]
                rebuilt_pool = {raw for _a, _b, raw in _entries(rebuilt)}
                replacements = [_replacement(raw) for raw in original_pool]
                desired = {rep for rep in replacements if rep is not None}
                forbidden = {raw for raw, rep in zip(original_pool, replacements)
                             if rep is not None}
                if not desired.issubset(rebuilt_pool) or forbidden & rebuilt_pool:
                    raise GateFailure("OEM_DEX_AUTHORITY_STRING_PARITY_FAILED")
                if not _art_string_ids_sorted(rebuilt):
                    raise GateFailure("OEM_DEX_STRING_IDS_NOT_SORTED")
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
    expected_package = OLD_PACKAGE if identity_control else NEW_PACKAGE
    if not m or m.group(1) != expected_package:
        raise GateFailure("NEW_PACKAGE_NOT_IN_FINAL_APK")
    return {
        "status": "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED",
        "apk_sha256": digest_file(candidate),
        "oem_dex_verified": original_dex,
        "controlled_authority_string_remaps": expected_remaps,
        "original_oem_dex_intentionally_changed": any(expected_remaps.values()),
        "dex_reassembly_mode": dex_mode,
        "sidecar_dex": addon[0],
        "package": expected_package,
        "identity_control_offline_only": identity_control,
        "signed": False, "installed": False, "device_hardware": "NOT_TESTED",
        "classic_ui": "NOT_TESTED", "cloud_login": "NOT_TESTED",
        "dj_checkpoint_adapter": "REQUIRES_PRIVATE_RUNTIME_CLASS",
    }


def verify_final_firebase_boundary(candidate: Path, *, identity_control=False) -> dict:
    """The rebuilt private APK must not accidentally re-enable OEM Firebase auto-init."""
    try:
        with zipfile.ZipFile(candidate) as z:
            root = parse_binary_xml(z.read("AndroidManifest.xml"))
    except (AxmlError, KeyError, zipfile.BadZipFile) as exc:
        raise GateFailure("FINAL_BINARY_MANIFEST_INVALID") from exc
    providers = [e["attributes"] for e in root["elements"] if e["tag"] == "provider"]
    if len(providers) != (6 if identity_control else 5):
        raise GateFailure("FINAL_PROVIDER_COUNT_NOT_SIX" if identity_control
                          else "FINAL_PROVIDER_COUNT_NOT_FIVE")
    dj = [p for p in providers if p.get(
        "{http://schemas.android.com/apk/res/android}name") ==
        "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjEntryProvider"]
    if identity_control:
        if len(dj) != 1 or dj[0].get(
                "{http://schemas.android.com/apk/res/android}authorities") != (
                "com.yeelight.cherry.loftdj.entry") or dj[0].get(
                "{http://schemas.android.com/apk/res/android}exported") != "false":
            raise GateFailure("FINAL_IDENTITY_ENTRY_PROVIDER_INVALID")
    elif dj:
        raise GateFailure("FINAL_CLONE_HAS_IDENTITY_ENTRY_PROVIDER")
    for p in providers:
        if p.get("{http://schemas.android.com/apk/res/android}name") == \
                "com.google.firebase.provider.FirebaseInitProvider":
            raise GateFailure("FINAL_FIREBASE_INIT_REINTRODUCED")
        if p.get("{http://schemas.android.com/apk/res/android}authorities", "").endswith(".firebaseinitprovider"):
            raise GateFailure("FINAL_FIREBASE_AUTHORITY_REINTRODUCED")
        if p.get("{http://schemas.android.com/apk/res/android}name") == \
                "com.yeelight.yeelib.data.DeviceInteractionProvider":
            raise GateFailure("FINAL_MISSING_DEVICE_PROVIDER_REINTRODUCED")
    return {"status": "PRIVATE_EARLY_STARTUP_PROVIDERS_RECONCILED", "remaining_oem_providers": 5, "identity_dj_entry_provider": bool(identity_control),
            "android_startup": "NOT_TESTED", "cloud_login": "NOT_TESTED"}


def verify_final_component_classes(candidate: Path, *, identity_control=False) -> dict:
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
    if len(apps) != 1 or len(providers) != (6 if identity_control else 5):
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


def build(original: Path, output: Path, sdk: Path, *, identity_control=False):
    if type(identity_control) is not bool:
        raise GateFailure("IDENTITY_ABLATION_BAD_MODE")
    if identity_control:
        tmp = os.environ.get("RUNNER_TEMP", "")
        from pathlib import Path as _Path
        if (not tmp or output != _Path(tmp) / "private-output" /
                "classic-loft-dj-IDENTITY-UNSIGNED.apk"
                or os.environ.get("GITHUB_REPOSITORY") != "lxsdd/yeelight-loft-dj"
                or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
                or os.environ.get("GITHUB_ACTOR") != "lxsdd"
                or os.environ.get("GITHUB_REF") != "refs/heads/main"
                or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted"
                or os.environ.get("GH_TOKEN")
                or os.environ.get("CLASSIC_VAULT_TOKEN")):
            raise GateFailure("IDENTITY_ABLATION_OWNER_OFFLINE_SCOPE_REQUIRED")
    build_diagnostics.phase('ORIGINAL_PIN')
    inspect_original(original)
    build_diagnostics.phase('TOOLCHAIN_PIN')
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
        build_diagnostics.phase('RESOURCE_DECODE')
        run([*apktool, 'd', '-s', '-f', '-o', decoded, original])  # -s keeps original dex bytes.
        build_diagnostics.phase('ANDROID_BRIDGE')
        dex = build_sidecar(sdk, bridge, original_apk=original)
        build_diagnostics.phase('BRIDGE_COLLISION')
        collision_report = no_oem_class_collisions(original, dex)
        build_diagnostics.phase('STAGE_ORIGINAL')
        prepare(original, decoded, dex, staged,
                identity_control=identity_control)
        # JSON preparation note does not belong in APK; private report lives next to output.
        (staged/'classic_first_preparation.json').unlink()
        # In-place changing an authority value from com.* to io.* reseals
        # checksums but leaves DEX string_ids indexes out of ART's mandated
        # UTF-16 sort order. Reassemble OEM Smali with existing pinned Apktool
        # so its writer updates every string index and validates class graph.
        if identity_control:
            # Preserve the OEM DEX byte-for-byte in the identity A/B control.
            # The stock package/authorities need no reassembly.
            smali_report = {"status": "ORIGINAL_OEM_DEX_BYTES_PRESERVED",
                            "private_payload_published": False}
            ui_reuse = {"status": "IDENTITY_CONTROL_NO_SOURCE_DECODE",
                        "original_apk_or_smali_published": False,
                        "runtime_ui_navigation_verified": False}
        else:
            codeonly = temp / 'source-code-only'
            build_diagnostics.phase('ORIGINAL_SMALI_DECODE')
            run([*apktool, 'd', '-r', '-f', '-o', codeonly, original],
                timeout=600)
            # The original resources (-s) and original Smali (-r) are already
            # decoded for the mandatory OEM DEX reassembly. Reuse those
            # immutable PRIVATE inputs to record safe UI-role evidence.
            # Never analyze patched decoded trees or publish original symbols.
            build_diagnostics.phase('OPTIONAL_UI_AUDIT')
            try:
                ui_reuse = original_ui_reuse_audit(decoded, codeonly)
            except (UiAuditBlocked, OSError, UnicodeError, ValueError, TypeError) as ui_error:
                # Optional read-only instrumentation is not a core APK gate.
                # No missing UI guess may prevent the protected APK parity
                # checks or emulator from running; never fabricate evidence.
                safe_reason = build_diagnostics.safe_ui_reason(
                    str(ui_error) if isinstance(ui_error, UiAuditBlocked) else None
                )
                build_diagnostics.record_optional_ui_unavailable(safe_reason)
                ui_reuse = {
                    "status": "ORIGINAL_UI_REUSE_AUDIT_UNAVAILABLE_NOT_ACCEPTED",
                    "safe_reason": safe_reason,
                    "original_apk_or_smali_published": False,
                    "private_symbols_emitted": False,
                    "runtime_ui_navigation_verified": False,
                    "original_behavior_compatibility_verified": False,
                    "production_hook_approved": False,
                }
            build_diagnostics.phase('OEM_SMALI_REASSEMBLY')
            smali_report = stage_reassembled_oem(original, codeonly, staged)
        build_diagnostics.phase('APKTOOL_REBUILD')
        run([*apktool, 'b', '-f', '-o', unsigned, staged], timeout=600)
        build_diagnostics.phase('MULTIDEX_LAYOUT')
        zip_layout = normalize_private_multidex(original, unsigned)
        build_diagnostics.phase('UNSIGNED_PARITY')
        report = verify_unsigned(
            unsigned, original, dex, str(aapt),
            dex_mode="raw" if identity_control else "smali",
            identity_control=identity_control)
        report["oem_smali_reassembly"] = smali_report
        report["original_ui_reuse_inventory"] = ui_reuse
        report['secondary_dex_transport'] = zip_layout
        build_diagnostics.phase("FINAL_COMPONENT_GATES")
        report["firebase_early_init_boundary"] = verify_final_firebase_boundary(
            unsigned, identity_control=identity_control)
        report["component_resolution_gate"] = verify_final_component_classes(
            unsigned, identity_control=identity_control)
        report['class_collision_check'] = collision_report
        report['runtime_resolution'] = json.loads((bridge / 'runtime_resolution.json').read_text(encoding='utf-8'))
        report['sdk_platform'] = str(android_jar.relative_to(sdk))
        report['sdk_d8'] = str(d8.relative_to(sdk))
        build_diagnostics.phase('PRIVATE_OUTPUT_COPY')
        shutil.copyfile(unsigned, output)
        report_path = output.with_suffix(output.suffix + '.preflight.json')
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
        build_diagnostics.phase('RECEIPT_WRITTEN')
    return report


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--original', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--android-sdk', type=Path, required=True)
    p.add_argument('--identity-control', action='store_true',
                   help="owner-only offline original-package ablation; no distribution")
    args = p.parse_args()
    try:
        result = build(args.original, args.output, args.android_sdk,
                       identity_control=args.identity_control)
        print(json.dumps(result, indent=2))
        return 0
    except (GateFailure, DexRemapError, ToolError, DexTransportBlocked, subprocess.SubprocessError, OSError, zipfile.BadZipFile) as e:
        build_diagnostics.record_failure(e)
        print(json.dumps({'status':'BLOCKED', 'reason':str(e), 'signed':False, 'installed':False}), file=sys.stderr)
        return 2
    except Exception as error:
        # Full exception remains in ephemeral local log. Owner job summary
        # receives only fixed phase/category tokens, never original paths.
        build_diagnostics.record_failure(error)
        raise

if __name__ == '__main__':
    sys.exit(main())
