#!/usr/bin/env python3
"""Fixed-vocabulary private Classic build diagnostics, never OEM data.

The trusted owner-only run redirects all raw build output into an ephemeral
runner-local file and deletes it. This helper writes only an allowlisted
phase and exception *category* to GitHub's job summary, guarded by the
original protected workflow identity and a runner-temporary summary path.
It never serializes exception text, original symbols, stack, commands, paths
or app/device/account values.
"""
from __future__ import annotations

import os
from pathlib import Path

PHASES = frozenset((
    "PRECONDITION", "ORIGINAL_PIN", "TOOLCHAIN_PIN", "RESOURCE_DECODE",
    "ANDROID_BRIDGE", "BRIDGE_COLLISION", "STAGE_ORIGINAL",
    "ORIGINAL_SMALI_DECODE", "OPTIONAL_UI_AUDIT", "OEM_SMALI_REASSEMBLY",
    "APKTOOL_REBUILD", "MULTIDEX_LAYOUT", "UNSIGNED_PARITY",
    "FINAL_COMPONENT_GATES", "PRIVATE_OUTPUT_COPY", "RECEIPT_WRITTEN",
))
UI_FAILURE_CODES = frozenset((
    "UI_DECODED_ROOT_INVALID", "UI_XML_INPUT_INVALID",
    "UI_XML_DECLARATION_DENIED", "UI_XML_PARSE_INVALID",
    "UI_NOT_ORIGINAL_MANIFEST", "UI_ORIGINAL_APPLICATION_MISSING",
    "UI_LAYOUT_DIRECTORY_INVALID", "UI_LAYOUT_FILE_INVALID",
    "UI_LAYOUT_COUNT_LIMIT", "UI_LAYOUT_NODE_LIMIT",
    "UI_SMALI_FILE_INVALID", "UI_SMALI_SCAN_LIMIT",
    "UI_SMALI_DUPLICATE_ORIGINAL_CLASS", "UI_SMALI_ROLE_LIMIT",
))
CATEGORIES = frozenset((
    "EXPECTED_BUILD_GATE", "DEX_VALIDATION", "TOOLCHAIN",
    "PROCESS_FAILURE", "FILESYSTEM_FAILURE", "ORIGINAL_UI_AUDIT",
    "UNEXPECTED_BUILD_EXCEPTION",
))
_current_phase = "PRECONDITION"

def phase(value: str) -> None:
    global _current_phase
    if value not in PHASES:
        raise ValueError("UNKNOWN_PRIVATE_BUILD_PHASE")
    _current_phase = value

def summary_path(env=None) -> Path | None:
    env = os.environ if env is None else env
    if not (
        env.get("GITHUB_REPOSITORY") == "lxsdd/yeelight-loft-dj"
        and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
        and env.get("GITHUB_REF") == "refs/heads/main"
        and env.get("GITHUB_ACTOR") == "lxsdd"
        and env.get("RUNNER_ENVIRONMENT") == "github-hosted"
        and env.get("RUNNER_TEMP")
        and env.get("GITHUB_STEP_SUMMARY")
    ):
        return None
    try:
        root = Path(env["RUNNER_TEMP"]).resolve(strict=True)
        path = Path(env["GITHUB_STEP_SUMMARY"])
        # GitHub-hosted temp/_runner_file_commands/step_summary_xxx.
        # Exclude arbitrary env-supplied absolute targets and symlink files.
        if (not path.is_absolute() or path.is_symlink() or
            not path.parent.is_dir() or
            not path.parent.resolve(strict=True).is_relative_to(root) or
            not path.name.startswith("step_summary_")):
            return None
        return path
    except (OSError, ValueError, RuntimeError):
        return None

def safe_ui_reason(reason: object) -> str:
    return reason if type(reason) is str and reason in UI_FAILURE_CODES else "UI_ANALYSIS_RUNTIME_UNAVAILABLE"

def category(error: BaseException) -> str:
    # Exception names only. Never format an untrusted exception argument.
    name = type(error).__name__
    return {
        "GateFailure": "EXPECTED_BUILD_GATE",
        "DexRemapError": "DEX_VALIDATION",
        "DexTransportBlocked": "DEX_VALIDATION",
        "ToolError": "TOOLCHAIN",
        "CalledProcessError": "PROCESS_FAILURE",
        "TimeoutExpired": "PROCESS_FAILURE",
        "FileNotFoundError": "FILESYSTEM_FAILURE",
        "PermissionError": "FILESYSTEM_FAILURE",
        "OSError": "FILESYSTEM_FAILURE",
        "UiAuditBlocked": "ORIGINAL_UI_AUDIT",
    }.get(name, "UNEXPECTED_BUILD_EXCEPTION")

def _append(lines: tuple[str, ...], env=None) -> bool:
    path = summary_path(env)
    if path is None:
        return False
    # Caller passes exclusively fixed vocabulary. No dynamic original data.
    try:
        flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        fd = os.open(path, flags, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as output:
            output.write("### Classic private build diagnostics (no OEM data)\n")
            for line in lines:
                output.write("- " + line + "\n")
        return True
    except OSError:
        return False

def record_failure(error: BaseException, env=None) -> bool:
    phase_name = _current_phase if _current_phase in PHASES else "PRECONDITION"
    cause = category(error)
    if cause not in CATEGORIES:
        cause = "UNEXPECTED_BUILD_EXCEPTION"
    reason = (safe_ui_reason(str(error)) if cause == "ORIGINAL_UI_AUDIT"
              else "NOT_EMITTED")
    return _append((f"Last attempted phase: `{phase_name}`",
                    f"Failure category: `{cause}`",
                    f"UI error code: `{reason}`",
                    "Raw build log/OEM bytes: `NOT_PUBLISHED`"), env)

def record_optional_ui_unavailable(reason: object, env=None) -> bool:
    return _append((
        "Optional original UI inventory: `UNAVAILABLE_NOT_ACCEPTED`",
        f"Optional UI cause: `{safe_ui_reason(reason)}`",
        "OEM Music/UI proof: `NOT_VERIFIED`",
        "Raw original classes/labels: `NOT_PUBLISHED`",
    ), env)
