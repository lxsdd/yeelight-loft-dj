#!/usr/bin/env python3
"""Offline, owner-only diagnosis of a verified installed Classic clone's load failure.

Only constant status labels, booleans and bounded integer buckets may leave the
protected runner. Private OEM class names, raw DEX, APKs, logcat and paths must
never be written to stdout, uploaded or incorporated into public test fixtures.

A class_def's existence is not ART loadability. Class hierarchy checks are
static, and an Android PackageManager verify command is not a class-load proof.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile, BadZipFile

PACKAGE = "io.lxsdd.yeelightdj"
ORIGINAL = "com.yeelight.cherry"
EXPECTED_DEX = {"classes.dex", "classes2.dex", "classes3.dex"}
ANDROID_PREFIXES = (
    "Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lcom/android/",
    "Lorg/json/", "Lorg/xmlpull/", "Lorg/w3c/dom/", "Lorg/xml/sax/",
    "Lorg/apache/http/",
)
DESCRIPTOR = re.compile(r"L(?:[A-Za-z_$][\w$]*/)+[A-Za-z_$][\w$]*;\Z")
MAX_CLASSES = 150000
MAX_LOG = 2_000_000
MAX_WALK = 4096


class ForensicsBlocked(ValueError):
    """Public allowlisted rejection codes only."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise ForensicsBlocked(code)


def bucket(n: int) -> str:
    require(type(n) is int and n >= 0, "COUNTER_INVALID")
    if n == 0:
        return "NONE"
    if n == 1:
        return "ONE"
    if n <= 3:
        return "TWO_TO_THREE"
    if n <= 15:
        return "FOUR_TO_FIFTEEN"
    return "SIXTEEN_OR_MORE"


def summarize_linkage(original: dict, candidate: dict, origin_map: dict,
                      target: str) -> dict:
    """Static super/interface closure of the *exact* ClassNotFoundException name.

    Graphs include original and clone class_defs. Definitions can resolve via
    platform/framework or libraries not represented here; only differences
    between graphs and direct unresolved dependencies are meaningful evidence.
    """
    require(type(original) is dict and type(candidate) is dict and
            type(origin_map) is dict and type(target) is str,
            "CLASS_GRAPH_INVALID")
    require(len(original) <= MAX_CLASSES and len(candidate) <= MAX_CLASSES and
            set(origin_map) == set(candidate), "CLASS_GRAPH_INVALID")
    for graph in (original, candidate):
        for cls, parents in graph.items():
            require(type(cls) is str and DESCRIPTOR.fullmatch(cls) and
                    type(parents) in (list, tuple) and len(parents) <= 512,
                    "CLASS_GRAPH_INVALID")
            for parent in parents:
                require(type(parent) is str and DESCRIPTOR.fullmatch(parent),
                        "CLASS_GRAPH_INVALID")
    require(DESCRIPTOR.fullmatch(target) is not None, "TARGET_INVALID")
    for source in origin_map.values():
        require(source in ("PRIMARY_OEM_DEX", "SECONDARY_OEM_DEX", "NATIVE_BRIDGE_DEX"),
                "ORIGIN_INVALID")
    target_source = origin_map.get(target, "ABSENT")

    def missing_parents(graph):
        if target not in graph:
            return "TARGET_NOT_DEFINED", "TARGET_NOT_DEFINED", "TARGET_NOT_DEFINED", frozenset()
        seen, pending, absent, platform = set(), [target], set(), set()
        while pending:
            cls = pending.pop()
            if cls in seen:
                continue
            seen.add(cls)
            require(len(seen) <= MAX_WALK, "CLASS_GRAPH_TOO_LARGE")
            for parent in graph[cls]:
                if parent in graph:
                    pending.append(parent)
                elif parent.startswith(ANDROID_PREFIXES):
                    platform.add(parent)
                else:
                    absent.add(parent)
        direct = [p for p in graph[target]
                  if p not in graph and not p.startswith(ANDROID_PREFIXES)]
        return bucket(len(direct)), bucket(len(absent)), bucket(len(platform)), frozenset(absent)

    orig_direct, orig_transitive, orig_platform, orig_absent = missing_parents(original)
    candidate_direct, candidate_transitive, candidate_platform, clone_absent = missing_parents(candidate)
    orig_parents = original.get(target)
    clone_parents = candidate.get(target)
    return {
        "status": "STATIC_CLASS_LINKAGE_EVIDENCE_ONLY",
        "original_target_definition": "PRESENT" if orig_parents is not None else "ABSENT",
        "clone_target_definition": "PRESENT" if clone_parents is not None else "ABSENT",
        "clone_target_origin": target_source,
        "target_parents_preserved": orig_parents == clone_parents if
            orig_parents is not None and clone_parents is not None else False,
        "original_direct_unresolved_parent_bucket": orig_direct,
        "clone_direct_unresolved_parent_bucket": candidate_direct,
        "original_transitive_unresolved_parent_bucket": orig_transitive,
        "clone_transitive_unresolved_parent_bucket": candidate_transitive,
        "clone_transitive_platform_assumed_bucket": candidate_platform,
        "original_transitive_platform_assumed_bucket": orig_platform,
        "clone_graph_has_new_missing_parent": bool(clone_absent - orig_absent),
        "static_dex_definition_proves_loadability": False,
        "art_class_loading_verified": False,
    }


