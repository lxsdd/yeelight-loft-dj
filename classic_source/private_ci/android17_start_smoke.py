#!/usr/bin/env python3
"""Private, offline emulator-only startup verification for the native Classic fork.

No OEM APK, vendor symbols, private paths, raw logcat, identities or credentials
are emitted. This tool never signs, installs, publishes, or changes any lamp.
The active probe is strictly opt-in for an owner-approved PRIVATE emulator;
public/source CI should only exercise the pure evidence classifier with fixtures.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from page_size_warning import (
    read_warning_action, read_framework_resources, inspect_app_overlay_readonly
)

PACKAGE = "io.lxsdd.yeelightdj"
ORIGINAL_PACKAGE = "com.yeelight.cherry"  # private identity-only offline A/B control
DJ_ACTIVITY = "io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity"
MAX_TEXT = 2_000_000
SERIAL = re.compile(r"emulator-[0-9]{4,5}\Z")
COMPONENT = re.compile(r"[A-Za-z_][\w.]*/[A-Za-z_][\w.$]*\Z")
PIN = re.compile(r"[0-9a-f]{64}\Z")
APK_PATH = re.compile(r"/data/app/[-A-Za-z0-9_@+~/.=]+/base\.apk\Z")
EXCEPTIONS = (
    ("NoClassDefFoundError", "MISSING_RUNTIME_DEFINITION"),
    ("ClassNotFoundException", "CLASS_LOADER_MISSING"),
    ("InflateException", "LAYOUT_INFLATION"),
    ("ClassCastException", "TYPE_CONTRACT"),
    ("SecurityException", "PERMISSION_CONTRACT"),
    ("ForegroundServiceStartNotAllowedException", "FOREGROUND_SERVICE_RESTRICTED"),
    ("IllegalStateException", "INVALID_RUNTIME_STATE"),
)

class SmokeBlocked(ValueError):
    """Only static public error codes, never original log fragments."""


def _bounded(text, code):
    if type(text) is not str or len(text) > MAX_TEXT:
        raise SmokeBlocked(code)
    return text


def _component(text):
    if type(text) is not str or not COMPONENT.fullmatch(text):
        raise SmokeBlocked("SMOKE_INVALID_COMPONENT")
    package, activity = text.split("/", 1)
    if package != PACKAGE or not activity.startswith(("com.yeelight.", PACKAGE + ".", "io.github.lxsdd.yeelightclassicdj.")):
        # No arbitrary packages or external activities may be launched.
        raise SmokeBlocked("SMOKE_COMPONENT_OUT_OF_SCOPE")
    return text


# Only global Android17 top-resumed ActivityRecords qualify real foreground.
# A per-task mResumedActivity or historic ActivityRecord is not evidence.
GLOBAL_TOP = re.compile(r"^\s*(?:topResumedActivity|ResumedActivity)\s*[:=]\s*(.*?)\s*$")
TOP_COMPONENT = re.compile(
    r"(?<![\w.])u0\s+([A-Za-z_][A-Za-z0-9_.]*)/"
    r"((?:\.[A-Za-z_$]|[A-Za-z_$])[A-Za-z0-9_.$]*)(?=\s|})"
)


def _global_top(snapshot):
    """Return fixed state and canonical component internally; never serialize OEM names."""
    rows = []
    for line in _bounded(snapshot, "SMOKE_BAD_ACTIVITY_SNAPSHOT").splitlines():
        match = GLOBAL_TOP.fullmatch(line)
        if match is None:
            continue
        payload = match.group(1)
        if not payload.startswith("ActivityRecord{") or not payload.endswith("}"):
            return "UNPARSABLE", None
        parts = TOP_COMPONENT.findall(payload)
        if len(parts) != 1:
            return "UNPARSABLE", None
        package, cls = parts[0]
        if cls.startswith("."):
            cls = package + cls
        rows.append((package, cls))
    if not rows:
        return "NO_GLOBAL_TOP", None
    if len(set(rows)) != 1:
        return "CONFLICTING_GLOBAL_TOP", None
    package, cls = rows[0]
    if package != PACKAGE:
        return "FOREIGN_GLOBAL_TOP", None
    return "CLONE_GLOBAL_TOP", cls


def _resumed(snapshot, component):
    """Strict exact native DJ foreground proof; no per-task stale-stack matches."""
    package, activity = component.split("/", 1)
    category, top = _global_top(snapshot)
    return category == "CLONE_GLOBAL_TOP" and package == PACKAGE and top == activity


def _classic_foreground(snapshot):
    category, top = _global_top(snapshot)
    if category != "CLONE_GLOBAL_TOP":
        return False, category
    if not top.startswith("com.yeelight."):
        # A native DJ activity, random injected clone component or fallback
        # launcher does not prove the ORIGINAL Classic screen.
        return False, "NON_CLASSIC_TOP"
    return True, "SAME_CLONE_ORIGINAL_ACTIVITY"


# Logcat may nest the *real* Caused by after dozens of AndroidRuntime
# framework stack frames. The old 14-line window hid that exception and
# reported OTHER_FATAL_RUNTIME. Never serialize OEM/stack text into reports.
ROOT_CAUSE = re.compile(
    r"^\s*(Caused by:\s*)?([A-Za-z_$][\w.$]*(?:Exception|Error))(?::|$)"
)

# adb logcat -b crash -d is normally formatted (threadtime), not raw.
# Only strip an exact AndroidRuntime record prefix: a similarly worded
# message from another tag is never allowed to inject an exception category.
ANDROID_RUNTIME_THREADTIME = re.compile(
    r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3,9}\s+"
    r"\d+\s+\d+\s+[A-Z]\s+AndroidRuntime\s*:\s*(.*)$"
)
ANDROID_RUNTIME_BRIEF = re.compile(
    r"^[A-Z]/AndroidRuntime\(\s*\d+\s*\):\s*(.*)$"
)
ANDROID_RUNTIME_TIME = re.compile(
    r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3,9}\s+"
    r"[A-Z]/AndroidRuntime\(\s*\d+\s*\):\s*(.*)$"
)


def _android_runtime_message(row):
    for grammar in (ANDROID_RUNTIME_THREADTIME, ANDROID_RUNTIME_BRIEF,
                    ANDROID_RUNTIME_TIME):
        matched = grammar.fullmatch(row)
        if matched is not None:
            return matched.group(1).lstrip()
    return row


MAX_FATAL_BLOCK_LINES = 256
NESTED_EXCEPTION_TYPES = (
    ("NoSuchMethodError", "METHOD_LINKAGE"),
    ("NoSuchFieldError", "FIELD_LINKAGE"),
    ("VerifyError", "DEX_VERIFICATION"),
    ("IncompatibleClassChangeError", "CLASS_LINKAGE_INCOMPATIBLE"),
    ("IllegalAccessError", "CLASS_MEMBER_ACCESS"),
    ("AbstractMethodError", "ABSTRACT_DISPATCH"),
    ("UnsatisfiedLinkError", "NATIVE_LIBRARY_LINKAGE"),
    ("ExceptionInInitializerError", "STATIC_INITIALIZER"),
    ("Resources$NotFoundException", "RESOURCE_REFERENCE"),
    ("SQLiteException", "SQLITE_INIT"),
    ("NullPointerException", "NULL_DEREFERENCE"),
    ("IllegalArgumentException", "ARGUMENT_CONTRACT"),
)



# Privacy-safe, exact candidate provider evidence. We extract names only inside
# the disposable private runner; reports contain fixed enums and booleans.
# A class not in the candidate DEX is distinct from a runtime loader failure
# for a class that *is* defined; neither is silently treated as a repair.
JAVA_CLASS = r"(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*"
PROVIDER_FAILURE = re.compile(
    r"\bUnable to (?:get|instantiate|create) provider\s+(" + JAVA_CLASS + r")(?=[:\s])"
)
PROVIDER_WRAPPER = re.compile(r"\bUnable to (?:get|instantiate|create) provider\b")
DEX_PATH_ZIP = re.compile(r'\bzip file "([^"\n]+\.apk)"')
MISSING_QUOTED = re.compile(
    r'\bClassNotFoundException:\s*Didn.t find class\s+"(' + JAVA_CLASS + r')"'
)
MISSING_DIRECT = re.compile(
    r"\bClassNotFoundException:\s*(" + JAVA_CLASS + r")(?=\s|$)"
)


def _runtime_namespace(name):
    if name is None:
        return "UNIDENTIFIED"
    if name.startswith("io.github.lxsdd.yeelightclassicdj."):
        return "NATIVE_DJ_BRIDGE"
    if name.startswith(PACKAGE + "."):
        return "CLONE_APP_NAMESPACE"
    if name.startswith("com.yeelight."):
        return "OEM_CLASS_FAMILY"
    if name.startswith(("android.", "java.", "javax.", "dalvik.")):
        return "PLATFORM_CLASS"
    if name.startswith(("androidx.", "com.google.")):
        return "ANDROID_SUPPORT_OR_GOOGLE_LIBRARY"
    return "OTHER_RUNTIME_LIBRARY"


def _sanitized_classloader_provenance(rows, candidate_graph, installed_apk_path=None):
    """Correlate a *target* provider FATAL with optional signed-APK definitions.

    A recovered name is never part of the return value, even on error. A
    matching provider declaration does not prove runtime reachability or UI.
    """
    provider = missing = None
    path_evidence = []
    provider_wrapper_seen = False
    for row in rows[:MAX_FATAL_BLOCK_LINES]:
        message = _android_runtime_message(row)
        root = ROOT_CAUSE.match(message)
        if root is None:
            continue
        if not root.group(1):
            provider_wrapper_seen |= bool(PROVIDER_WRAPPER.search(message))
        if provider is None and not root.group(1):
            match = PROVIDER_FAILURE.search(message)
            if match is not None:
                provider = match.group(1)
        if root.group(2).rsplit(".", 1)[-1] == "ClassNotFoundException":
            match = MISSING_QUOTED.search(message) or MISSING_DIRECT.search(message)
            if match is not None:
                missing = match.group(1)
            if "on path:" in message:
                path_evidence.extend(DEX_PATH_ZIP.findall(message.split("on path:", 1)[1]))
    match_relation = ("DIRECT_PROVIDER_CLASS" if missing is not None and missing == provider else
                      "PROVIDER_DEPENDENCY_OR_REFLECTION" if missing and provider else
                      "UNIDENTIFIED")
    manifest = dex_provider = dex_missing = "NOT_CHECKED"
    dex_location = "NOT_CHECKED"
    if candidate_graph is not None:
        definitions = candidate_graph["definitions"]
        declared = candidate_graph["manifest_providers"]
        manifest = ("DECLARED" if provider in declared else
                    "NOT_DECLARED" if provider else "UNIDENTIFIED")
        dex_provider = ("PRESENT" if "L" + provider.replace(".", "/") + ";" in definitions else
                        "ABSENT") if provider else "UNIDENTIFIED"
        dex_missing = ("PRESENT" if "L" + missing.replace(".", "/") + ";" in definitions else
                       "ABSENT") if missing else "UNIDENTIFIED"
        if missing:
            descriptor = "L" + missing.replace(".", "/") + ";"
            dex_location = (candidate_graph.get("dex_origin", {}).get(
                descriptor, "NOT_INDEXED" if descriptor in definitions else "ABSENT"))
        else:
            dex_location = "UNIDENTIFIED"
    loader_path = "NOT_CHECKED" if installed_apk_path is None else (
        "NO_APK_PATH_IN_EXCEPTION" if not path_evidence else
        "INSTALLED_CANDIDATE_INCLUDED" if installed_apk_path in path_evidence else
        "OTHER_DEX_SOURCE")
    return {
        "provider_instantiation_relation": match_relation,
        "missing_class_namespace": _runtime_namespace(missing),
        "provider_class_namespace": _runtime_namespace(provider),
        "provider_declared_in_candidate_manifest": manifest,
        "provider_defined_in_candidate_dex": dex_provider,
        "missing_class_defined_in_candidate_dex": dex_missing,
        "missing_class_dex_location": dex_location,
        "provider_wrapper_seen": bool(provider_wrapper_seen),
        "loader_apk_path_relation": loader_path,
        "classloader_provenance": "SIGNED_APK_GRAPH_CORRELATED" if candidate_graph is not None else "LOG_ONLY",
    }


def _private_candidate_graph(path):
    """Read signed APK on same ephemeral owner runner; never print class names."""
    temp = os.environ.get("RUNNER_TEMP")
    if not temp or not isinstance(path, str):
        raise SmokeBlocked("SMOKE_PRIVATE_CANDIDATE_PATH_REQUIRED")
    expected = Path(temp) / "private-output" / "classic-loft-dj-TEST-SIGNED.apk"
    supplied = Path(path)
    if supplied != expected or not expected.is_file() or expected.is_symlink():
        raise SmokeBlocked("SMOKE_PRIVATE_CANDIDATE_PATH_REQUIRED")
    try:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from private_startup_audit import inspect_apk, dex_index
        graph = inspect_apk(expected)
        dex_origin = {}
        dex_labels = {
            "classes.dex": "PRIMARY_OEM_DEX",
            "classes2.dex": "SECONDARY_OEM_DEX",
            "classes3.dex": "NATIVE_BRIDGE_DEX",
        }
        with ZipFile(expected) as archive:
            dex_names = sorted(name for name in archive.namelist()
                               if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name))
            if set(dex_names) != set(dex_labels):
                raise ValueError("UNEXPECTED_DEX_SET")
            for dex_name in dex_names:
                for descriptor in dex_index(archive.read(dex_name)):
                    if descriptor in dex_origin:
                        raise ValueError("DUPLICATE_CLASS")
                    dex_origin[descriptor] = dex_labels[dex_name]
        if set(dex_origin) != set(graph["definitions"]):
            raise ValueError("DEX_READER_MISMATCH")
    except Exception:
        raise SmokeBlocked("SMOKE_PRIVATE_CANDIDATE_GRAPH_INVALID") from None
    if graph["package"] != PACKAGE:
        raise SmokeBlocked("SMOKE_PRIVATE_CANDIDATE_PACKAGE_MISMATCH")
    return {
        "definitions": frozenset(graph["definitions"]),
        "dex_origin": dex_origin,
        "manifest_providers": frozenset(name for (kind, name) in graph["components"]
                                         if kind == "provider"),
    }


def _sanitized_exception_chain(rows):
    """Choose the *last explicit nested cause*, without disclosing its text.

    Do not hunt for exception names in stack-frame messages, log chatter or
    another process's FATAL. Every returned value is a fixed allowlisted enum.
    """
    top = None
    nested = []
    for row in rows[:MAX_FATAL_BLOCK_LINES]:
        match = ROOT_CAUSE.match(_android_runtime_message(row))
        if match is None:
            continue
        name = match.group(2).rsplit(".", 1)[-1]
        code = next((code for token, code in (*EXCEPTIONS, *NESTED_EXCEPTION_TYPES)
                     if name == token), None)
        category = code or ("OTHER_NESTED_CAUSE" if match.group(1) else
                            "OTHER_FATAL_RUNTIME")
        if match.group(1):
            nested.append(category)
        elif top is None:
            top = category
    return {
        "exception_category": nested[-1] if nested else (top or "OTHER_FATAL_RUNTIME"),
        "nested_cause_count": min(len(nested), 16),
        "exception_depth": "NESTED_CAUSE" if nested else
                           "TOP_LEVEL" if top else "NO_EXCEPTION_HEADER",
    }


# Isolate the FIRST stack frame of the selected target FATAL. Never output
# proprietary method names or frame strings. Java stack dumps may contain
# renamed/obfuscated OEM classes with no package prefix; the signed APK's
# exact DEX-origin map, not a package-name heuristic, determines ownership.
NULL_STACK_FRAME = re.compile(
    r"^\s*at\s+([A-Za-z_$][A-Za-z0-9_.$]*)\.([A-Za-z_$][A-Za-z0-9_$<>]*)"
    r"\([^()\n]{0,180}\)\s*$"
)
KNOWN_DEX_ORIGINS = frozenset({
    "PRIMARY_OEM_DEX", "SECONDARY_OEM_DEX", "NATIVE_BRIDGE_DEX"
})


def _null_dereference_origin(rows, graph, fatal_line):
    """Return fixed low-entropy null-crash hints, never private symbols.

    This is *diagnostic*, not proof of a root cause, lifecycle completion,
    or an OEM-vs-clone comparison. Ignore unrelated logcat tags and the next
    process's FATAL block using the caller's exact target selection.
    """
    frame_classes = []
    lifecycle = "UNDETERMINED"
    operation = "UNSPECIFIED"
    for row in rows[:MAX_FATAL_BLOCK_LINES]:
        msg = _android_runtime_message(row)
        cause = ROOT_CAUSE.match(msg)
        if cause and cause.group(2).rsplit(".", 1)[-1] == "NullPointerException":
            if "Attempt to invoke" in msg and "null object reference" in msg:
                operation = "NULL_METHOD_INVOKE"
            elif "Attempt to read from field" in msg:
                operation = "NULL_FIELD_READ"
            elif "Attempt to write to field" in msg:
                operation = "NULL_FIELD_WRITE"
            elif "Attempt to get length of null array" in msg:
                operation = "NULL_ARRAY_LENGTH"
            else:
                operation = "NULL_OTHER"
        frame = NULL_STACK_FRAME.fullmatch(msg)
        if not frame:
            continue
        klass, method = frame.groups()
        frame_classes.append(klass)
        if lifecycle == "UNDETERMINED" and klass == "android.app.ActivityThread":
            if method in ("performLaunchActivity", "handleLaunchActivity"):
                lifecycle = "ACTIVITY_LAUNCH_STACK"
            elif method in ("performResumeActivity", "handleResumeActivity"):
                lifecycle = "ACTIVITY_RESUME_STACK"
            elif method in ("performPauseActivity", "handlePauseActivity"):
                lifecycle = "ACTIVITY_PAUSE_STACK"
        if lifecycle == "UNDETERMINED" and klass == "android.app.Instrumentation":
            if method == "callActivityOnCreate":
                lifecycle = "ACTIVITY_CREATE_STACK"
            elif method == "callActivityOnResume":
                lifecycle = "ACTIVITY_RESUME_STACK"
    def origin(klass):
        descriptor = "L" + klass.replace(".", "/") + ";"
        name = graph.get("dex_origin", {}).get(descriptor) if type(graph) is dict else None
        if name in KNOWN_DEX_ORIGINS:
            return name
        if klass.startswith(("android.", "java.", "javax.", "dalvik.")):
            return "PLATFORM_CLASS"
        return "UNINDEXED_OR_UNKNOWN_CLASS"
    first = origin(frame_classes[0]) if frame_classes else "NO_FRAME"
    first_owned = next((origin(k) for k in frame_classes
                        if origin(k) in KNOWN_DEX_ORIGINS), "NONE")
    header = _android_runtime_message(fatal_line)
    thread = ("MAIN_THREAD" if header == "FATAL EXCEPTION: main" else
              "NON_MAIN_THREAD" if header.startswith("FATAL EXCEPTION: ") else
              "UNDETERMINED")
    return {
        "null_operation_bucket": operation,
        "null_top_frame_origin": first,
        "null_first_owned_frame_origin": first_owned,
        "null_activity_lifecycle_hint": lifecycle,
        "null_crash_thread": thread,
        "null_frame_evidence": "STACK_FRAMES_SEEN" if frame_classes else "NO_PARSED_FRAMES",
        "null_origin_attribution_is_diagnostic_only": True,
    }


def classify_crash(logcat, package=PACKAGE, candidate_graph=None, installed_apk_path=None):
    """Classify actual AndroidRuntime target crash, not a guessed OEM failure.

    Process ownership comes from the adjacent Process/PID header (first 14
    lines), but cause classification uses a bounded 256-line FATAL block.
    It never borrows the nested exception of the next crash/process and never
    prints class names, stack frames, raw logs, or OEM identity.
    """
    _bounded(logcat, "SMOKE_BAD_LOGCAT")
    lines = logcat.splitlines()
    found_unattributed = False
    result = {"target_crash": False, "unattributed_fatal": False,
              "exception_category": "NONE", "failure_stage": "UNDETERMINED",
              "nested_cause_count": 0, "exception_depth": "NONE"}
    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" not in line:
            continue
        window = []
        for row in lines[i + 1:i + 1 + MAX_FATAL_BLOCK_LINES]:
            if "FATAL EXCEPTION" in row:
                break
            window.append(row)
        process = re.search(r"\bProcess:\s*([A-Za-z0-9_.:]+)\s*,\s*PID:",
                            "\n".join(window[:14]))
        if process and process.group(1) != package and not process.group(1).startswith(package + ":"):
            continue
        if not process:
            found_unattributed = True
            continue
        e = _sanitized_exception_chain(window)
        provenance = (_sanitized_classloader_provenance(window, candidate_graph, installed_apk_path)
                      if e["exception_category"] == "CLASS_LOADER_MISSING" else
                      _null_dereference_origin(window, candidate_graph, line)
                      if e["exception_category"] == "NULL_DEREFERENCE" else {})
        trace = "\n".join(window)
        if "installContentProviders" in trace or "installProvider(" in trace or "Provider.onCreate" in trace:
            stage = "PROVIDER_INIT"
        elif "Application.onCreate" in trace or "makeApplication" in trace:
            stage = "APPLICATION_INIT"
        elif "performLaunchActivity" in trace or "Activity.onCreate" in trace:
            stage = "ACTIVITY_LAUNCH"
        else:
            stage = "UNDETERMINED"
        return {**result, **e, **provenance, "target_crash": True,
                "unattributed_fatal": found_unattributed, "failure_stage": stage}
    return {**result, "unattributed_fatal": found_unattributed}


def evaluate_launch_window(*, component, launch_output, activity_snapshot, crash_log,
                           observed_ms, candidate_graph=None, installed_apk_path=None):
    """Assess one honest, bounded launch window; not an app/user acceptance.

    The positive state means only that a private emulator reported a resumed
    Activity with no target FATAL during this window, NOT that OEM networking,
    music, accounts, hardware, or a physical Pixel worked.
    """
    component = _component(component)
    _bounded(launch_output, "SMOKE_BAD_START_OUTPUT")
    _bounded(activity_snapshot, "SMOKE_BAD_ACTIVITY_SNAPSHOT")
    if type(observed_ms) is not int or observed_ms < 2000 or observed_ms > 120000:
        raise SmokeBlocked("SMOKE_OBSERVATION_WINDOW_INVALID")
    expected_activity = re.search(r"^Activity:\s*(.+?)\s*$", launch_output, re.M)
    resolved = expected_activity.group(1) if expected_activity is not None else ""
    # A MAIN/LAUNCHER alias may start another OEM Activity inside the clone.
    # Neither an arbitrary exported clone nor the DJ Activity is an OEM entry.
    valid_component = (resolved == component or
                       bool(re.fullmatch(re.escape(PACKAGE) +
                                         r"/com\.yeelight\.[A-Za-z_$][A-Za-z0-9_.$]*",
                                         resolved)))
    valid_am = (bool(re.search(r"^Status:\s*ok\s*$", launch_output, re.M))
                and valid_component
                and bool(re.search(r"^Complete\s*$", launch_output, re.M))
                and not re.search(r"(?:^Error:|^Security exception:|^Warning: Activity not started)", launch_output, re.M))
    crash = classify_crash(crash_log, candidate_graph=candidate_graph,
                           installed_apk_path=installed_apk_path)
    resumed, resume_category = _classic_foreground(activity_snapshot)
    return {
        "status": ("BLOCKED_CRASH" if crash["target_crash"] else
                   "BLOCKED_UNATTRIBUTED_FATAL" if crash["unattributed_fatal"] else
                   "BLOCKED_START_RESULT" if not valid_am else
                   "BLOCKED_ACTIVITY_NOT_RESUMED" if not resumed else
                   "EMULATOR_ACTIVITY_RESUMED_OBSERVED"),
        "am_result_accepted": bool(valid_am),
        "resumed_activity_observed": bool(resumed),
        "foreground_category": resume_category,
        "observed_window_ms": observed_ms,
        "crash": crash,
        "runtime": "PRIVATE_EMULATOR_WINDOW_ONLY",
        "physical_device_tested": False,
        "lighting_tested": False,
        "app_functionally_accepted": False,
    }



def classify_visible_ui(dump, *, stage, expected_package=PACKAGE):
    """Confirm visible, clickable native controls; never return raw UI XML.

    An ActivityRecord alone may identify an empty or broken Activity. UIAutomator
    data is read exclusively from the isolated private emulator and discarded.
    """
    _bounded(dump, "SMOKE_BAD_UI_DUMP")
    if expected_package not in (PACKAGE, ORIGINAL_PACKAGE):
        raise SmokeBlocked("SMOKE_UI_PACKAGE_NOT_AUTHORIZED")
    if stage not in ("classic_entry", "native_dj", "music_inline_toggle", "music_inline_expanded"):
        raise SmokeBlocked("SMOKE_INVALID_UI_STAGE")
    if "<!DOCTYPE" in dump or "<!ENTITY" in dump:
        raise SmokeBlocked("SMOKE_UNSAFE_UI_XML")
    begin = dump.find("<?xml")
    end = dump.find("</hierarchy>", begin if begin >= 0 else 0)
    if begin < 0 or end < begin:
        raise SmokeBlocked("SMOKE_UI_XML_MISSING")
    try:
        root = ET.fromstring(dump[begin:end + len("</hierarchy>")])
    except (ET.ParseError, ValueError):
        raise SmokeBlocked("SMOKE_UI_XML_INVALID") from None
    if root.tag != "hierarchy":
        raise SmokeBlocked("SMOKE_UI_ROOT_INVALID")
    nodes = [x.attrib for x in root.iter("node")]
    # UIAutomator's node package attribution is the only evidence used here.
    # A pre-login OEM Activity or Android compatibility dialog can occupy the
    # first frame; never mislabel it as a failed Music screen or bypass login.
    # Return a fixed category, never package names, OEM labels or raw UI text.
    packages = {x["package"] for x in nodes if x.get("package")}
    system_packages = {"android", "com.android.systemui",
                       "com.google.android.permissioncontroller",
                       "com.android.permissioncontroller"}
    if expected_package in packages:
        origin = ("CLONE_ONLY" if packages == {expected_package}
                  else "CLONE_AND_OTHER")
    elif packages and packages <= system_packages:
        origin = "ANDROID_SYSTEM_ONLY"
    elif packages:
        origin = "OTHER_OR_UNVERIFIED"
    else:
        origin = "UNATTRIBUTED"
    def own_control(node, label, clickable=None):
        return (node.get("package") == expected_package and
                node.get("text") == label and
                node.get("enabled") != "false" and
                (clickable is None or node.get("clickable") == clickable))
    if stage == "classic_entry":
        ok = any(own_control(x, "Loft DJ", "true") for x in nodes)
    elif stage in ("music_inline_toggle", "music_inline_expanded"):
        toggle = any(own_control(x, "Loft DJ", "true") and
                     x.get("content-desc") ==
                     "Loft DJ Steuerung in Classic-Musik einblenden"
                     for x in nodes)
        if stage == "music_inline_toggle":
            ok = toggle
        else:
            # These appear only after tapping the in-place toggle.
            # No separate DJ Activity and no microphone permission is needed.
            status = any(x.get("package") == expected_package and
                         x.get("content-desc") ==
                         "Loft DJ Status innerhalb Classic Music"
                         for x in nodes)
            analyze = any(x.get("package") == expected_package and
                          x.get("content-desc") ==
                          "Mikrofonanalyse starten, ohne Lampen" and
                          x.get("enabled") != "false" and
                          x.get("clickable") == "true" for x in nodes)
            stop = any(x.get("package") == expected_package and
                       x.get("content-desc") ==
                       "Loft DJ Mikrofonanalyse stoppen" and
                       x.get("enabled") != "false" and
                       x.get("clickable") == "true" for x in nodes)
            ok = toggle and status and analyze and stop
    else:
        title = any(own_control(x, "Loft DJ · Yeelight Classic") for x in nodes)
        start = any(own_control(x, "Mikrofon starten", "true") for x in nodes)
        stop = any(own_control(x, "DJ stoppen", "true") for x in nodes)
        ok = title and start and stop
    return {"expected_native_ui_observed": bool(ok),
            "ui_node_origin_category": origin,
            "ui_stage": stage, "ui_interaction_tested": False}


def entry_button_coordinates(dump, display, *, expected_package=PACKAGE,
                             native_music=False):
    """Select only the verified Classic button; native mode is Music-only."""
    if type(native_music) is not bool or (
            native_music and expected_package != PACKAGE):
        raise SmokeBlocked("SMOKE_MUSIC_MODE_OUT_OF_SCOPE")
    stage = "music_inline_toggle" if native_music else "classic_entry"
    result = classify_visible_ui(dump, stage=stage,
                                 expected_package=expected_package)
    if not result["expected_native_ui_observed"]:
        raise SmokeBlocked("SMOKE_MUSIC_INLINE_TOGGLE_NOT_VISIBLE"
                           if native_music else "SMOKE_CLASSIC_ENTRY_NOT_VISIBLE")
    _bounded(display, "SMOKE_BAD_DISPLAY")
    sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", display)
    if not sizes:
        raise SmokeBlocked("SMOKE_DISPLAY_SIZE_UNVERIFIED")
    width, height = map(int, sizes[-1])
    if width not in range(200, 6001) or height not in range(200, 12001):
        raise SmokeBlocked("SMOKE_DISPLAY_SIZE_UNVERIFIED")
    start = dump.find("<?xml")
    end = dump.find("</hierarchy>", start)
    if start < 0 or end < start:
        raise SmokeBlocked("SMOKE_UI_XML_MISSING")
    try:
        tree = ET.fromstring(dump[start:end + len("</hierarchy>")])
    except ET.ParseError:
        raise SmokeBlocked("SMOKE_UI_XML_INVALID") from None
    buttons = [n.attrib for n in tree.iter("node")
               if n.get("package") == expected_package and
               n.get("text") == "Loft DJ" and
               (not native_music or n.get("content-desc") ==
                "Loft DJ Steuerung in Classic-Musik einblenden") and
               n.get("clickable") == "true" and n.get("enabled") != "false"]
    if len(buttons) != 1:
        raise SmokeBlocked("SMOKE_CLASSIC_ENTRY_AMBIGUOUS")
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                          buttons[0].get("bounds", ""))
    if not bounds:
        raise SmokeBlocked("SMOKE_ENTRY_BOUNDS_UNVERIFIED")
    x1, y1, x2, y2 = map(int, bounds.groups())
    if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
        raise SmokeBlocked("SMOKE_ENTRY_BOUNDS_INVALID")
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def evaluate_navigation_window(*, component, activity_snapshot, crash_log, ui_dump,
                               observed_ms, candidate_graph=None, installed_apk_path=None):
    """Test in-app navigation, not an exported direct-Activity shortcut."""
    component = _component(component)
    if type(observed_ms) is not int or observed_ms < 2000 or observed_ms > 120000:
        raise SmokeBlocked("SMOKE_OBSERVATION_WINDOW_INVALID")
    _bounded(activity_snapshot, "SMOKE_BAD_ACTIVITY_SNAPSHOT")
    crash = classify_crash(crash_log, candidate_graph=candidate_graph,
                           installed_apk_path=installed_apk_path)
    # Navigation MUST resume the exact native DJ class, not merely any clone.
    resumed = _resumed(activity_snapshot, component)
    ui = (classify_visible_ui(ui_dump, stage="native_dj")
          if resumed and not crash["target_crash"] and not crash["unattributed_fatal"]
          else {"expected_native_ui_observed": False, "ui_stage": "native_dj",
                "ui_interaction_tested": False})
    return {
        "status": ("BLOCKED_CRASH" if crash["target_crash"] else
                   "BLOCKED_UNATTRIBUTED_FATAL" if crash["unattributed_fatal"] else
                   "BLOCKED_DJ_NOT_RESUMED" if not resumed else
                   "BLOCKED_REQUIRED_UI_NOT_VISIBLE" if not ui["expected_native_ui_observed"] else
                   "EMULATOR_NATIVE_DJ_NAVIGATION_OBSERVED"),
        "resumed_activity_observed": resumed,
        "observed_window_ms": observed_ms,
        "ui": ui,
        "crash": crash,
        "runtime": "PRIVATE_EMULATOR_WINDOW_ONLY",
        "physical_device_tested": False,
        "lighting_tested": False,
        "app_functionally_accepted": False,
    }


def _adb(args, *, serial, timeout=18):
    """The sole subprocess boundary; never invokes a shell or network tool."""
    try:
        result = subprocess.run(["adb", "-s", serial, *args], check=False,
                                text=True, capture_output=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        raise SmokeBlocked("SMOKE_ADB_UNAVAILABLE") from None
    if result.returncode != 0:
        raise SmokeBlocked("SMOKE_ADB_COMMAND_FAILED")
    return _bounded(result.stdout, "SMOKE_ADB_OUTPUT_TOO_LARGE")


# APK remains private on an owner-approved disposable offline emulator.
# Android 17 UiAutomator can report only the destination on /dev/tty stdout.
# The identity-control path already uses an ephemeral file; use the same
# capture contract for BOTH clone UI stages without publishing its contents.
PRIVATE_UI_SNAPSHOT = "/data/local/tmp/classic-dj-owner-only-smoke.xml"


def capture_private_ui_hierarchy(run):
    """Read actual XML bytes, never infer UI visibility from a dump success.

    The runner must be an already owner-gated isolated emulator. No shell
    interpolation or arbitrary output, and cleanup is mandatory on ALL paths.
    """
    try:
        run(["shell", "rm", "-f", PRIVATE_UI_SNAPSHOT])
        run(["shell", "uiautomator", "dump", PRIVATE_UI_SNAPSHOT])
        xml = run(["shell", "cat", PRIVATE_UI_SNAPSHOT])
        _bounded(xml, "SMOKE_BAD_UI_DUMP")
        if "<?xml" not in xml or "</hierarchy>" not in xml:
            raise SmokeBlocked("SMOKE_UI_XML_MISSING")
        return xml
    finally:
        try:
            run(["shell", "rm", "-f", PRIVATE_UI_SNAPSHOT])
        except (SmokeBlocked, OSError, ValueError, TypeError):
            raise SmokeBlocked("SMOKE_UI_TEMP_CLEANUP_FAILED") from None


def _focused_system_window(run):
    """Read first-frame focus without exposing any Android or OEM window text."""
    for section in ("displays", "windows"):
        raw = run(["shell", "dumpsys", "window", section])
        _bounded(raw, "SMOKE_BAD_WINDOW_SNAPSHOT")
        matches = re.findall(r"(?m)^[ \t]*mCurrentFocus=[^\r\n]{1,512}$", raw)
        if matches:
            return (raw if len(matches) == 1 else "",
                    "DISPLAY_FOCUS_DECLARED" if section == "displays" and len(matches) == 1
                    else "LEGACY_FOCUS_DECLARED" if len(matches) == 1
                    else "FOCUS_AMBIGUOUS_NO_ACTION")
    return "", "FOCUS_NOT_DECLARED_NO_ACTION"


def _unique_current_focus_token(snapshot):
    """Opaque private-only token; NEVER serialize the focused window title."""
    if type(snapshot) is not str or len(snapshot) > MAX_TEXT:
        return None
    matches = re.findall(
        r"(?m)^[ \t]*mCurrentFocus=(Window\{[^\r\n]{1,300}\})[ \t]*$",
        snapshot)
    return matches[0] if len(matches) == 1 else None


def _focus_token_relation(before, after):
    old = _unique_current_focus_token(before)
    new = _unique_current_focus_token(after)
    return ('UNVERIFIED' if not old or not new else
            'SAME_FOCUS_TOKEN' if old == new else
            'DIFFERENT_FOCUS_TOKEN')


def _warning_focus_tokens(snapshot):
    return tuple(re.findall(
        r"(?m)^[ \t]*m(?:CurrentFocus|FocusedApp)=[^\r\n]{1,512}$",
        snapshot))


def _single_known_16k_warning(ui_xml, *, run, pause, prelaunch_focus=''):
    """At most one exact AOSP 16K acknowledgment per Activity transition.

    Unknown, login, permission, ANR and unrelated Android dialogs are NEVER
    clicked. This path is reachable only after the owner-gated offline
    emulator, installed-hash and original-Activity launch checks.
    """
    observation = {
        "status": "SYSTEM_UI_NOT_RECOGNIZED_NO_ACTION",
        "acknowledged": False,
        "permission_granted": False,
        "native_entry_accepted": False,
        "raw_ui_or_window_logged": False,
    }
    try:
        focus, state = _focused_system_window(run)
        observation["focus_source"] = state
        if not focus:
            return ui_xml, observation
        display = run(["shell", "wm", "size"])
        evidence = {}
        warning = read_warning_action(
            ui_xml, focus, display, run, evidence, target_package=PACKAGE)
        observation["page_size_probe"] = evidence
        if warning is None:
            # An actual system UID 1000 APPLICATION_OVERLAY is *not* the
            # AOSP TYPE_PHONE warning. Classify only known platform text and
            # lifecycle identity; never grant a tap or emit the raw UI.
            if (evidence.get('window_type_detail') == 'APP_OVERLAY'
                    and evidence.get('focused_app') == 'CLASSIC'
                    and evidence.get('window_owner') == 'ANDROID_SYSTEM'):
                observation['prelaunch_focus_relation'] = (
                    _focus_token_relation(prelaunch_focus, focus))
                try:
                    windows = run(['shell', 'dumpsys', 'window', 'windows'])
                    resources = read_framework_resources(run)
                    observation['system_overlay_inspection'] = (
                        inspect_app_overlay_readonly(
                            ui_xml, focus, windows, resources,
                            target_package=PACKAGE))
                except (SmokeBlocked, OSError, ValueError, TypeError):
                    observation['system_overlay_inspection'] = {
                        'status': 'OVERLAY_READONLY_PROBE_UNAVAILABLE',
                        'interaction_performed': False}
            return ui_xml, observation
        next_focus, next_state = _focused_system_window(run)
        current_tokens = _warning_focus_tokens(focus)
        if (not next_focus or next_state != state
                or len(current_tokens) != 2
                or current_tokens != _warning_focus_tokens(next_focus)):
            observation["status"] = "WARNING_FOCUS_CHANGED_NO_ACTION"
            return ui_xml, observation
        # Re-establish full offline isolation immediately before touching a
        # *verified system compatibility button*, never user app controls.
        if (run(["shell", "getprop", "ro.kernel.qemu"]) != "1"
                or run(["shell", "settings", "get", "global", "airplane_mode_on"]) != "1"
                or run(["shell", "settings", "get", "global", "wifi_on"]) != "0"
                or run(["shell", "settings", "get", "global", "mobile_data"]) != "0"):
            observation["status"] = "WARNING_OFFLINE_STATE_CHANGED_NO_ACTION"
            return ui_xml, observation
        for version in ("-4", "-6"):
            routes = run(["shell", "ip", version, "route", "show"])
            if routes and any(" dev lo" not in line for line in routes.splitlines()):
                observation["status"] = "WARNING_NETWORK_ROUTE_CHANGED_NO_ACTION"
                return ui_xml, observation
        (x, y), kind = warning
        run(["shell", "input", "tap", str(x), str(y)])
        observation["status"] = "EXACT_AOSP_PAGE_SIZE_WARNING_ACKNOWLEDGED"
        observation["page_size_warning_kind"] = kind
        observation["acknowledged"] = True
        pause(1)
        return capture_private_ui_hierarchy(run), observation
    except SmokeBlocked as exc:
        # Do not swallow an incomplete private screenshot cleanup.
        if str(exc) == "SMOKE_UI_TEMP_CLEANUP_FAILED":
            raise
        observation["status"] = "WARNING_PROBE_UNAVAILABLE_NO_ACTION"
        return ui_xml, observation


def _passive_system_ui_recheck(ui_xml, *, stage, run, pause):
    """One bounded read-only wait for transient first-run system surfaces.

    Android's cold first run can outlive the first 3-second window. Never
    acknowledge, swipe, log in, grant permission, or infer OEM readiness.
    No raw UI hierarchy, Android dialog text, or OEM labels leave this module.
    """
    before = classify_visible_ui(ui_xml, stage=stage)
    if before["ui_node_origin_category"] != "ANDROID_SYSTEM_ONLY":
        return ui_xml, None
    before_focus, _before_source = _focused_system_window(run)
    pause(8)
    fresh = capture_private_ui_hierarchy(run)
    after_focus, _after_source = _focused_system_window(run)
    state = classify_visible_ui(fresh, stage=stage)
    status = ("NATIVE_CONTROLS_BECAME_VISIBLE"
              if state["expected_native_ui_observed"] else
              "SYSTEM_UI_PERSISTED_NO_ACTION"
              if state["ui_node_origin_category"] == "ANDROID_SYSTEM_ONLY" else
              "OTHER_UI_NO_NATIVE_PROOF")
    return fresh, {
        "status": status,
        "wait_ms": 8000,
        "window_focus_relation": _focus_token_relation(before_focus, after_focus),
        "ui_node_origin_category": state["ui_node_origin_category"],
        "expected_native_ui_observed": state["expected_native_ui_observed"],
        "interaction_performed": False,
        "permission_granted": False,
        "account_login_tested": False,
        "raw_ui_or_window_logged": False,
    }


def _native_clone_music_host(snapshot):
    """Current global top is the real Classic MusicActivity inside the clone.

    Class names are compared privately and NEVER returned/logged.
    """
    category, activity = _global_top(snapshot)
    return bool(category == "CLONE_GLOBAL_TOP" and activity and
                activity.startswith("com.yeelight.cherry.") and
                activity.endswith(".MusicActivity") and
                "$" not in activity and "/" not in activity)


def _verify_inline_music_route(*, home, reports, ui_data, run, pause,
                               dwell_ms, candidate_graph, installed_path):
    """Qualify one real OEM Music screen and in-place DJ expansion, no login.

    This is private emulator UI evidence only, NOT app, MiOT or lamp acceptance.
    """
    if (home["status"] not in ("EMULATOR_ACTIVITY_RESUMED_OBSERVED",
                               "BLOCKED_REQUIRED_UI_NOT_VISIBLE")
            or not home["resumed_activity_observed"]):
        return {"status": "PRIVATE_EMULATOR_LAUNCH_BLOCKED",
                "entrypoint_observations": reports,
                "installed_apk_hash_verified": True,
                "native_entry_navigation_tested": False,
                "physical_device_tested": False,
                "app_functionally_accepted": False}
    before = run(["shell", "dumpsys", "activity", "activities"])
    host = _native_clone_music_host(before)
    home["oem_music_activity_resumed"] = host
    if not host or not home["ui"]["expected_native_ui_observed"]:
        # An OEM Welcome/Login/Home may be fully healthy while its Music
        # screen is unreachable in a freshly installed OFFLINE test clone.
        home["status"] = "OEM_START_OBSERVED_MUSIC_NOT_ATTESTED"
        return {"status": "PRIVATE_EMULATOR_OEM_START_ONLY",
                "entrypoint_observations": reports,
                "installed_apk_hash_verified": True,
                "native_entry_navigation_tested": False,
                "cloud_login_tested": False,
                "miot_binding_attested": False,
                "physical_device_tested": False,
                "app_functionally_accepted": False}
    x, y = entry_button_coordinates(ui_data, run(["shell", "wm", "size"]),
                                    native_music=True)
    run(["logcat", "-b", "crash", "-c"])
    run(["shell", "input", "tap", str(x), str(y)])
    pause(dwell_ms / 1000.0)
    after = run(["shell", "dumpsys", "activity", "activities"])
    crash = classify_crash(run(["logcat", "-b", "crash", "-d"]),
                           candidate_graph=candidate_graph,
                           installed_apk_path=installed_path)
    expanded = classify_visible_ui(capture_private_ui_hierarchy(run),
                                   stage="music_inline_expanded")
    same_host = (_native_clone_music_host(after) and
                 _global_top(after) == _global_top(before))
    good = (same_host and expanded["expected_native_ui_observed"] and
            not crash["target_crash"] and not crash["unattributed_fatal"])
    reports.append({
        "status": ("EMULATOR_CLASSIC_MUSIC_INLINE_OPENED" if good else
                   "BLOCKED_MUSIC_ACTIVITY_CHANGED" if not same_host else
                   "BLOCKED_CRASH" if crash["target_crash"] else
                   "BLOCKED_UNATTRIBUTED_FATAL" if crash["unattributed_fatal"] else
                   "BLOCKED_INLINE_CONTROLS_NOT_VISIBLE"),
        "oem_music_activity_retained": bool(same_host),
        "ui": expanded, "crash": crash,
        "microphone_started": False, "lamp_commands_sent": False,
    })
    return {
        "status": ("PRIVATE_EMULATOR_NATIVE_MUSIC_INLINE_OBSERVED" if good
                   else "PRIVATE_EMULATOR_NATIVE_MUSIC_INLINE_BLOCKED"),
        "entrypoint_observations": reports,
        "installed_apk_hash_verified": True,
        "native_entry_navigation_tested": True,
        "ui_controls_exercised": False,
        "microphone_started": False,
        "cloud_login_tested": False,
        "miot_binding_attested": False,
        "lamp_commands_sent": False,
        "physical_device_tested": False,
        "app_functionally_accepted": False,
    }


def probe_private_emulator(serial, *, owner_approved, launcher_component,
                           expected_installed_apk_sha256, dwell_ms=3000,
                           adb=_adb, pause=time.sleep, candidate_graph=None,
                           require_clone_music_ui=False):
    """Explicitly authorized PRIVATE offline emulator only; NEVER a real phone.

    Requires preinstalled private candidate, Android 17, emulator kernel,
    airplane + Wi-Fi-off + mobile-data-off + no routed network. Does not install or sign.
    When checks fail, performs no launch, force-stop, or log writes.
    """
    if owner_approved is not True:
        raise SmokeBlocked("SMOKE_OWNER_APPROVAL_REQUIRED")
    if type(serial) is not str or not SERIAL.fullmatch(serial):
        raise SmokeBlocked("SMOKE_EMULATOR_SERIAL_REQUIRED")
    _component(launcher_component)
    _component(PACKAGE + "/" + DJ_ACTIVITY)
    if type(require_clone_music_ui) is not bool:
        raise SmokeBlocked("SMOKE_INVALID_MUSIC_TEST_MODE")
    if (type(expected_installed_apk_sha256) is not str or
            not PIN.fullmatch(expected_installed_apk_sha256)):
        raise SmokeBlocked("SMOKE_APK_PIN_REQUIRED")
    if type(dwell_ms) is not int or dwell_ms < 2000 or dwell_ms > 30000:
        raise SmokeBlocked("SMOKE_BAD_DWELL")
    def run(args):
        return adb(args, serial=serial).strip()
    if run(["shell", "getprop", "ro.kernel.qemu"]) != "1":
        raise SmokeBlocked("SMOKE_NOT_AN_EMULATOR")
    release = run(["shell", "getprop", "ro.build.version.release"])
    if release != "17" and not release.startswith("17."):
        raise SmokeBlocked("SMOKE_NOT_ANDROID_17")
    if (run(["shell", "settings", "get", "global", "airplane_mode_on"]) != "1"
            or run(["shell", "settings", "get", "global", "wifi_on"]) != "0"
            or run(["shell", "settings", "get", "global", "mobile_data"]) != "0"):
        raise SmokeBlocked("SMOKE_EMULATOR_NOT_OFFLINE")
    # Airplane mode alone does NOT guarantee that the Android Emulator's
    # virtual Ethernet transport is disconnected. Require no routed IPv4/IPv6
    # path at all before triggering any OEM Application/ContentProvider code.
    for version in ("-4", "-6"):
        routes = run(["shell", "ip", version, "route", "show"])
        if routes and any(" dev lo" not in line for line in routes.splitlines()):
            raise SmokeBlocked("SMOKE_EMULATOR_ROUTE_NOT_ISOLATED")
    paths = run(["shell", "pm", "path", PACKAGE]).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise SmokeBlocked("SMOKE_PRIVATE_CLONE_NOT_INSTALLED")
    installed_path = paths[0][8:]
    if not APK_PATH.fullmatch(installed_path):
        raise SmokeBlocked("SMOKE_INSTALLED_APK_PATH_INVALID")
    actual_sha_output = run(["shell", "sha256sum", installed_path])
    fields = actual_sha_output.split()
    if (len(fields) != 2 or not PIN.fullmatch(fields[0]) or
            fields[1] != installed_path or
            fields[0] != expected_installed_apk_sha256):
        raise SmokeBlocked("SMOKE_INSTALLED_APK_HASH_MISMATCH")

    # Capture an opaque prelaunch focus token while the verified clone is
    # installed but not running. Only SAME/DIFFERENT/UNVERIFIED leaves RAM.
    # No prelaunch UI, OEM app label or system window title is serialized.
    try:
        prelaunch_focus, _prelaunch_source = _focused_system_window(run)
    except SmokeBlocked:
        prelaunch_focus = ''

    reports = []
    # Launch ONLY the OEM launcher, which must already be exported as a normal
    # launcher Activity. The private native DJ Activity is intentionally
    # android:exported=false and cannot be started directly by adb-shell.
    run(["logcat", "-b", "crash", "-c"])
    run(["shell", "am", "force-stop", PACKAGE])
    am_output = run(["shell", "am", "start", "-W", "-n", launcher_component])
    pause(dwell_ms / 1000.0)
    snapshot = run(["shell", "dumpsys", "activity", "activities"])
    crashes = run(["logcat", "-b", "crash", "-d"])
    home = evaluate_launch_window(component=launcher_component,
                                  launch_output=am_output, activity_snapshot=snapshot,
                                  crash_log=crashes, observed_ms=dwell_ms,
                                  candidate_graph=candidate_graph,
                                  installed_apk_path=installed_path)
    ui_stage = "music_inline_toggle" if require_clone_music_ui else "classic_entry"
    ui_data = None
    if home["status"] == "EMULATOR_ACTIVITY_RESUMED_OBSERVED":
        ui_data = capture_private_ui_hierarchy(run)
        home["ui"] = classify_visible_ui(ui_data, stage=ui_stage)
        if (not home["ui"]["expected_native_ui_observed"] and
                home["ui"]["ui_node_origin_category"] == "ANDROID_SYSTEM_ONLY"):
            ui_data, warning = _single_known_16k_warning(
                ui_data, run=run, pause=pause,
                prelaunch_focus=prelaunch_focus)
            home["system_warning"] = warning
            if warning["acknowledged"]:
                # A compatibility confirmation is NOT OEM init or a native
                # entry PASS. Re-check Activity/crashes after its dismissal.
                fresh = evaluate_launch_window(
                    component=launcher_component, launch_output=am_output,
                    activity_snapshot=run(["shell", "dumpsys", "activity", "activities"]),
                    crash_log=run(["logcat", "-b", "crash", "-d"]),
                    observed_ms=dwell_ms, candidate_graph=candidate_graph,
                    installed_apk_path=installed_path)
                home.update({k: fresh[k] for k in (
                    "status", "resumed_activity_observed", "foreground_category",
                    "crash")})
                home["ui"] = classify_visible_ui(ui_data, stage=ui_stage)
        ui_data, delayed = _passive_system_ui_recheck(
            ui_data, stage=ui_stage, run=run, pause=pause)
        if delayed is not None:
            home["passive_system_ui_recheck"] = delayed
            fresh = evaluate_launch_window(
                component=launcher_component, launch_output=am_output,
                activity_snapshot=run(["shell", "dumpsys", "activity", "activities"]),
                crash_log=run(["logcat", "-b", "crash", "-d"]),
                observed_ms=dwell_ms, candidate_graph=candidate_graph,
                installed_apk_path=installed_path)
            home.update({k: fresh[k] for k in (
                "status", "resumed_activity_observed", "foreground_category", "crash")})
            home["ui"] = classify_visible_ui(ui_data, stage=ui_stage)
        # Initial OEM welcome/login or a platform dialog need not inflate
        # activity_main_new.xml, where the normal clone's entry is attached.
        # Preserve independent startup evidence, but do not tap, infer an
        # account state, or count a missing entry as native UI acceptance.
        home["oem_main_initialization"] = "NOT_ATTESTED"
        home["miot_binding"] = "NOT_ATTESTED"
        home["cloud_login"] = "NOT_TESTED"
        if (home["ui"]["expected_native_ui_observed"] is False and
                home["status"] == "EMULATOR_ACTIVITY_RESUMED_OBSERVED"):
            home["status"] = "BLOCKED_REQUIRED_UI_NOT_VISIBLE"
    reports.append(home)
    if require_clone_music_ui:
        return _verify_inline_music_route(
            home=home, reports=reports, ui_data=ui_data,
            run=run, pause=pause, dwell_ms=dwell_ms,
            candidate_graph=candidate_graph, installed_path=installed_path)
    if home["status"] != "EMULATOR_ACTIVITY_RESUMED_OBSERVED":
        return {"status": "PRIVATE_EMULATOR_LAUNCH_BLOCKED",
                "entrypoint_observations": reports,
                "installed_apk_hash_verified": True,
                "native_entry_navigation_tested": False,
                "physical_device_tested": False, "app_functionally_accepted": False}

    # A real click of the native Classic layout entry verifies the actual
    # production UI wiring, without exporting the DJ Activity or sending a
    # private OS-level explicit Activity intent from adb.
    display = run(["shell", "wm", "size"])
    x, y = entry_button_coordinates(ui_data, display)
    run(["logcat", "-b", "crash", "-c"])
    run(["shell", "input", "tap", str(x), str(y)])
    pause(dwell_ms / 1000.0)
    dj_snapshot = run(["shell", "dumpsys", "activity", "activities"])
    dj_crashes = run(["logcat", "-b", "crash", "-d"])
    dj_ui = capture_private_ui_hierarchy(run)
    dj_classification = classify_visible_ui(dj_ui, stage="native_dj")
    dj_warning = None
    if (not dj_classification["expected_native_ui_observed"] and
            dj_classification["ui_node_origin_category"] == "ANDROID_SYSTEM_ONLY"):
        dj_ui, dj_warning = _single_known_16k_warning(
            dj_ui, run=run, pause=pause)
        if dj_warning["acknowledged"]:
            dj_snapshot = run(["shell", "dumpsys", "activity", "activities"])
            dj_crashes = run(["logcat", "-b", "crash", "-d"])
    dj_ui, dj_delayed = _passive_system_ui_recheck(
        dj_ui, stage="native_dj", run=run, pause=pause)
    if dj_delayed is not None:
        dj_snapshot = run(["shell", "dumpsys", "activity", "activities"])
        dj_crashes = run(["logcat", "-b", "crash", "-d"])
    dj = evaluate_navigation_window(component=PACKAGE + "/" + DJ_ACTIVITY,
                                    activity_snapshot=dj_snapshot,
                                    crash_log=dj_crashes, ui_dump=dj_ui,
                                    observed_ms=dwell_ms,
                                    candidate_graph=candidate_graph,
                                    installed_apk_path=installed_path)
    if dj_warning is not None:
        dj["system_warning"] = dj_warning
    if dj_delayed is not None:
        dj["passive_system_ui_recheck"] = dj_delayed
    reports.append(dj)
    success = dj["status"] == "EMULATOR_NATIVE_DJ_NAVIGATION_OBSERVED"
    return {
        "status": "PRIVATE_EMULATOR_NATIVE_NAVIGATION_OBSERVED" if success else
                  "PRIVATE_EMULATOR_LAUNCH_BLOCKED",
        "entrypoint_observations": reports,
        "android": "17",
        "installed_apk_hash_verified": True,
        "native_entry_navigation_tested": True,
        "ui_controls_exercised": False,
        "oem_contents_logged": False,
        "physical_device_tested": False,
        "app_functionally_accepted": False,
    }



def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--serial", required=True)
    p.add_argument("--launcher-component", required=True)
    p.add_argument("--expected-installed-apk-sha256", required=True)
    p.add_argument("--explicit-owner-approved-private-emulator", action="store_true")
    p.add_argument("--candidate-apk", required=True)
    p.add_argument("--require-clone-music-ui", action="store_true")
    args = p.parse_args(argv)
    try:
        candidate_graph = _private_candidate_graph(args.candidate_apk)
        report = probe_private_emulator(
            args.serial,
            owner_approved=args.explicit_owner_approved_private_emulator,
            launcher_component=args.launcher_component,
            expected_installed_apk_sha256=args.expected_installed_apk_sha256,
            candidate_graph=candidate_graph,
            require_clone_music_ui=args.require_clone_music_ui)
    except SmokeBlocked as e:
        # Fixed code only. Never print the raw adb/logcat/OEM identity.
        print(json.dumps({"status": "BLOCKED", "code": str(e)}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    accepted = ("PRIVATE_EMULATOR_NATIVE_MUSIC_INLINE_OBSERVED"
                if args.require_clone_music_ui else
                "PRIVATE_EMULATOR_NATIVE_NAVIGATION_OBSERVED")
    return 0 if report["status"] == accepted else 2


if __name__ == "__main__":
    raise SystemExit(main())
