"""Exact AOSP page-size warning recognition for the owner-only offline probe.

Unlike DeprecatedTargetSdkVersionDialog, AOSP PageSizeMismatchDialog does
NOT set a stable Window title. Never identify it by an app label or generic
OK text. Match the focused system-owned TYPE_PHONE window, the selected
Classic Activity, a 16 KiB kernel and the running framework's exact message.

Public source contracts:
  platform/frameworks/base/services/core/java/com/android/server/wm/PageSizeMismatchDialog.java
  platform/frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java
  platform/frameworks/base/services/core/java/com/android/server/om/OverlayManagerShellCommand.java
on https://android.googlesource.com/. No OEM source or hierarchy is emitted.
This module cannot tap, set compatibility flags, grant permission or log in.
"""
from html.parser import HTMLParser
import re
import subprocess
import xml.etree.ElementTree as ET

ORIGINAL = "com.yeelight.cherry"
CLONE = "io.lxsdd.yeelightdj"
ALLOWED_TARGETS = frozenset((ORIGINAL, CLONE))
RESOURCES = {
    "page_size_compat_apk_warning": "APK_ALIGNMENT",
    "page_size_compat_elf_warning": "ELF_ALIGNMENT",
    "page_size_compat_apk_and_elf_warning": "APK_AND_ELF_ALIGNMENT",
}
MAX_TEXT = 2_000_000


class _PlainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []

    def handle_data(self, value):
        self.parts.append(value)

    def handle_starttag(self, tag, attrs):
        if tag in ("br", "p", "div"):
            self.parts.append(" ")

    def handle_endtag(self, tag):
        if tag in ("p", "div"):
            self.parts.append(" ")


def rendered_resource(value):
    """Resolve only the documented AOSP lookup arrow, never guess resource text.

    OverlayManagerShellCommand.runLookup prints either a value or a
    TypedValue reference followed by ' -> ' and the resolved value. A bare
    @reference or a short token is NOT proof of an actual warning message.
    Its text cannot authorize a UI action in the private emulator.
    """
    if type(value) is not str or not 0 < len(value) <= 8192:
        return None
    raw = value.strip()
    if "Error:" in raw or "\x00" in raw or "\r" in raw:
        return None
    if " -> " in raw:
        arrow = re.fullmatch(
            r"@(?:0x[0-9a-fA-F]{8}|android:string/[A-Za-z0-9_]+) -> ([^\r\n]+)",
            raw)
        if arrow is None:
            return None
        raw = arrow.group(1)
    if (raw.startswith("@") or re.fullmatch(r"0x[0-9a-fA-F]{6,8}", raw)
            or not raw):
        return None
    parser = _PlainText()
    try:
        parser.feed(raw)
        parser.close()
    except (ValueError, TypeError):
        return None
    return " ".join("".join(parser.parts).split()) or None


def read_framework_resources(run, diagnostic=None):
    """Read each fixed resource independently across Android build variants.

    API37 currently has the APK warning but can omit older ELF warning keys.
    Missing keys never authorize a tap and must not discard another exact,
    successfully read framework warning. No fallback English text is invented.
    """
    resources, states = {}, {}
    for name in (*RESOURCES, 'ok'):
        label = RESOURCES.get(name, 'POSITIVE_BUTTON')
        try:
            value = run(['shell', 'cmd', 'overlay', 'lookup', 'android', 'android:string/' + name])
            rendered = rendered_resource(value)
            if rendered:
                resources[name] = value
                states[label] = ('SHORT_UNVERIFIED' if name != 'ok'
                                 and len(rendered) < 32 else 'READ')
            elif type(value) is str and value.strip().startswith('@'):
                states[label] = 'UNRESOLVED_REFERENCE'
            else:
                states[label] = 'INVALID_VALUE'
        except (OSError, ValueError, subprocess.SubprocessError):
            states[label] = 'READ_FAILED'
    if type(diagnostic) is dict:
        diagnostic['framework_resource_reads'] = states
    return resources


def _window_attributes(body):
    """Read the one balanced LayoutParams block, including sim={...}.

    AOSP puts the nested soft-input block BEFORE ty=. Stopping at the first
    closing brace silently loses the window type on a real Android window.
    The caller already bounded body to this unique focused Window record.
    """
    starts = list(re.finditer(r'(?m)^[ \t]*mAttrs=\{', body))
    if len(starts) != 1:
        return None
    start = starts[0].end() - 1
    depth = 0
    for index in range(start, min(len(body), start + 8192)):
        char = body[index]
        if char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return body[start + 1:index]
    return None


