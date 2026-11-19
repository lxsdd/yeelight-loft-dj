#!/usr/bin/env python3
"""Fail-closed private-clone-only null Cursor guard for the legacy OEM provider.

The private clone intentionally omits the original DeviceInteractionProvider
manifest entry after a genuine pre-Activity startup failure. A separately
qualified OEM callback still queries this now-unregistered provider and
dereferences its returned Cursor. Only the empty answer is short-circuited.
The real OEM APK, valid Cursor path, other OEM methods, and identity control
remain untouched. Runtime device-list acceptance is NOT implied.
"""
from __future__ import annotations
from pathlib import Path
import re
from classic_first import GateFailure

SOURCE_PATH = Path("smali_classes2/com/yeelight/yeelib/managers/v.smali")
OWNER = "Lcom/yeelight/yeelib/managers/v;"
QUERY = ("Lcom/yeelight/yeelib/data/DeviceInteractionProvider;"
         "->f(Lcom/yeelight/yeelib/device/base/e;)Landroid/database/Cursor;")
TAG = ":classic_private_missing_legacy_cursor"
METHOD = re.compile(
    r"(?ms)^\.method\s+[^\r\n]*\ba\(Lcom/yeelight/yeelib/device/base/e;\)V\s*$.*?^\.end method\s*$")
CALL = re.compile(
    r"(?m)^(?P<indent>[ \t]*)invoke-static(?:/range)?\s+\{[^}\r\n]+\},\s*"
    + re.escape(QUERY) + r"[ \t]*\r?\n[ \t]*move-result-object\s+(?P<reg>[vp]\d+)[ \t]*$")
MOVE_TO_FIRST = re.compile(
    r"(?m)^\s*invoke-interface(?:/range)?\s+\{[^}\r\n]+\},\s*"
    r"Landroid/database/Cursor;->moveToFirst\(\)Z\s*$")


def patch_callback_text(source: str) -> str:
    if not isinstance(source, str) or not 0 < len(source) < 1000000:
        raise GateFailure("CLASSIC_CURSOR_SMALI_INPUT_INVALID")
    if len(re.findall(r"(?m)^\.class\s+[^\r\n]*" + re.escape(OWNER) + r"\s*$", source)) != 1:
        raise GateFailure("CLASSIC_CURSOR_OWNER_MISMATCH")
    methods = list(METHOD.finditer(source))
    if len(methods) != 1:
        raise GateFailure("CLASSIC_CURSOR_CALLBACK_NOT_UNIQUE")
    match = methods[0]
    method = match.group()
    calls = list(CALL.finditer(method))
    if (len(calls) != 1 or method.count(QUERY) != 1
            or len(MOVE_TO_FIRST.findall(method)) != 1
            or not re.search(r"(?m)^\s*\.(?:locals|registers)\s+\d+\s*$", method)
            or TAG in source):
        raise GateFailure("CLASSIC_CURSOR_CALL_SHAPE_UNVERIFIED")
    call = calls[0]
    reg, indent = call.group("reg"), call.group("indent")
    use = (r"(?m)^\s*invoke-interface(?:/range)?\s+\{\s*" + re.escape(reg)
           + r"\s*\},\s*Landroid/database/Cursor;->moveToFirst\(\)Z\s*$")
    if not re.search(use, method):
        raise GateFailure("CLASSIC_CURSOR_RESULT_REGISTER_DRIFT")
    # The valid Cursor branch runs the exact original OEM instructions.
    # The absent-provider branch does not synthesize devices or query data.
    branch = ("\n" + indent + "if-nez " + reg + ", " + TAG + "_present"
              + "\n" + indent + "return-void"
              + "\n" + indent + TAG + "_present")
    replacement = method[:call.end()] + branch + method[call.end():]
    if (replacement.count(QUERY) != 1 or len(MOVE_TO_FIRST.findall(replacement)) != 1
            or replacement.count("if-nez " + reg + ", " + TAG + "_present") != 1
            or replacement.count("return-void") != method.count("return-void") + 1
            or replacement.count(TAG + "_present") != 2):
        raise GateFailure("CLASSIC_CURSOR_GUARD_VERIFICATION_FAILED")
    return source[:match.start()] + replacement + source[match.end():]


def patch_staged_clone(staged: Path) -> dict:
    if not isinstance(staged, Path):
        raise GateFailure("CLASSIC_CURSOR_CALLBACK_FILE_MISSING")
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("CLASSIC_CURSOR_CALLBACK_FILE_MISSING")
    source = path.read_text(encoding="utf-8")
    patched = patch_callback_text(source)
    path.write_text(patched, encoding="utf-8")
    return {
        "status": "PRIVATE_CLONE_MISSING_LEGACY_CURSOR_GUARDED_RUNTIME_UNVERIFIED",
        "original_oem_methods_modified": 1,
        "other_methods_modified": 0,
        "legacy_provider_restored": False,
        "null_cursor_handled": True,
        "original_identity_control_untouched": True,
        "runtime_verified": False,
    }
