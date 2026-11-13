#!/usr/bin/env python3
"""Private, read-only NPE callsite text differential; no OEM symbols escape.

The caller must already have checked the owner-only offline emulator scope,
exact original APK hash, installed clone hash and target crash. Both APKs and
Apktool are consumed only in a disposable runner path. This is diagnostic
evidence, never proof of root cause or functional acceptance.
"""
from __future__ import annotations
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZipFile

from android17_start_smoke import (
    PACKAGE, _android_runtime_message, NULL_STACK_FRAME, ROOT_CAUSE,
    MAX_FATAL_BLOCK_LINES, classify_crash,
)
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import ORIGINAL_SHA256, digest_file
from smali_authority_reassembly import rewrite_literals

PINNED_APKTOOL = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"
MAX_TEXT = 2_000_000
MAX_SMALI = 4_000_000
METHOD = re.compile(r"^\.method\s+(?:\S+\s+)*([A-Za-z_$<>][A-Za-z0-9_$<>]*)\(([^()\r\n]*)\)(\S+)\s*$")
CLASS = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*\Z")
RELATIONS = frozenset({
    "UNAVAILABLE", "FIRST_FRAME_NOT_IN_BOTH_OEM_DEX",
    "METHOD_MISSING_OR_AMBIGUOUS", "OVERLOAD_SET_DIFFERS",
    "METHOD_TEXT_IDENTICAL", "KNOWN_AUTHORITY_LITERALS_ONLY",
    "OTHER_METHOD_TEXT_DIFFERENCE",
})


class NullSiteBlocked(ValueError):
    """Fixed public error code only."""


def require(test, code):
    if not test:
        raise NullSiteBlocked(code)


def target_null_site(logcat, package=PACKAGE):
    """Extract target first NPE Java class+method privately, not from a foreign FATAL."""
    require(type(logcat) is str and len(logcat) <= MAX_TEXT and package == PACKAGE,
            "NULL_SITE_INPUT_INVALID")
    lines = logcat.splitlines()
    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" not in _android_runtime_message(line):
            continue
        block = []
        for row in lines[i + 1:i + 1 + MAX_FATAL_BLOCK_LINES]:
            if "FATAL EXCEPTION" in _android_runtime_message(row):
                break
            block.append(row)
        process = re.search(r"\bProcess:\s*([A-Za-z0-9_.:]+)\s*,\s*PID:",
                            "\n".join(_android_runtime_message(s) for s in block[:14]))
        if not process or not (process.group(1) == package or
                               process.group(1).startswith(package + ":")):
            continue
        selected = "\n".join([_android_runtime_message(line)] +
                             [_android_runtime_message(row) for row in block])
        if classify_crash(selected, package=package)["exception_category"] != "NULL_DEREFERENCE":
            return None
        after_null = False
        for row in block:
            msg = _android_runtime_message(row)
            cause = ROOT_CAUSE.match(msg)
            if cause and cause.group(2).rsplit(".", 1)[-1] == "NullPointerException":
                after_null = True
            if after_null:
                frame = NULL_STACK_FRAME.fullmatch(msg)
                if frame:
                    return frame.groups()
        return None
    return None


def method_blocks(text, method):
    """Parse every overload of one method; never serialize the returned text."""
    require(type(text) is str and len(text) <= MAX_SMALI,
            "NULL_SITE_SMALI_TEXT_INVALID")
    require(type(method) is str and
            bool(re.fullmatch(r"[A-Za-z_$<>][A-Za-z0-9_$<>]*", method)),
            "NULL_SITE_METHOD_INVALID")
    result = {}
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        match = METHOD.fullmatch(lines[i])
        if match is None:
            i += 1
            continue
        end = i + 1
        while end < len(lines) and lines[end].strip() != ".end method":
            end += 1
        require(end < len(lines), "NULL_SITE_UNTERMINATED_METHOD")
        if match.group(1) == method:
            key = match.group(1) + "(" + match.group(2) + ")" + match.group(3)
            require(key not in result, "NULL_SITE_DUPLICATE_METHOD")
            result[key] = "\n".join(lines[i:end + 1]) + "\n"
        i = end + 1
    return result