def _system_phone_window(focus, windows, diagnostic=None, *, target_package=ORIGINAL):
    evidence = diagnostic if type(diagnostic) is dict else {}
    evidence['window_check'] = 'INPUT_INVALID'
    if type(target_package) is not str or target_package not in ALLOWED_TARGETS:
        return False
    if any(type(x) is not str or len(x) > MAX_TEXT for x in (focus, windows)):
        return False
    current = re.findall(r"(?m)^[ \t]*mCurrentFocus=(Window\{[^\r\n]{1,300}\})[ \t]*$", focus)
    evidence['window_check'] = 'FOCUS_MISSING_OR_AMBIGUOUS'
    if len(re.findall(r"(?m)^[ \t]*mCurrentFocus=", focus)) != 1 or len(current) != 1:
        return False
    selected = re.findall(r"(?m)^[ \t]*mFocusedApp=([^\r\n]{1,512})[ \t]*$", focus)
    classic = len(selected) == 1 and bool(re.search(
        r"(?<![A-Za-z0-9_.])" + re.escape(target_package) + r"/[^\s}]+",
        selected[0]))
    evidence['focused_app'] = 'CLASSIC' if classic else 'OTHER_OR_UNRESOLVED'
    # Bind owner/type to THIS focused Window token, not a different window
    # somewhere in a large dump. Duplicates and incomplete dumps fail closed.
    headers = list(re.finditer(
        r"(?m)^[ \t]*Window #\d+ (Window\{[^\r\n]{1,300}\}):[ \t]*$", windows))
    target = [i for i, match in enumerate(headers) if match.group(1) == current[0]]
    evidence['window_check'] = 'FOCUSED_WINDOW_NOT_UNIQUE'
    if len(target) != 1:
        return False
    i = target[0]
    end = headers[i + 1].start() if i + 1 < len(headers) else len(windows)
    body = windows[headers[i].end():end]
    evidence['window_binding'] = 'MATCHED'
    owners = re.findall(r"(?m)^[ \t]*mOwnerUid=(\d+)\b[^\r\n]*\bpackage=([^\s]+)", body)
    attrs = _window_attributes(body)
    types = re.findall(r"\bty=([^\s}]+)", attrs) if attrs is not None else []
    owner = owners == [("1000", "android")]
    phone = types in (["PHONE"], ["2002"])
    evidence['window_owner'] = ('ANDROID_SYSTEM' if owner else
                                'OTHER' if len(owners) == 1 else 'UNPARSEABLE')
    evidence['window_type'] = ('PHONE' if phone else
                               'OTHER' if len(types) == 1 else 'UNPARSEABLE')
    # Read-only AOSP window category. Never output raw titles, types or OEM
    # labels. No category other than the independently verified PHONE gate
    # changes the permission to acknowledge a framework dialog.
    window_kinds = {
        "PHONE": "PHONE", "2002": "PHONE",
        "BASE_APPLICATION": "APP_BASE", "1": "APP_BASE",
        "APPLICATION": "APP_MAIN", "2": "APP_MAIN",
        "APPLICATION_STARTING": "APP_STARTING", "3": "APP_STARTING",
        "APPLICATION_PANEL": "APP_PANEL", "1000": "APP_PANEL",
        "APPLICATION_ATTACHED_DIALOG": "APP_ATTACHED_DIALOG", "1003": "APP_ATTACHED_DIALOG",
        "SYSTEM_DIALOG": "SYSTEM_DIALOG", "2008": "SYSTEM_DIALOG",
        "SYSTEM_ALERT": "SYSTEM_ALERT", "2003": "SYSTEM_ALERT",
        "SYSTEM_ERROR": "SYSTEM_ERROR", "2010": "SYSTEM_ERROR",
        "APPLICATION_OVERLAY": "APP_OVERLAY", "2038": "APP_OVERLAY",
        "STATUS_BAR": "STATUS_BAR", "2000": "STATUS_BAR",
        "INPUT_METHOD": "INPUT_METHOD", "2011": "INPUT_METHOD",
        "TOAST": "TOAST", "2005": "TOAST",
    }
    evidence['window_type_detail'] = (
        window_kinds.get(types[0], 'OTHER_UNCLASSIFIED')
        if len(types) == 1 else 'UNPARSEABLE')
    title = current[0]
    evidence['focused_window_category'] = (
        'AOSP_DEPRECATED_TARGET_DIALOG' if ' DeprecatedTargetSdkVersionDialog}' in title
        else 'ANDROID_ANR_DIALOG' if ('Application Not Responding:' in title
                                     or 'App Not Responding:' in title)
        else 'ANDROID_PERMISSION_CONTROLLER' if 'permissioncontroller' in title.lower()
        else 'ANDROID_LAUNCHER' if ('com.android.launcher3' in title
                                   or 'com.google.android.apps.nexuslauncher' in title)
        else 'ANDROID_SYSTEM_UI' if 'com.android.systemui' in title
        else 'ANDROID_SPLASH_WINDOW' if 'Splash Screen' in title
        else 'OTHER_OR_UNVERIFIED')
    evidence['window_check'] = ('VERIFIED' if classic and owner and phone
                                else 'OWNER_TYPE_OR_SELECTED_APP_NOT_VERIFIED')
    return classic and owner and phone


