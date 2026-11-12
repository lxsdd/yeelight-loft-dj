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

PACKAGE = "io.lxsdd.yeelightdj"
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
                      if e["exception_category"] == "CLASS_LOADER_MISSING" else {})
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



def classify_visible_ui(dump, *, stage):
    """Confirm visible, clickable native controls; never return raw UI XML.

    An ActivityRecord alone may identify an empty or broken Activity. UIAutomator
    data is read exclusively from the isolated private emulator and discarded.
    """
    _bounded(dump, "SMOKE_BAD_UI_DUMP")
    if stage not in ("classic_entry", "native_dj"):
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
    if stage == "classic_entry":
        ok = any(x.get("text") == "Loft DJ" and x.get("clickable") == "true"
                 for x in nodes)
    else:
        title = any(x.get("text") == "Loft DJ · Yeelight Classic" for x in nodes)
        start = any(x.get("text") == "Mikrofon starten" and x.get("clickable") == "true"
                    for x in nodes)
        stop = any(x.get("text") == "DJ stoppen" and x.get("clickable") == "true"
                   for x in nodes)
        ok = title and start and stop
    return {"expected_native_ui_observed": bool(ok),
            "ui_stage": stage, "ui_interaction_tested": False}


def entry_button_coordinates(dump, display):
    """Select only the actual visible Classic Loft DJ button; no blind taps."""
    result = classify_visible_ui(dump, stage="classic_entry")
    if not result["expected_native_ui_observed"]:
        raise SmokeBlocked("SMOKE_CLASSIC_ENTRY_NOT_VISIBLE")
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
               if n.get("text") == "Loft DJ" and n.get("clickable") == "true"]
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


def probe_private_emulator(serial, *, owner_approved, launcher_component,
                           expected_installed_apk_sha256, dwell_ms=3000,
                           adb=_adb, pause=time.sleep, candidate_graph=None):
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
    ui_data = None
    if home["status"] == "EMULATOR_ACTIVITY_RESUMED_OBSERVED":
        ui_data = run(["shell", "uiautomator", "dump", "/dev/tty"])
        home["ui"] = classify_visible_ui(ui_data, stage="classic_entry")
        if home["ui"]["expected_native_ui_observed"] is False:
            home["status"] = "BLOCKED_REQUIRED_UI_NOT_VISIBLE"
    reports.append(home)
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
    dj_ui = run(["shell", "uiautomator", "dump", "/dev/tty"])
    dj = evaluate_navigation_window(component=PACKAGE + "/" + DJ_ACTIVITY,
                                    activity_snapshot=dj_snapshot,
                                    crash_log=dj_crashes, ui_dump=dj_ui,
                                    observed_ms=dwell_ms,
                                    candidate_graph=candidate_graph,
                                    installed_apk_path=installed_path)
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
    args = p.parse_args(argv)
    try:
        candidate_graph = _private_candidate_graph(args.candidate_apk)
        report = probe_private_emulator(
            args.serial,
            owner_approved=args.explicit_owner_approved_private_emulator,
            launcher_component=args.launcher_component,
            expected_installed_apk_sha256=args.expected_installed_apk_sha256,
            candidate_graph=candidate_graph)
    except SmokeBlocked as e:
        # Fixed code only. Never print the raw adb/logcat/OEM identity.
        print(json.dumps({"status": "BLOCKED", "code": str(e)}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0 if report["status"] == "PRIVATE_EMULATOR_NATIVE_NAVIGATION_OBSERVED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
