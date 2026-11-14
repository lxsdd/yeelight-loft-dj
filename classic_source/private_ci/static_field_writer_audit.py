#!/usr/bin/env python3
"""Pure, bounded OEM-SMALI writer provenance for a private crash's static field.

No APK, device or network operations. Private class/field/method identifiers
are never returned by the reporting function, logged, committed or uploaded.
Not a claim that any particular initializer executed or should be restored.
"""
from __future__ import annotations
from pathlib import Path
import re

FIELD = re.compile(r"L[A-Za-z0-9_$/]+;->[A-Za-z0-9_$]+:(?:L[A-Za-z0-9_$/]+;|\[+L[A-Za-z0-9_$/]+;)\Z")
GET = re.compile(r"^\s*sget-object\s+[vp]\d+,\s*(L\S+)\s*$")
PUT = re.compile(r"^\s*sput-object\s+[vp]\d+,\s*(L\S+)\s*$")
HEADER = re.compile(r"^\.method\s+(?:\S+\s+)*([A-Za-z_$<>][A-Za-z0-9_$<>]*)\(([^()\r\n]*)\)(\S+)\s*$")
MAX_FILES = 80000
MAX_SOURCE_BYTES = 4_000_000
MAX_SITES = 30

WRITER_ROUTES = frozenset({
    "UNAVAILABLE", "NO_OEM_WRITER", "REMOVED_PROVIDER_WRITER",
    "RETAINED_APPLICATION_WRITER", "RETAINED_PROVIDER_WRITER",
    "CLASS_INITIALIZER_WRITER", "OTHER_OEM_WRITER", "MIXED_WRITER_ROUTES"
})
WRITER_RELATIONS = frozenset({
    "UNAVAILABLE", "NO_OEM_WRITER", "WRITER_SET_DIFFERS",
    "WRITER_METHODS_IDENTICAL", "WRITER_EXPECTED_AUTHORITY_ONLY",
    "WRITER_METHODS_DIFFER"
})


class WriterBlocked(ValueError):
    """Only fixed, non-identifying error codes."""


def require(ok, code):
    if not ok:
        raise WriterBlocked(code)


def _receiver_field_from_lines(lines, index, initial):
    """Trace only direct v/p-object moves until the matching sget-object."""
    reg = initial
    seen = set()
    for _ in range(5):
        if reg in seen:
            return None
        seen.add(reg)
        found = None
        for line in reversed(lines[:index]):
            s=line.strip()
            if not s or s.startswith((".line ", ".local ", ".end local ",
                                     ".prologue", ".param ", ".restart local ")):
                continue
            if s.startswith((":","if-","goto","return","throw",
                             "packed-switch","sparse-switch",".catch")):
                return None
            if s.startswith("check-cast"):
                continue
            op=re.match(r"^([a-z][a-z0-9/-]*)\s+([vp]\d+)(?:,|$)",s)
            if not op or op.group(2)!=reg:
                continue
            found=s
            break
        if found is None:
            return None
        match=GET.fullmatch(found)
        if match:
            return match.group(1) if FIELD.fullmatch(match.group(1)) else None
        move=re.fullmatch(r"move-object(?:/[0-9]+)?\s+[vp]\d+,\s*([vp]\d+)",found)
        if move is None:
            return None
        reg=move.group(1)
    return None


def crashing_static_field(source, frame_method, invoked, *, method_blocks, invoke_re):
    """Unique original target callsite -> exact private static field identity."""
    if invoked is None or len(invoked)!=2 or not all(type(q) is str for q in invoked):
        return None
    owner,method=invoked
    blocks=method_blocks(source,frame_method)
    hits=[]
    for block in blocks.values():
        lines=block.splitlines()
        for i,line in enumerate(lines):
            m=invoke_re.fullmatch(line)
            if m and m.group(2)==owner.replace(".","/") and m.group(3)==method:
                regs=m.group(1)
                if ".." in regs:
                    return None
                first=regs.split(",")[0].strip()
                if not re.fullmatch(r"[vp]\d+",first):
                    return None
                hits.append((lines,i,first))
    if len(hits)!=1:
        return None
    return _receiver_field_from_lines(*hits[0])