def compare_smali(old_text, new_text, method):
    old = method_blocks(old_text, method)
    new = method_blocks(new_text, method)
    if not old or not new:
        return "METHOD_MISSING_OR_AMBIGUOUS"
    if set(old) != set(new):
        return "OVERLOAD_SET_DIFFERS"
    if all(old[k] == new[k] for k in old):
        return "METHOD_TEXT_IDENTICAL"
    if all(rewrite_literals(old[k])[0] == new[k] for k in old):
        return "KNOWN_AUTHORITY_LITERALS_ONLY"
    return "OTHER_METHOD_TEXT_DIFFERENCE"


def compare_private(original: Path, candidate: Path, logcat: str, temp: Path):
    """Run only from verified protected original-baseline procedure.

    No output except an allowlisted relation. An NPE is still a test failure.
    """
    require(type(temp) is Path and
            original == temp / "private-original/com.yeelight.cherry_3.5.4.apk" and
            candidate == temp / "private-output/classic-loft-dj-TEST-SIGNED.apk" and
            all(p.is_file() and not p.is_symlink() for p in (original, candidate)) and
            os.environ.get("RUNNER_TEMP") == str(temp),
            "NULL_SITE_PRIVATE_SCOPE_REQUIRED")
    require(digest_file(original) == ORIGINAL_SHA256,
            "NULL_SITE_ORIGINAL_HASH_MISMATCH")
    site = target_null_site(logcat)
    if site is None:
        return "UNAVAILABLE"
    klass, method = site
    if not CLASS.fullmatch(klass):
        return "UNAVAILABLE"
    descriptor = "L" + klass.replace(".", "/") + ";"
    try:
        from private_startup_audit import dex_index
        with ZipFile(original) as stock, ZipFile(candidate) as clone:
            if (descriptor not in dex_index(stock.read("classes2.dex")) or
                    descriptor not in dex_index(clone.read("classes2.dex"))):
                return "FIRST_FRAME_NOT_IN_BOTH_OEM_DEX"
    except Exception:
        raise NullSiteBlocked("NULL_SITE_DEX_INDEX_FAILED") from None
    jar = temp / "classic-tools" / "apktool_3.0.3.jar"
    require(jar.is_file() and not jar.is_symlink() and
            digest_file(jar) == PINNED_APKTOOL,
            "NULL_SITE_PINNED_APKTOOL_REQUIRED")
    java = shutil.which("java")
    require(bool(java), "NULL_SITE_JAVA_REQUIRED")
    with tempfile.TemporaryDirectory(dir=temp, prefix="classic-null-site-") as name:
        root = Path(name)
        old_dir = root / "original"
        new_dir = root / "candidate"
        for apk, dest in ((original, old_dir), (candidate, new_dir)):
            try:
                process = subprocess.run(
                    [java, "-jar", str(jar), "d", "-r", "-f", "-o",
                     str(dest), str(apk)],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=240, check=False)
            except (OSError, subprocess.TimeoutExpired):
                raise NullSiteBlocked("NULL_SITE_DECODE_FAILED") from None
            require(process.returncode == 0, "NULL_SITE_DECODE_FAILED")
        rel = Path("smali_classes2") / (klass.replace(".", "/") + ".smali")
        contents = []
        for parent in (old_dir, new_dir):
            file = parent / rel
            require(file.is_file() and not file.is_symlink() and
                    file.resolve().is_relative_to(parent.resolve()) and
                    file.stat().st_size <= MAX_SMALI,
                    "NULL_SITE_METHOD_FILE_UNAVAILABLE")
            try:
                contents.append(file.read_text(encoding="utf-8"))
            except (OSError, UnicodeError):
                raise NullSiteBlocked("NULL_SITE_METHOD_FILE_UNAVAILABLE") from None
        result = compare_smali(contents[0], contents[1], method)
        require(result in RELATIONS, "NULL_SITE_RELATION_UNSAFE")
        return result
