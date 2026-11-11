#!/usr/bin/env python3
"""Safe classification of the *global* Android17 top-resumed stock activity.

Dumpsys strings remain private in-process: callers serialize only fixed codes.
A launcher alias can resolve to another Activity, or the app can hand off.
Never accept a per-task mResumedActivity or an old activity-history entry.
"""
import re

PACKAGE = "com.yeelight.cherry"
MAX_SNAPSHOT = 1_000_000
GLOBAL = re.compile(r"^\s*(?:topResumedActivity|ResumedActivity)\s*[:=]\s*(.*?)\s*$")
COMPONENT = re.compile(
    r"(?<![\w.])u0\s+([A-Za-z_][A-Za-z0-9_.]*)/"
    r"((?:\.[A-Za-z_$]|[A-Za-z_$])[A-Za-z0-9_.$]*)(?=\s|})"
)
STATES = frozenset((
    "EXACT_LAUNCHER", "SAME_PACKAGE_OTHER_ACTIVITY", "FOREGROUND_OTHER_PACKAGE",
    "NO_GLOBAL_RESUMED_RECORD", "GLOBAL_RESUMED_UNPARSABLE",
    "GLOBAL_RESUMED_CONFLICT",
))


def inspect_top_resumed(snapshot, launcher):
    """Return a fixed state and booleans, never private Activity class names."""
    if type(snapshot) is not str or len(snapshot) > MAX_SNAPSHOT:
        state = "GLOBAL_RESUMED_UNPARSABLE"
    elif not isinstance(launcher, str) or not launcher.startswith(PACKAGE + "."):
        state = "GLOBAL_RESUMED_UNPARSABLE"
    else:
        records = []
        for line in snapshot.splitlines():
            match = GLOBAL.fullmatch(line)
            if match is None:
                continue
            payload = match.group(1)
            if not payload.startswith("ActivityRecord{") or not payload.endswith("}"):
                records.append(None)
                continue
            matches = COMPONENT.findall(payload)
            if len(matches) != 1 or "\n" in payload:
                records.append(None)
                continue
            package, name = matches[0]
            if name.startswith("."):
                name = package + name
            records.append((package, name))
        if not records:
            state = "NO_GLOBAL_RESUMED_RECORD"
        elif any(row is None for row in records):
            state = "GLOBAL_RESUMED_UNPARSABLE"
        elif len(set(records)) != 1:
            state = "GLOBAL_RESUMED_CONFLICT"
        elif records[0][0] != PACKAGE:
            state = "FOREGROUND_OTHER_PACKAGE"
        elif records[0][1] == launcher:
            state = "EXACT_LAUNCHER"
        else:
            state = "SAME_PACKAGE_OTHER_ACTIVITY"
    return {
        "category": state,
        "same_package_top_resumed": state in (
            "EXACT_LAUNCHER", "SAME_PACKAGE_OTHER_ACTIVITY"),
        "exact_launcher_top_resumed": state == "EXACT_LAUNCHER",
    }