def inspect_app_overlay_readonly(xml, focus, windows, resources, *,
                                 target_package=CLONE):
    """Sanitize a system-owned APP_OVERLAY; never authorize a click.

    Unlike the AOSP TYPE_PHONE compatibility warning, a 2038 overlay cannot
    be interpreted as page-size consent merely by an Android UID/title.
    Compare a single Android framework message to *runtime-resolved* resource
    values without returning the message, identity, classes or coordinates.
    No system interaction is available from this helper.
    """
    evidence = {}
    _system_phone_window(focus, windows, evidence,
                         target_package=target_package)
    if (evidence.get('focused_app') != 'CLASSIC'
            or evidence.get('window_binding') != 'MATCHED'
            or evidence.get('window_owner') != 'ANDROID_SYSTEM'
            or evidence.get('window_type_detail') != 'APP_OVERLAY'):
        return {'status': 'OVERLAY_WINDOW_NOT_VERIFIED',
                'interaction_performed': False}
    result = {'status': 'SYSTEM_APP_OVERLAY_UNCLASSIFIED',
              'interaction_performed': False,
              'raw_ui_logged': False,
              'framework_page_size_message': 'UNVERIFIED',
              'system_message_node': False,
              'system_positive_button_node': False,
              'clickable_count_category': 'UNVERIFIED'}
    if (type(xml) is not str or not 0 < len(xml) <= MAX_TEXT
            or '<!DOCTYPE' in xml.upper() or '<!ENTITY' in xml.upper()
            or type(resources) is not dict):
        result['status'] = 'OVERLAY_UI_INVALID'
        return result
    start, end = xml.find('<?xml'), xml.find('</hierarchy>')
    if start < 0 or end < start:
        result['status'] = 'OVERLAY_UI_INVALID'
        return result
    try:
        root = ET.fromstring(xml[start:end + len('</hierarchy>')])
    except (ET.ParseError, ValueError):
        result['status'] = 'OVERLAY_UI_INVALID'
        return result
    nodes = list(root.iter('node'))
    if (root.tag != 'hierarchy' or not 1 <= len(nodes) <= 1000
            or any(n.get('package') != 'android' for n in nodes)):
        result['status'] = 'OVERLAY_UI_PACKAGE_UNVERIFIED'
        return result
    messages = [n for n in nodes if n.get('resource-id') == 'android:id/message']
    buttons = [n for n in nodes if n.get('resource-id') == 'android:id/button1']
    result['system_message_node'] = len(messages) == 1
    result['system_positive_button_node'] = len(buttons) == 1
    clickable = sum(n.get('clickable') == 'true' for n in nodes)
    result['clickable_count_category'] = (
        'NONE' if clickable == 0 else 'ONE' if clickable == 1 else 'MULTIPLE')
    if len(messages) != 1:
        result['status'] = 'NO_UNIQUE_ANDROID_MESSAGE_NODE'
        return result
    message = ' '.join(messages[0].get('text', '').split())
    readable = [rendered_resource(resources.get(key)) for key in RESOURCES]
    readable = [s for s in readable if s and len(s) >= 32]
    if not readable:
        result['status'] = 'FRAMEWORK_PAGE_SIZE_RESOURCES_UNAVAILABLE'
        return result
    match = sum(s == message for s in readable)
    result['framework_page_size_message'] = (
        'EXACT' if match == 1 else 'NO_MATCH' if match == 0 else 'AMBIGUOUS')
    result['status'] = ('FRAMEWORK_PAGE_SIZE_MESSAGE_MATCH_NO_ACTION'
                        if match == 1 else 'NOT_AOSP_PAGE_SIZE_MESSAGE')
    return result


