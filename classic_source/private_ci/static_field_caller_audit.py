#!/usr/bin/env python3
"""Private bounded direct-call differential for OEM static writer methods.

Run only under the existing owner-only exact-APK protected emulator gate.
This pure parser returns private class names only to the in-memory caller;
the public report contains fixed enum categories, never Smali or OEM symbols.
Direct calls are syntactic evidence, NOT proof that a call executed. Reflection,
callbacks, inherited dispatch, and dynamic conditions remain unobservable.
"""
from __future__ import annotations
from pathlib import Path
import re
from static_field_writer_audit import (
    HEADER, MAX_FILES, MAX_SOURCE_BYTES, MAX_SITES, WriterBlocked, require,
)

CALLER_ROUTES = frozenset({
    "UNAVAILABLE", "NO_DIRECT_CALLERS", "REMOVED_PROVIDER_CALLER",
    "RETAINED_APPLICATION_CALLER", "RETAINED_PROVIDER_CALLER",
    "RETAINED_ACTIVITY_CALLER", "CLASS_INITIALIZER_CALLER",
    "OTHER_OEM_CALLER", "MIXED_CALLER_ROUTES",
})
CALLER_RELATIONS = frozenset({
    "UNAVAILABLE", "NO_DIRECT_CALLERS", "CALLER_SET_DIFFERS",
    "CALLER_METHODS_IDENTICAL", "CALLER_EXPECTED_AUTHORITY_ONLY",
    "CALLER_METHODS_DIFFER",
})
CALLER_CONTEXTS = frozenset({
    "UNAVAILABLE", "NO_DIRECT_CALLERS", "IDENTITY_REFERENCE_AND_BRANCH",
    "IDENTITY_REFERENCE_ONLY", "BRANCH_PRESENT", "NO_LOCAL_BRANCH",
})
INVOKE = re.compile(
    r"^\s*invoke-(?:static|virtual|direct|super|interface)(?:/range)?"
    r"\s+\{[^}\r\n]{0,512}\},\s*(L\S+;->[A-Za-z_$<>][A-Za-z0-9_$<>]*"
    r"\([^()\r\n]*\)\S+)\s*$"
)
BRANCH = re.compile(r"^\s*(?:if-|goto|packed-switch|sparse-switch)")
IDENTITY_MARKERS = ("getPackageName(", "com.yeelight.cherry")
MAX_CALLERS = 120


def direct_writer_callers(root: Path, writers: dict):
    """Exact invoke method references in original two decoded OEM DEX trees.

    Returned class/method/source data may not be logged, published or retained.
    """
    require(isinstance(root, Path) and root.is_dir() and not root.is_symlink()
            and type(writers) is dict and 0 < len(writers) <= MAX_SITES
            and all(type(k) is tuple and len(k) == 2 and
                    all(type(s) is str for s in k) for k in writers),
            "WRITER_CALLER_SCOPE_INVALID")
    targets = {klass + "->" + signature for klass, signature in writers}
    require(all(re.fullmatch(
        r"L[A-Za-z0-9_/$]+;->[A-Za-z_$<>][A-Za-z0-9_$<>]*"
        r"\([^()\r\n]*\)\S+", target) for target in targets),
        "WRITER_CALLER_TARGET_INVALID")
    callers = {}
    count = 0
    for dex in ("smali", "smali_classes2"):
        parent = root / dex
        require(parent.is_dir() and not parent.is_symlink(),
                "WRITER_CALLER_DEX_ROOT_MISSING")
        for path in parent.rglob("*.smali"):
            count += 1
            require(count <= MAX_FILES, "WRITER_CALLER_TOO_MANY_FILES")
            require(path.is_file() and not path.is_symlink()
                    and path.resolve().is_relative_to(parent.resolve())
                    and path.stat().st_size <= MAX_SOURCE_BYTES,
                    "WRITER_CALLER_UNSAFE_FILE")
            raw = path.read_text(encoding="utf-8")
            if not any(target in raw for target in targets):
                continue
            lines = raw.splitlines()
            i = 0
            while i < len(lines):
                match = HEADER.fullmatch(lines[i])
                if match is None:
                    i += 1
                    continue
                j = i + 1
                while j < len(lines) and lines[j].strip() != ".end method":
                    j += 1
                require(j < len(lines), "WRITER_CALLER_UNTERMINATED_METHOD")
                if any((m := INVOKE.fullmatch(row)) and m.group(1) in targets
                       for row in lines[i:j+1]):
                    signature = match.group(1)+"("+match.group(2)+")"+match.group(3)
                    klass = "L"+path.relative_to(parent).with_suffix("").as_posix()+";"
                    key = (klass, signature)
                    require(key not in callers, "WRITER_CALLER_DUPLICATE_METHOD")
                    require(len(callers) < MAX_CALLERS, "WRITER_CALLER_TOO_MANY_SITES")
                    callers[key] = "\n".join(lines[i:j+1])+"\n"
                i = j+1
    return callers


def assess_direct_writer_callers(original: dict, candidate: dict,
                                 original_components: set,
                                 candidate_components: set, rewrite_literals):
    """Closed route/code-parity/local-branch categories, never execution proof."""
    require(type(original) is dict and type(candidate) is dict
            and type(original_components) in (set, frozenset)
            and type(candidate_components) in (set, frozenset),
            "WRITER_CALLER_INPUT_INVALID")
    if not original and not candidate:
        return "NO_DIRECT_CALLERS", "NO_DIRECT_CALLERS", "NO_DIRECT_CALLERS"
    if set(original) != set(candidate):
        relation = "CALLER_SET_DIFFERS"
    elif all(original[k] == candidate[k] for k in original):
        relation = "CALLER_METHODS_IDENTICAL"
    elif all(rewrite_literals(original[k])[0] == candidate[k] for k in original):
        relation = "CALLER_EXPECTED_AUTHORITY_ONLY"
    else:
        relation = "CALLER_METHODS_DIFFER"
    routes = set()
    has_branch = False
    has_identity = False
    for (klass, signature), code in original.items():
        name = klass[1:-1].replace("/", ".")
        if ("provider", name) in original_components and (
                "provider", name) not in candidate_components:
            routes.add("REMOVED_PROVIDER_CALLER")
        elif ("application", name) in candidate_components:
            routes.add("RETAINED_APPLICATION_CALLER")
        elif ("provider", name) in candidate_components:
            routes.add("RETAINED_PROVIDER_CALLER")
        elif ("activity", name) in candidate_components:
            routes.add("RETAINED_ACTIVITY_CALLER")
        elif signature.startswith("<clinit>("):
            routes.add("CLASS_INITIALIZER_CALLER")
        else:
            routes.add("OTHER_OEM_CALLER")
        has_branch |= any(BRANCH.match(line) for line in code.splitlines())
        has_identity |= any(word in code for word in IDENTITY_MARKERS)
    route = ("UNAVAILABLE" if not routes else
             next(iter(routes)) if len(routes) == 1 else "MIXED_CALLER_ROUTES")
    context = ("IDENTITY_REFERENCE_AND_BRANCH" if has_identity and has_branch else
               "IDENTITY_REFERENCE_ONLY" if has_identity else
               "BRANCH_PRESENT" if has_branch else
               "NO_LOCAL_BRANCH" if routes else "UNAVAILABLE")
    require(route in CALLER_ROUTES and relation in CALLER_RELATIONS and
            context in CALLER_CONTEXTS, "WRITER_CALLER_OUTPUT_UNSAFE")
    return route, relation, context