def target_crash_descriptor(logcat: str) -> tuple[str, str | None]:
    """Reuse target-process scoping and AndroidRuntime-only cause recognition.

    The target name is used only in memory, never serialized. A missing class
    without a scoped target FATAL is an untrusted input and cannot be analyzed.
    """
    require(type(logcat) is str and len(logcat) <= MAX_LOG,
            "CRASH_LOG_INVALID")
    from android17_start_smoke import (
        classify_crash, ROOT_CAUSE, _android_runtime_message,
        MISSING_QUOTED, MISSING_DIRECT, MAX_FATAL_BLOCK_LINES,
    )
    crash = classify_crash(logcat)
    if not crash["target_crash"]:
        return "NO_TARGET_FATAL", None
    if crash["exception_category"] != "CLASS_LOADER_MISSING":
        return "TARGET_FATAL_NOT_CLASS_LOADER", None
    lines = logcat.splitlines()
    for pos, row in enumerate(lines):
        if "FATAL EXCEPTION" not in row:
            continue
        block = []
        for r in lines[pos + 1:pos + 1 + MAX_FATAL_BLOCK_LINES]:
            if "FATAL EXCEPTION" in r:
                break
            block.append(r)
        process = re.search(r"\bProcess:\s*([A-Za-z0-9_.:]+)\s*,\s*PID:",
                            "\n".join(block[:14]))
        if process is None or not (process.group(1) == PACKAGE or
                                   process.group(1).startswith(PACKAGE + ":")):
            continue
        names = set()
        for b in block:
            msg = _android_runtime_message(b)
            root = ROOT_CAUSE.match(msg)
            if root is not None and root.group(2).rsplit(".", 1)[-1] == "ClassNotFoundException":
                missing = MISSING_QUOTED.search(msg) or MISSING_DIRECT.search(msg)
                if missing is not None:
                    names.add("L" + missing.group(1).replace(".", "/") + ";")
        if len(names) != 1:
            return "MISSING_CLASS_AMBIGUOUS_OR_UNPARSEABLE", None
        target = names.pop()
        require(DESCRIPTOR.fullmatch(target) is not None, "TARGET_INVALID")
        return "TARGET_CLASS_IDENTIFIED_PRIVATELY", target
    return "NO_TARGET_FATAL", None


def load_dex_graphs(original: Path, candidate: Path) -> tuple[dict, dict, dict]:
    """Only read private archives, verify complete DEX member sets and hashes."""
    # Python invokes this file as classic_source/private_ci/<script>.py from
    # the repository root. The sibling classic_source modules are not on
    # sys.path unless resolved explicitly. Do not rely on PYTHONPATH or tests
    # that inject synthetic modules into sys.modules.
    try:
        source_root = str(Path(__file__).resolve().parents[1])
        if source_root not in sys.path:
            sys.path.insert(0, source_root)
        from private_startup_audit import dex_index
        with ZipFile(original) as stock, ZipFile(candidate) as clone:
            def names(z):
                n = z.namelist()
                require(len(n) == len(set(n)), "ZIP_DUPLICATE_MEMBER")
                return {a for a in n if re.fullmatch(r"classes(?:[2-9]\d*)?\.dex", a)}
            require(names(stock) == {"classes.dex", "classes2.dex"} and
                    names(clone) == EXPECTED_DEX, "DEX_MEMBER_SET_INVALID")
            original_graph, clone_graph, origins = {}, {}, {}
            for archive, dest, is_clone in ((stock, original_graph, False),
                                            (clone, clone_graph, True)):
                for member in ("classes.dex", "classes2.dex", "classes3.dex"):
                    if member == "classes3.dex" and not is_clone:
                        continue
                    classes = dex_index(archive.read(member))
                    require(not (set(classes) & set(dest)), "DUPLICATE_CLASS_DEF")
                    dest.update(classes)
                    if is_clone:
                        label = {"classes.dex": "PRIMARY_OEM_DEX",
                                 "classes2.dex": "SECONDARY_OEM_DEX",
                                 "classes3.dex": "NATIVE_BRIDGE_DEX"}[member]
                        origins.update({name: label for name in classes})
            return original_graph, clone_graph, origins
    except ForensicsBlocked:
        raise
    except ImportError:
        raise ForensicsBlocked("PRIVATE_GRAPH_MODULE_UNAVAILABLE") from None
    except (OSError, BadZipFile, KeyError, RuntimeError, ValueError):
        raise ForensicsBlocked("PRIVATE_GRAPH_UNREADABLE") from None


