#!/usr/bin/env python3
"""Fail-closed one-method OEM main-process guard correction for PRIVATE clone only.

The original helper reads processName and compares to the hardcoded stock package.
Replace only the literal load with Application.getPackageName(): the comparison,
branching, default false path, MiOT side-process flow and original APK stay intact.
Called only on the rebuilt separate-package clone path; identity control keeps raw DEX.
"""
from __future__ import annotations
from pathlib import Path
import re
from classic_first import GateFailure

CLASS = "Lcom/yeelight/cherry/YeelightApplication;"
SOURCE_PATH = Path("smali/com/yeelight/cherry/YeelightApplication.smali")
OLD = "com.yeelight.cherry"
ENTRY = re.compile(r"(?ms)^\.method\s+public\s+h\(\)Z\s*$.*?^\.end method\s*$")
CONST = re.compile(r'(?m)^([ \t]*)const-string\s+v1,\s*"com\.yeelight\.cherry"[ \t]*$')
EXPECTED_EQUALS = re.compile(r'(?m)^\s*invoke-virtual\s+\{v0,\s*v1\},\s*Ljava/lang/String;->equals\(Ljava/lang/Object;\)Z\s*$')
EXPECTED_CALL = re.compile(r'(?m)^\s*invoke-direct\s+\{p0\},\s*'+re.escape(CLASS)+r'->f\(\)Ljava/lang/String;\s*$')

def patch_guard_text(source: str) -> str:
    if not isinstance(source, str) or not 0 < len(source) < 1000000:
        raise GateFailure("CLASSIC_GUARD_SMALI_INPUT_INVALID")
    if len(re.findall(r'(?m)^\.class\s+[^\n]*'+re.escape(CLASS)+r'\s*$', source)) != 1:
        raise GateFailure("CLASSIC_GUARD_CLASS_MISMATCH")
    matches = list(ENTRY.finditer(source))
    if len(matches) != 1:
        raise GateFailure("CLASSIC_GUARD_METHOD_NOT_UNIQUE")
    m = matches[0]
    method = m.group()
    if (len(re.findall(r'(?m)^\s*\.locals\s+2\s*$', method)) != 1 or
            len(EXPECTED_CALL.findall(method)) != 1 or
            len(EXPECTED_EQUALS.findall(method)) != 1 or
            len(CONST.findall(method)) != 1 or
            method.count("->getPackageName()Ljava/lang/String;") or
            method.count('"'+OLD+'"') != 1):
        raise GateFailure("CLASSIC_GUARD_ORIGINAL_INSTRUCTION_SHAPE_INVALID")
    def subst(match):
        indent = match.group(1)
        return (indent + "invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n"
                + indent + "move-result-object v1")
    replacement = CONST.sub(subst, method, count=1)
    if (replacement.count("getPackageName()Ljava/lang/String;") != 1 or
            len(EXPECTED_EQUALS.findall(replacement)) != 1 or
            len(EXPECTED_CALL.findall(replacement)) != 1 or
            replacement.count('"'+OLD+'"') != 0):
        raise GateFailure("CLASSIC_GUARD_PATCH_VERIFICATION_FAILED")
    return source[:m.start()] + replacement + source[m.end():]

def patch_staged_clone(staged: Path) -> dict:
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("CLASSIC_GUARD_CLASS_FILE_MISSING")
    original = path.read_text(encoding="utf-8")
    patched = patch_guard_text(original)
    path.write_text(patched, encoding="utf-8")
    return {"status": "PRIVATE_CLONE_MAIN_PROCESS_PREDICATE_PATCHED_NOT_RUNTIME_VERIFIED",
            "original_oem_methods_modified": 1,
            "other_methods_modified": 0,
            "guarded_old_package_literal_replaced": 1,
            "original_identity_control_untouched": True,
            "runtime_verified": False}