def writer_methods(root:Path,field:str):
    """Bounded scan of original two OEM Smali directories, not bridge code."""
    require(isinstance(root,Path) and root.is_dir() and
            not root.is_symlink() and type(field) is str and FIELD.fullmatch(field),
            "WRITER_SCAN_SCOPE_INVALID")
    found={}
    count=0
    for dex in ("smali","smali_classes2"):
        parent=root/dex
        require(parent.is_dir() and not parent.is_symlink(),
                "WRITER_SCAN_DEX_ROOT_MISSING")
        for path in parent.rglob("*.smali"):
            count+=1
            require(count<=MAX_FILES, "WRITER_SCAN_TOO_MANY_FILES")
            require(path.is_file() and not path.is_symlink() and
                    path.resolve().is_relative_to(parent.resolve()) and
                    path.stat().st_size<=MAX_SOURCE_BYTES,
                    "WRITER_SCAN_UNSAFE_FILE")
            raw=path.read_text(encoding="utf-8")
            if field not in raw or "sput-object" not in raw:
                continue
            lines=raw.splitlines()
            i=0
            while i<len(lines):
                match=HEADER.fullmatch(lines[i])
                if match is None:
                    i+=1
                    continue
                j=i+1
                while j<len(lines) and lines[j].strip()!=".end method":
                    j+=1
                require(j<len(lines),"WRITER_SCAN_UNTERMINATED_METHOD")
                code="\n".join(lines[i:j+1])+"\n"
                if any((m:=PUT.fullmatch(row)) and m.group(1)==field
                       for row in lines[i:j+1]):
                    signature=match.group(1)+"("+match.group(2)+")"+match.group(3)
                    klass="L"+path.relative_to(parent).with_suffix("").as_posix()+";"
                    key=(klass,signature)
                    require(key not in found, "WRITER_SCAN_DUPLICATE_METHOD")
                    require(len(found)<MAX_SITES,"WRITER_SCAN_TOO_MANY_SITES")
                    found[key]=code
                i=j+1
    return found


def assess_writer_provenance(original, candidate, original_components,
                             candidate_components, rewrite_literals):
    """Private input writer maps + manifest component roles -> closed enums."""
    require(type(original) is dict and type(candidate) is dict and
            type(original_components) in (set,frozenset) and
            type(candidate_components) in (set,frozenset),
            "WRITER_RESULT_INPUT_INVALID")
    if not original and not candidate:
        return "NO_OEM_WRITER","NO_OEM_WRITER"
    if set(original)!=set(candidate):
        relation="WRITER_SET_DIFFERS"
    elif all(original[k]==candidate[k] for k in original):
        relation="WRITER_METHODS_IDENTICAL"
    elif all(rewrite_literals(original[k])[0]==candidate[k] for k in original):
        relation="WRITER_EXPECTED_AUTHORITY_ONLY"
    else:
        relation="WRITER_METHODS_DIFFER"
    roles=set()
    for klass,signature in original:
        name=klass[1:-1].replace("/",".")
        if ("provider",name) in original_components and (
                "provider",name) not in candidate_components:
            roles.add("REMOVED_PROVIDER_WRITER")
        elif ("application",name) in candidate_components:
            roles.add("RETAINED_APPLICATION_WRITER")
        elif ("provider",name) in candidate_components:
            roles.add("RETAINED_PROVIDER_WRITER")
        elif signature.startswith("<clinit>("):
            roles.add("CLASS_INITIALIZER_WRITER")
        else:
            roles.add("OTHER_OEM_WRITER")
    if not roles:
        return "UNAVAILABLE",relation
    result=next(iter(roles)) if len(roles)==1 else "MIXED_WRITER_ROUTES"
    require(result in WRITER_ROUTES and relation in WRITER_RELATIONS,
            "WRITER_RESULT_UNSAFE")
    return result,relation
