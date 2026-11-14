#!/usr/bin/env python3
"""Classify a private OEM UI hierarchy without emitting labels or account data.

Called only after the unmodified SHA-pinned OEM Classic is already running
inside the existing owner-gated, network-isolated Android17 emulator. This
cannot navigate, tap, authenticate or change a device. Raw XML is not logged.
"""
import re
import xml.etree.ElementTree as ET

MAX_XML = 2_000_000
ORIGINAL = "com.yeelight.cherry"

class UiSurfaceBlocked(ValueError):
    """Fixed error codes; never embed private hierarchy data."""

def summarize(raw: str) -> dict:
    if type(raw) is not str or not (0 < len(raw) <= MAX_XML):
        raise UiSurfaceBlocked("OEM_UI_SNAPSHOT_INVALID")
    begin = raw.find("<hierarchy")
    if begin < 0:
        raise UiSurfaceBlocked("OEM_UI_HIERARCHY_MISSING")
    close = raw.rfind("</hierarchy>")
    if close < begin:
        raise UiSurfaceBlocked("OEM_UI_HIERARCHY_INCOMPLETE")
    data = raw[begin:close+len("</hierarchy>")]
    if "<!DOCTYPE" in raw.upper() or "<!ENTITY" in raw.upper():
        raise UiSurfaceBlocked("OEM_UI_XML_DECLARATION_DENIED")
    try:
        root = ET.fromstring(data)
    except (ET.ParseError, ValueError):
        raise UiSurfaceBlocked("OEM_UI_XML_INVALID") from None
    if root.tag != "hierarchy":
        raise UiSurfaceBlocked("OEM_UI_ROOT_INVALID")
    nodes = list(root.iter("node"))
    if not nodes or len(nodes) > 6000:
        raise UiSurfaceBlocked("OEM_UI_NODE_COUNT_INVALID")
    own = sum(x.attrib.get("package") == ORIGINAL for x in nodes)
    enabled_click = sum(x.attrib.get("clickable") == "true" and
                        x.attrib.get("enabled") == "true" for x in nodes)
    scrollable = sum(x.attrib.get("scrollable") == "true" for x in nodes)
    # Do not even inspect text/content-description or serialize resource ids.
    # XML hierarchy alone does not identify a Music screen, allow a UI hook,
    # or assert account/offline/transport functionality.
    return {
        "status": "ORIGINAL_UI_HIERARCHY_OBSERVED_NOT_FUNCTIONALLY_ACCEPTED",
        "hierarchy_node_count": len(nodes),
        "original_package_node_count": own,
        "enabled_clickable_node_count": enabled_click,
        "scrollable_node_count": scrollable,
        "original_ui_hierarchy_visible": own > 0,
        "original_music_screen_reached": False,
        "oem_music_method_bound": False,
        "account_state_examined": False,
        "sensitive_text_or_resource_ids_exported": False,
        "navigation_or_device_action_taken": False,
        "raw_hierarchy_published": False,
    }