def verify_compiler(serial: str) -> str:
    """One post-crash, offline emulator-only ART verification request.

    It is not a substitute for actually loading a class inside the app process.
    No compiler/log output is forwarded. Changes occur only inside disposable
    emulator after the original launch has already failed.
    """
    require(serial == "emulator-5554", "EMULATOR_SCOPE_INVALID")
    try:
        completed = subprocess.run(
            ["adb", "-s", serial, "shell", "cmd", "package", "compile", "-m",
             "verify", "-f", PACKAGE], stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return "ART_VERIFY_COMMAND_UNAVAILABLE"
    if len(completed.stdout) > 50000 or len(completed.stderr) > 50000:
        return "ART_VERIFY_COMMAND_UNTRUSTED_OUTPUT"
    return ("ART_VERIFY_COMMAND_ACCEPTED" if completed.returncode == 0 and
            b"Success" in completed.stdout else "ART_VERIFY_COMMAND_REJECTED")


# Catch unexpected private-input failures at their exact diagnostic phase.
# Never serialize exception text, stack frames, proprietary class names, or
# file paths. Existing explicit fail-closed ForensicsBlocked labels survive.
SAFE_STAGE_ERRORS = frozenset({
    "TARGET_CRASH_CLASSIFICATION_FAILED",
    "PRIVATE_GRAPH_ANALYSIS_FAILED",
    "CLASS_LINKAGE_ANALYSIS_FAILED",
    "ART_VERIFY_EXECUTION_FAILED",
})


def stage_guard(code: str, operation):
    require(code in SAFE_STAGE_ERRORS, "STAGE_CODE_INVALID")
    try:
        return operation()
    except ForensicsBlocked:
        raise
    except Exception:
        raise ForensicsBlocked(code) from None


def safe_main(args) -> dict:
    temp = os.environ.get("RUNNER_TEMP", "")
    require(os.environ.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj" and
            os.environ.get("GITHUB_ACTOR") == "lxsdd" and
            os.environ.get("GITHUB_REF") == "refs/heads/main" and
            os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
            os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and
            bool(temp), "OWNER_ONLY_RUNNER_REQUIRED")
    root = Path(temp)
    require(args.serial == "emulator-5554", "EMULATOR_SCOPE_INVALID")
    require(args.original == root / "private-original/com.yeelight.cherry_3.5.4.apk" and
            args.candidate == root / "private-output/classic-loft-dj-TEST-SIGNED.apk" and
            args.original.is_file() and not args.original.is_symlink() and
            args.candidate.is_file() and not args.candidate.is_symlink(),
            "PRIVATE_APK_SCOPE_INVALID")
    try:
        result = subprocess.run(["adb", "-s", args.serial, "logcat", "-b", "crash", "-d"],
                                capture_output=True, timeout=25, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ForensicsBlocked("CRASH_CAPTURE_UNAVAILABLE") from None
    require(result.returncode == 0 and len(result.stdout) <= MAX_LOG and
            not result.stderr, "CRASH_CAPTURE_INVALID")
    try:
        logcat = result.stdout.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise ForensicsBlocked("CRASH_ENCODING_INVALID") from None
    status, target = stage_guard(
        "TARGET_CRASH_CLASSIFICATION_FAILED",
        lambda: target_crash_descriptor(logcat))
    if target is None:
        return {"status": "PRIVATE_CLASSLOADER_FORENSICS_NOT_APPLICABLE",
                "target_evidence": status, "art_class_loading_verified": False}
    original, candidate, origin = stage_guard(
        "PRIVATE_GRAPH_ANALYSIS_FAILED",
        lambda: load_dex_graphs(args.original, args.candidate))
    evidence = stage_guard(
        "CLASS_LINKAGE_ANALYSIS_FAILED",
        lambda: summarize_linkage(original, candidate, origin, target))
    art = stage_guard(
        "ART_VERIFY_EXECUTION_FAILED",
        lambda: verify_compiler(args.serial))
    return {
        "status": "PRIVATE_CLASSLOADER_FORENSICS_DIAGNOSTIC_ONLY",
        "target_evidence": status,
        "linkage": evidence,
        "post_crash_art_verify_command": art,
        "no_private_payload_published": True,
        "art_class_loading_verified": False,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Protected Classic classloader evidence only")
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args(argv)
    try:
        report = safe_main(args)
    except ForensicsBlocked as exc:
        print(json.dumps({"status": "PRIVATE_CLASSLOADER_FORENSICS_BLOCKED",
                          "code": str(exc)}))
        return 2
    except Exception:
        # Avoid the default Python traceback: it could contain private paths.
        print(json.dumps({"status": "PRIVATE_CLASSLOADER_FORENSICS_BLOCKED",
                          "code": "UNEXPECTED_DIAGNOSTIC_FAILURE"}))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
