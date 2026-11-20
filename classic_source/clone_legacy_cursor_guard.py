#!/usr/bin/env python3
"""Fail-closed private-clone-only Cursor-null guard for absent OEM provider.

Only the exact known original device callback and exact provider API may be
patched. Smali's non-executable debugging directives are tolerated between
invoke-static and move-result-object; any real opcode or label still blocks.
This is clone-only staging, not an OEM source distribution or runtime PASS.
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
    r"(?ms)^\.method[ \t]+[^\r\n]*\ba\(Lcom/yeelight/yeelib/device/base/e;\)V[ \t]*\r?$"
    r".*?^\.end method[ \t]*\r?$")
INVOKE = re.compile(
    r"^[ \t]*invoke-static(?:/range)?[ \t]+\{(?P<args>[^}\r\n]+)\},[ \t]*"
    + re.escape(QUERY) + r"[ \t]*$")
RESULT = re.compile(r"^[ \t]*move-result-object[ \t]+(?P<reg>[vp]\d+)[ \t]*$")
CURSOR_CALL = re.compile(
    r"^[ \t]*invoke-interface(?:/range)?[ \t]+\{[^}\r\n]+\},[ \t]*"
    r"Landroid/database/Cursor;->moveToFirst\(\)Z[ \t]*$")
DEBUG = re.compile(
    r"^[ \t]*\.(?:line[ \t]+\d+|prologue|epilogue|local[ \t]+.*|end local[ \t]+.*|restart local[ \t]+.*)[ \t]*$")
LOCALS = re.compile(r"(?m)^[ \t]*\.(?:locals|registers)[ \t]+\d+[ \t]*\r?$")


def _line_text(line: str) -> str:
    # normalize Windows line endings for matching only; original bytes kept.
    return line.rstrip("\r\n")


def _result_index(lines: list[str], call_index: int) -> tuple[int, str]:
    """DEX invoke result must be the next executable opcode, no jump/label."""
    ignored = 0
    for i in range(call_index + 1, min(len(lines), call_index + 18)):
        line = _line_text(lines[i])
        if not line.strip() or line.lstrip().startswith("#") or DEBUG.fullmatch(line):
            ignored += 1
            if ignored > 12:
                break
            continue
        matched = RESULT.fullmatch(line)
        if matched:
            return i, matched.group("reg")
        # Never skip labels, try/catch markers or real opcodes. Fail closed.
        break
    raise GateFailure("CLASSIC_CURSOR_RESULT_NOT_ADJACENT_EXECUTABLE")


def patch_callback_text(source: str) -> str:
    if type(source) is not str or not 0 < len(source) < 1000000:
        raise GateFailure("CLASSIC_CURSOR_SMALI_INPUT_INVALID")
    if len(re.findall(r"(?m)^\.class[ \t]+[^\r\n]*" + re.escape(OWNER) + r"[ \t]*\r?$", source)) != 1:
        raise GateFailure("CLASSIC_CURSOR_OWNER_MISMATCH")
    matches = list(METHOD.finditer(source))
    if len(matches) != 1:
        raise GateFailure("CLASSIC_CURSOR_CALLBACK_NOT_UNIQUE")
    match = matches[0]
    method = match.group()
    if TAG in source:
        raise GateFailure("CLASSIC_CURSOR_DOUBLE_PATCH_BLOCKED")
    if not LOCALS.search(method):
        raise GateFailure("CLASSIC_CURSOR_REGISTER_DECLARATION_MISSING")
    # Method must have one exact provider query and a known cursor-use path.
    lines = method.splitlines(keepends=True)
    calls = [i for i, line in enumerate(lines) if INVOKE.fullmatch(_line_text(line))]
    query_occurrences = method.count(QUERY)
    if len(calls) != 1 or query_occurrences != 1:
        raise GateFailure("CLASSIC_CURSOR_QUERY_SITE_NOT_UNIQUE")
    uses = sum(CURSOR_CALL.fullmatch(_line_text(line)) is not None for line in lines)
    if uses != 1:
        raise GateFailure("CLASSIC_CURSOR_DEREFERENCE_SHAPE_UNVERIFIED")
    # No implicit monitor exit or exception handler transfer on early return.
    if re.search(r"(?m)^[ \t]*(?:monitor-enter|monitor-exit)[ \t]", method):
        raise GateFailure("CLASSIC_CURSOR_MONITOR_FLOW_UNVERIFIED")
    call_index = calls[0]
    result_index, reg = _result_index(lines, call_index)
    if result_index < call_index or result_index >= len(lines):
        raise GateFailure("CLASSIC_CURSOR_RESULT_BOUNDARY_INVALID")
    # Reject excessive or out-of-bounds allocation; no new registers needed.
    indent = re.match(r"^[ \t]*", lines[result_index]).group()
    inserted = (
        indent + "if-nez " + reg + ", " + TAG + "_present\n"
        + indent + "return-void\n"
        + indent + TAG + "_present\n"
    )
    lines.insert(result_index + 1, inserted)
    replacement = "".join(lines)
    if (replacement.count(QUERY) != 1 or replacement.count(TAG + "_present") != 2
            or replacement.count("return-void") != method.count("return-void") + 1
            or replacement.count("if-nez " + reg + ", " + TAG + "_present") != 1
            or len(re.findall(re.escape(QUERY), replacement)) != 1):
        raise GateFailure("CLASSIC_CURSOR_GUARD_VERIFICATION_FAILED")
    return source[:match.start()] + replacement + source[match.end():]


def patch_staged_clone(staged: Path) -> dict:
    if not isinstance(staged, Path):
        raise GateFailure("CLASSIC_CURSOR_CALLBACK_FILE_MISSING")
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("CLASSIC_CURSOR_CALLBACK_FILE_MISSING")
    original = path.read_text(encoding="utf-8")
    patched = patch_callback_text(original)
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
