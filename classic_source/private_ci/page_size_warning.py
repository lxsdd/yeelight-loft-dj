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
import xml.etree.ElementTree as ET

ORIGINAL = "com.yeelight.cherry"
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
    """Mirror text-only Html.fromHtml/whitespace for a trusted framework value."""
    if type(value) is not str or not 0 < len(value) <= 8192:
        return None
    if "Error:" in value or " -> " in value:
        return None  # unresolved resource reference / shell error
    parser = _PlainText()
    try:
        parser.feed(value)
        parser.close()
    except (ValueError, TypeError):
        return None
    return " ".join("".join(parser.parts).split()) or None


def _system_phone_window(focus, windows):
    if any(type(x) is not str or len(x) > MAX_TEXT for x in (focus, windows)):
        return False
    current = re.findall(r"(?m)^[ \t]*mCurrentFocus=(Window\{[^\r\n]{1,300}\})[ \t]*$", focus)
    if len(re.findall(r"(?m)^[ \t]*mCurrentFocus=", focus)) != 1 or len(current) != 1:
        return False
    selected = re.findall(r"(?m)^[ \t]*mFocusedApp=([^\r\n]{1,512})[ \t]*$", focus)
    if len(selected) != 1 or not re.search(
            r"(?<![A-Za-z0-9_.])com\.yeelight\.cherry/[^\s}]+", selected[0]):
        return False
    # Bind owner/type to THIS focused Window token, not a different window
    # somewhere in a large dump. Duplicates and incomplete dumps fail closed.
    headers = list(re.finditer(
        r"(?m)^[ \t]*Window #\d+ (Window\{[^\r\n]{1,300}\}):[ \t]*$", windows))
    target = [i for i, match in enumerate(headers) if match.group(1) == current[0]]
    if len(target) != 1:
        return False
    i = target[0]
    end = headers[i + 1].start() if i + 1 < len(headers) else len(windows)
    body = windows[headers[i].end():end]
    owners = re.findall(r"(?m)^[ \t]*mOwnerUid=(\d+)\b[^\r\n]*\bpackage=([^\s]+)", body)
    attrs = re.findall(r"\bmAttrs=\{([^}]{1,8192})\}", body)
    return (owners == [("1000", "android")] and len(attrs) == 1
            and re.findall(r"\bty=([^\s}]+)", attrs[0]) in (["PHONE"], ["2002"]))


def warning_action(xml, focus, windows, display, page_size, resources):
    """Return (point, fixed reason) only for the exact verified system warning."""
    if (type(xml) is not str or not 0 < len(xml) <= MAX_TEXT
            or type(display) is not str or len(display) > 10000
            or page_size != "16384" or type(resources) is not dict
            or not _system_phone_window(focus, windows)):
        return None
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
    if len(messages) != 1 or len(buttons) != 1:
        return None
    message = " ".join(messages[0].get("text", "").split())
    matched = [RESOURCES[key] for key in RESOURCES
               if len(message) >= 32 and rendered_resource(resources.get(key)) == message]
    button = buttons[0]
    ok = rendered_resource(resources.get("ok"))
    if (len(matched) != 1 or not ok
            or button.get("resource-id") != "android:id/button1"
            or button.get("text") != ok
            or button.get("class") != "android.widget.Button"
            or button.get("clickable") != "true" or button.get("enabled") != "true"):
        return None
    # Only the documented help link and the positive button can be interactive.
    if any(n.get("clickable") == "true" and n not in (button, messages[0])
           for n in nodes):
        return None
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", button.get("bounds", ""))
    sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", display)
    if not bounds or not sizes:
        return None
    width, height = map(int, sizes[-1])
    x1, y1, x2, y2 = map(int, bounds.groups())
    if not (200 <= width <= 6000 and 200 <= height <= 12000
            and 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
        return None
    return ((x1 + x2) // 2, (y1 + y2) // 2), matched[0]


def read_warning_action(xml, focus, display, run):
    """Read-only Android shell probes. Failures are handled by the guarded caller."""
    if type(xml) is not str or len(xml) > MAX_TEXT:
        return None
    page_size = run(["shell", "getconf", "PAGE_SIZE"]).strip()
    if page_size != "16384":
        return None
    windows = run(["shell", "dumpsys", "window", "windows"])
    if not _system_phone_window(focus, windows):
        return None
    resources = {name: run(["shell", "cmd", "overlay", "lookup", "android",
                            "android:string/" + name])
                 for name in (*RESOURCES, "ok")}
    return warning_action(xml, focus, windows, display, page_size, resources)