def warning_action(xml, focus, windows, display, page_size, resources,
                   diagnostic=None, *, target_package=ORIGINAL):
    """Return (point, fixed reason) only for the exact verified system warning."""
    evidence = diagnostic if type(diagnostic) is dict else {}
    evidence['decision'] = 'INPUT_OR_WINDOW_NOT_VERIFIED'
    if (type(target_package) is not str or target_package not in ALLOWED_TARGETS
            or type(xml) is not str or not 0 < len(xml) <= MAX_TEXT
            or type(display) is not str or len(display) > 10000
            or page_size != "16384" or type(resources) is not dict
            or not _system_phone_window(focus, windows, evidence,
                                         target_package=target_package)):
        return None
    evidence['decision'] = 'HIERARCHY_NOT_VERIFIED'
    if "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper():
        return None
    start, end = xml.find("<?xml"), xml.find("</hierarchy>")
    if start < 0 or end < start:
        return None
    try:
        root = ET.fromstring(xml[start:end + len("</hierarchy>")])
    except (ET.ParseError, ValueError):
        return None
    nodes = list(root.iter("node"))
    if (root.tag != "hierarchy" or not 1 <= len(nodes) <= 100
            or any(n.get("package") != "android" for n in nodes)):
        return None
    messages = [n for n in nodes if n.get("resource-id") == "android:id/message"]
    buttons = [n for n in nodes if n.get("resource-id") in (
        "android:id/button1", "android:id/button2", "android:id/button3")]
    evidence['message_count'] = min(len(messages), 2)
    evidence['button_count'] = min(len(buttons), 4)
    evidence['decision'] = 'MESSAGE_OR_BUTTON_NOT_UNIQUE'
    if len(messages) != 1 or len(buttons) != 1:
        return None
    message = " ".join(messages[0].get("text", "").split())
    matched = [RESOURCES[key] for key in RESOURCES
               if len(message) >= 32 and rendered_resource(resources.get(key)) == message]
    button = buttons[0]
    ok = rendered_resource(resources.get("ok"))
    evidence['message_match'] = ('EXACT' if len(matched) == 1 else
                                 'NONE' if not matched else 'AMBIGUOUS')
    evidence['ok_resource_readable'] = bool(ok)
    evidence['positive_text_matches'] = bool(ok and button.get('text') == ok)
    evidence['decision'] = 'MESSAGE_OR_POSITIVE_BUTTON_NOT_VERIFIED'
    if (len(matched) != 1 or not ok
            or button.get("resource-id") != "android:id/button1"
            or button.get("text") != ok
            or button.get("class") != "android.widget.Button"
            or button.get("clickable") != "true" or button.get("enabled") != "true"):
        return None
    # Only the documented help link and the positive button can be interactive.
    evidence['decision'] = 'EXTRA_INTERACTIVE_CONTROL'
    if any(n.get("clickable") == "true" and n not in (button, messages[0])
           for n in nodes):
        return None
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", button.get("bounds", ""))
    evidence['decision'] = 'GEOMETRY_NOT_VERIFIED'
    sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", display)
    if not bounds or not sizes:
        return None
    width, height = map(int, sizes[-1])
    x1, y1, x2, y2 = map(int, bounds.groups())
    if not (200 <= width <= 6000 and 200 <= height <= 12000
            and 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
        return None
    evidence['decision'] = 'EXACT_WARNING_VERIFIED'
    return ((x1 + x2) // 2, (y1 + y2) // 2), matched[0]


def read_warning_action(xml, focus, display, run, diagnostic=None,
                        *, target_package=ORIGINAL):
    """Read-only Android shell probes. Failures are handled by the guarded caller."""
    evidence = diagnostic if type(diagnostic) is dict else {}
    evidence['decision'] = 'INPUT_INVALID'
    if (type(target_package) is not str or target_package not in ALLOWED_TARGETS
            or type(xml) is not str or len(xml) > MAX_TEXT):
        return None
    evidence['decision'] = 'PAGE_SIZE_READ_FAILED'
    page_size = run(["shell", "getconf", "PAGE_SIZE"]).strip()
    evidence['kernel_page_size'] = ('16K' if page_size == '16384' else
                                   '4K' if page_size == '4096' else 'OTHER')
    evidence['decision'] = 'PAGE_SIZE_NOT_16K'
    if page_size != "16384":
        return None
    windows = run(["shell", "dumpsys", "window", "windows"])
    evidence['decision'] = 'WINDOW_NOT_VERIFIED'
    if not _system_phone_window(focus, windows, evidence,
                                 target_package=target_package):
        return None
    evidence['decision'] = 'FRAMEWORK_RESOURCE_READ_FAILED'
    resources = read_framework_resources(run, evidence)
    evidence['required_framework_resources_readable'] = (
        'ok' in resources and any(name in resources for name in RESOURCES))
    if not evidence['required_framework_resources_readable']:
        evidence['decision'] = 'FRAMEWORK_RESOURCES_UNAVAILABLE'
        return None
    return warning_action(xml, focus, windows, display, page_size, resources,
                          evidence, target_package=target_package)
