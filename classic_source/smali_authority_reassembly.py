#!/usr/bin/env python3
"""Owner-only, disposable Apktool Smali authority reassembly; no OEM output.

Called after the SHA-pinned original and prepare() have been verified. Exactly
two OEM DEXes are privately disassembled via the already pinned Apktool JAR,
then only known, exact authority string literals are replaced before Apktool
reassembles them with ART-compliant string-id ordering.
"""
from __future__ import annotations
from pathlib import Path
import re
import shutil
from zipfile import ZipFile

from classic_first import GateFailure
from dex_authority_remap import _entries, _replacement

DEX_DIRS = (("classes.dex", "smali"), ("classes2.dex", "smali_classes2"))
QUOTED = re.compile(r'"(?:\\.|[^"\\])*"')
MAX_SMALI_FILES = 200000
MAX_FILE_BYTES = 16 * 1024 * 1024


def rewrite_literals(text):
    if type(text) is not str:
        raise GateFailure("SMALI_TEXT_INVALID")
    found = set()
    def rewrite(match):
        lit = match.group()[1:-1]
        if "\\" in lit or not lit.isascii():
            return match.group()
        old = lit.encode("ascii")
        new = _replacement(old)
        if new is None:
            return match.group()
        found.add(old)
        return '"' + new.decode("ascii") + '"'
    return QUOTED.sub(rewrite, text), found


def stage_reassembled_oem(original: Path, codeonly: Path, staged: Path):
    if not (isinstance(original, Path) and original.is_file()
            and not original.is_symlink() and codeonly.is_dir()
            and not codeonly.is_symlink() and staged.is_dir()
            and not staged.is_symlink()):
        raise GateFailure("SMALI_PRIVATE_SCOPE_INVALID")
    replaced = {}
    with ZipFile(original) as archive:
        for dex, smali_dir in DEX_DIRS:
            original_literals = {
                val for _a, _b, val in _entries(archive.read(dex))
                if _replacement(val) is not None
            }
            source_dir = codeonly / smali_dir
            target_dir = staged / smali_dir
            if not source_dir.is_dir() or source_dir.is_symlink() or target_dir.exists():
                raise GateFailure("SMALI_EXACT_SOURCE_DIR_REQUIRED")
            shutil.copytree(source_dir, target_dir, symlinks=True)
            found = set()
            count = 0
            for path in target_dir.rglob("*"):
                if path.is_symlink():
                    raise GateFailure("SMALI_SYMLINK_FORBIDDEN")
                if not path.is_file():
                    continue
                if path.suffix != ".smali":
                    raise GateFailure("SMALI_NON_SOURCE_FILE")
                count += 1
                if count > MAX_SMALI_FILES or path.stat().st_size > MAX_FILE_BYTES:
                    raise GateFailure("SMALI_SIZE_INVALID")
                try:
                    source = path.read_text(encoding="utf-8")
                except (UnicodeError, OSError) as exc:
                    raise GateFailure("SMALI_TEXT_DECODE_FAILED") from exc
                updated, seen = rewrite_literals(source)
                found.update(seen)
                if updated != source:
                    path.write_text(updated, encoding="utf-8")
            if not count or found != original_literals:
                raise GateFailure("SMALI_REMAP_LITERAL_COVERAGE_INVALID")
            replaced[dex] = len(found)
    # Exactly the two OEM DEX bytes from prepare() are discarded in the
    # quarantined staging tree. The separate classes3.dex native bridge stays.
    for name, _folder in DEX_DIRS:
        target = staged / name
        if not target.is_file() or target.is_symlink():
            raise GateFailure("SMALI_SOURCE_DEX_MISSING")
    for name, _folder in DEX_DIRS:
        (staged / name).unlink()
    return {"status": "PRIVATE_OEM_SMALI_REASSEMBLY_PENDING_FINAL_VALIDATION",
            "original_dex_count": 2,
            "authority_literal_count_by_dex": replaced,
            "private_payload_published": False}
