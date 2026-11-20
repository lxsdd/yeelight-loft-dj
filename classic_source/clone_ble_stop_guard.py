#!/usr/bin/env python3
"""Private-clone-only, fail-closed BLE scanner null guard.

An original Classic device-handler schedules scan-stop through one OEM helper.
The actual Pixel6a Java crash is in that helper. The original helper's callgraph
contains BluetoothLeScanner.stopScan(ScanCallback), but public synthetic tests
cannot qualify the exact private Smali or a successful runtime scan.

Patch only that precisely scoped OEM helper in quarantined private clone Smali.
Neither original OEM APK, default successful scanner path, Bluetooth state,
nor real device/session data is modified by this source-only gate.
"""
from __future__ import annotations

from pathlib import Path
import re
from classic_first import GateFailure

SOURCE_PATH = Path("smali_classes2/com/yeelight/yeelib/managers/y.smali")
OWNER = "Lcom/yeelight/yeelib/managers/y;"
CALLBACK = "a(Landroid/bluetooth/le/BluetoothLeScanner;Landroid/bluetooth/le/ScanCallback;)V"
STOP = "Landroid/bluetooth/le/BluetoothLeScanner;->stopScan(Landroid/bluetooth/le/ScanCallback;)V"
LABEL = ":classic_clone_ble_scanner_present"
METHOD = re.compile(
    r"(?ms)^\.method[ \t]+[^\r\n]*\bstatic\b[^\r\n]*\b" + re.escape(CALLBACK)
    + r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$"
)
STOP_INVOKE = re.compile(
    r"^[ \t]*invoke-virtual(?:/range)?[ \t]+\{(?P<args>[^}\r\n]+)\},[ \t]*"
    + re.escape(STOP) + r"[ \t]*$"
)
DECL = re.compile(r"(?m)^[ \t]*\.(?:registers|locals)[ \t]+\d+[ \t]*\r?$", re.MULTILINE)
# Defensive: no early return while holding an object monitor or jumping out
# of an exception-handling region in an unverified OEM method.
UNSAFE_REGION = re.compile(r"(?m)^[ \t]*(?:monitor-enter|monitor-exit|\.catch(?:all)?\b)")
WRITES_P0 = re.compile(
    r"^[ \t]*(?:move(?:-object|-wide)?(?:/[0-9a-z]+)?|const(?:-string|-class|-wide|-high16)?(?:/[0-9a-z]+)?"
    r"|new-instance|iget[^ \t]*|sget[^ \t]*|aget[^ \t]*|check-cast)\s+p0\s*(?:,|$)"
)


def patch_ble_stop_text(source: str) -> str:
    if type(source) is not str or not 0 < len(source) <= 1000000:
        raise GateFailure("CLASSIC_BLE_GUARD_SMALI_INPUT_INVALID")
    if len(re.findall(r"(?m)^\.class[ \t]+[^\r\n]*" + re.escape(OWNER) + r"[ \t]*\r?$", source)) != 1:
        raise GateFailure("CLASSIC_BLE_GUARD_OWNER_MISMATCH")
    matches = list(METHOD.finditer(source))
    if len(matches) != 1:
        raise GateFailure("CLASSIC_BLE_GUARD_STATIC_METHOD_NOT_UNIQUE")
    found = matches[0]
    method = found.group()
    if LABEL in source:
        raise GateFailure("CLASSIC_BLE_GUARD_ALREADY_APPLIED")
    if not DECL.search(method):
        raise GateFailure("CLASSIC_BLE_GUARD_REGISTERS_UNVERIFIED")
    if UNSAFE_REGION.search(method):
        raise GateFailure("CLASSIC_BLE_GUARD_MONITOR_OR_CATCH_UNVERIFIED")
    if method.count(STOP) != 1:
        raise GateFailure("CLASSIC_BLE_GUARD_STOP_SITE_NOT_UNIQUE")
    lines = method.splitlines(keepends=True)
    matches_call = [(i, STOP_INVOKE.fullmatch(line.rstrip("\r\n")))
                    for i, line in enumerate(lines)]
    matches_call = [(i, m) for i, m in matches_call if m is not None]
    if len(matches_call) != 1:
        raise GateFailure("CLASSIC_BLE_GUARD_STOP_CALL_SHAPE_UNVERIFIED")
    idx, call = matches_call[0]
    # For a static (BluetoothLeScanner, ScanCallback) method, p0 is exactly
    # the scanner and p1 the callback. Never infer unknown register aliases.
    if re.sub(r"\s+", "", call.group("args")) != "p0,p1":
        raise GateFailure("CLASSIC_BLE_GUARD_REGISTER_PROVENANCE_UNVERIFIED")
    if any(WRITES_P0.match(line.rstrip("\r\n")) for line in lines[:idx]):
        raise GateFailure("CLASSIC_BLE_GUARD_SCANNER_REGISTER_OVERWRITTEN")
    indent = re.match(r"^[ \t]*", lines[idx]).group()
    guard = (indent + "if-nez p0, " + LABEL + "\n"
             + indent + "return-void\n"
             + indent + LABEL + "\n")
    lines.insert(idx, guard)
    amended = "".join(lines)
    if (amended.count(STOP) != 1 or amended.count(LABEL) != 2
            or amended.count("if-nez p0, " + LABEL) != 1
            or amended.count("return-void") != method.count("return-void") + 1):
        raise GateFailure("CLASSIC_BLE_GUARD_PATCH_VERIFICATION_FAILED")
    return source[:found.start()] + amended + source[found.end():]


def patch_staged_clone(staged: Path) -> dict:
    if not isinstance(staged, Path):
        raise GateFailure("CLASSIC_BLE_GUARD_FILE_MISSING")
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("CLASSIC_BLE_GUARD_FILE_MISSING")
    original = path.read_text(encoding="utf-8")
    updated = patch_ble_stop_text(original)
    path.write_text(updated, encoding="utf-8")
    return {
        "status": "CLONE_BLE_SCAN_STOP_NULL_GUARDED_NOT_RUNTIME_VERIFIED",
        "original_oem_methods_modified": 1,
        "other_methods_modified": 0,
        "null_scanner_skip_only": True,
        "valid_scanner_stop_unchanged": True,
        "original_identity_control_untouched": True,
        "runtime_verified": False,
    }
