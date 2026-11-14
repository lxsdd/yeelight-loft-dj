#!/usr/bin/env python3
"""Read-only original-Classic UI reuse inventory, executed inside private build.

The pinned OEM APK is verified by the owning build *before* its existing Apktool
resource (-s) and source (-r) decodes. This analyzer accepts those two original
decoded trees, never a modified candidate. Neither OEM symbols nor resources,
account text, or user data may be serialized to its allowlisted report.

IMPORTANT: class/layout existence and bytecode reference patterns are NOT
evidence of reachable controls, correct OEM screen semantics, or app acceptance.
"""
from __future__ import annotations

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAX_LAYOUT = 2_000_000
MAX_SMALI = 2_000_000
MAX_SMALI_FILES = 90_000
MAX_ROLE_SOURCES = 500

# Names here are already documented from the first owner-supplied APK audit.
# They are lookup hints, not inferred class owners, successful navigations
# or permission to reuse a private method without validating its behaviour.
ROLES = {
    "navigation": (("BottomTabView",), ("activity_main_new",)),
    "music": (("MusicActivity", "GroupMusicModeManagerActivity",
               "MusicModeNotificationService"), ("activity_music", "fragment_music")),
    "scenes": (("SceneFragment", "SceneBundleSettingActivity"),
               ("fragment_scene_list",)),
    "colors": (("ColorSelectView",), ("fragment_colorflow_color_select",)),
    "rooms": (("RoomFragment", "RoomMainActivity", "RoomDeviceModifyActivity"),
              ("fragment_room_list",)),
    "groups": (("GroupDeviceModifyActivity", "GroupRhythmActivity"),
               ("fragment_group_list",)),
    "devices": (("LocalActionExecutor", "Color4Service"),
                ("fragment_device_list",)),
}
FLOW_MARKERS = {
    "lifecycle_methods": re.compile(r"^\.method[^\n]*\s(?:onCreate|onCreateView|onResume|onStart)\(", re.M),
    "click_handlers": re.compile(r"^\.method[^\n]*\s(?:onClick|onItemClick)\(", re.M),
    "listener_wiring": re.compile(r"->setOn(?:Click|ItemClick)Listener\("),
    "view_inflation": re.compile(r"->(?:inflate|setContentView)\("),
    "navigation_calls": re.compile(r"->startActivity(?:ForResult)?\("),
    "action_dispatch_refs": re.compile(r"->(?:execute|setScene|setMusic|setRgb|setBright|setPower)\("),
}
_UI_TAGS = frozenset(("Button", "ImageButton", "CheckBox", "RadioButton",
                      "Switch", "SeekBar", "RecyclerView", "ListView"))

class UiAuditBlocked(ValueError):
    """Fixed, non-OEM diagnostic codes only."""

def need(ok: bool, code: str) -> None:
    if not ok:
        raise UiAuditBlocked(code)

def safe_regular(path: Path, maximum: int) -> bool:
    return (path.is_file() and not path.is_symlink() and
            path.stat().st_size <= maximum)

def _xml(path: Path):
    need(safe_regular(path, MAX_LAYOUT), "UI_XML_INPUT_INVALID")
    raw = path.read_bytes()
    need(b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper(),
         "UI_XML_DECLARATION_DENIED")
    try:
        return ET.fromstring(raw)
    except (ET.ParseError, ValueError):
        raise UiAuditBlocked("UI_XML_PARSE_INVALID") from None

def _classes(root: Path) -> tuple[dict[str, list[Path]], int]:
    found = {name: [] for names, _layouts in ROLES.values() for name in names}
    count = 0
    # Smali comes from the existing pinned Apktool -r original-only decode.
    for path in root.glob("smali*/**/*.smali"):
        count += 1
        need(count <= MAX_SMALI_FILES, "UI_SMALI_SCAN_LIMIT")
        need(not path.is_symlink(), "UI_SMALI_FILE_INVALID")
        stem = path.stem
        if stem in found:
            need(safe_regular(path, MAX_SMALI), "UI_SMALI_FILE_INVALID")
            found[stem].append(path)
            need(len(found[stem]) <= 4, "UI_SMALI_DUPLICATE_ORIGINAL_CLASS")
        elif "$" in stem and stem.split("$", 1)[0] in found:
            need(safe_regular(path, MAX_SMALI), "UI_SMALI_FILE_INVALID")
            found[stem.split("$", 1)[0]].append(path)
            need(len(found[stem.split("$", 1)[0]]) <= MAX_ROLE_SOURCES,
                 "UI_SMALI_ROLE_LIMIT")
    return found, count

def analyze(resources: Path, codeonly: Path) -> dict:
    """Original-only pure filesystem analysis: NEVER changes either input."""
    need(isinstance(resources, Path) and isinstance(codeonly, Path)
         and resources.is_dir() and codeonly.is_dir()
         and not resources.is_symlink() and not codeonly.is_symlink()
         and resources.resolve() != codeonly.resolve(),
         "UI_DECODED_ROOT_INVALID")
    manifest = _xml(resources / "AndroidManifest.xml")
    need(manifest.tag == "manifest" and
         manifest.attrib.get("package") == "com.yeelight.cherry",
         "UI_NOT_ORIGINAL_MANIFEST")
    application = manifest.find("application")
    need(application is not None, "UI_ORIGINAL_APPLICATION_MISSING")
    manifest_activities = {
        v for x in application.findall("activity")
        if (v := x.attrib.get(ANDROID + "name")) is not None
    }
    all_layouts: dict[str, list[Path]] = {}
    total_layouts = 0
    for folder in (resources / "res").glob("layout*"):
        need(folder.is_dir() and not folder.is_symlink(),
             "UI_LAYOUT_DIRECTORY_INVALID")
        for path in folder.glob("*.xml"):
            need(safe_regular(path, MAX_LAYOUT), "UI_LAYOUT_FILE_INVALID")
            total_layouts += 1
            need(total_layouts <= 15000, "UI_LAYOUT_COUNT_LIMIT")
            all_layouts.setdefault(path.stem, []).append(path)

    source_files, total_smali = _classes(codeonly)
    result = {
        "status": "ORIGINAL_UI_REUSE_CANDIDATES_STATIC_ONLY_NOT_RUNTIME_VERIFIED",
        "source": "PINNED_ORIGINAL_DECODED_BY_EXISTING_PRIVATE_BUILD",
        "original_apk_or_smali_published": False,
        "original_ui_methods_rewritten": False,
        "private_symbols_emitted": False,
        "account_or_device_data_examined": False,
        "runtime_ui_navigation_verified": False,
        "original_behavior_compatibility_verified": False,
        "manifest_activity_count": len(manifest_activities),
        "decoded_layout_count": total_layouts,
        "decoded_smali_file_count": total_smali,
        "roles": {},
    }
    for role, (class_names, layout_names) in ROLES.items():
        layouts = [path for name in layout_names for path in all_layouts.get(name, ())]
        node_count = 0
        ids = 0
        declared_callbacks = 0
        interactive = 0
        root_tags: set[str] = set()
        for path in layouts:
            root = _xml(path)
            root_tags.add(root.tag.split("}")[-1].split(".")[-1])
            for node in root.iter():
                node_count += 1
                need(node_count <= 15000, "UI_LAYOUT_NODE_LIMIT")
                if ANDROID + "id" in node.attrib: ids += 1
                if ANDROID + "onClick" in node.attrib: declared_callbacks += 1
                if node.tag.split("}")[-1].split(".")[-1] in _UI_TAGS:
                    interactive += 1
        methods = {key: 0 for key in FLOW_MARKERS}
        verified_source_classes: set[str] = set()
        manifest_matches = 0
        for name in class_names:
            candidates = source_files[name]
            # Exactly one canonical class source (rather than only its
            # anonymous callbacks) may count as a source-level definition.
            canonical = [p for p in candidates if p.stem == name]
            if len(canonical) == 1:
                c = canonical[0]
                blob = c.read_text("utf-8")
                # Re-validate descriptor path; no same-name third-party class.
                match = re.search(r"^\.class[^\n]*\s(L[^;]+;)", blob, re.M)
                if (match and match.group(1).startswith("Lcom/yeelight/") and
                        match.group(1).endswith("/" + name + ";")):
                    verified_source_classes.add(name)
                    fqcn = match.group(1)[1:-1].replace("/", ".")
                    if fqcn in manifest_activities: manifest_matches += 1
            for path in candidates:
                raw = path.read_text("utf-8")
                for key, pattern in FLOW_MARKERS.items():
                    methods[key] += min(9999, len(pattern.findall(raw)))
        # Keep route markers mere evidence counts; do not infer callbacks are
        # wired from an absent Activity/Fragment or that OEM actions are LAN.
        result["roles"][role] = {
            "original_layout_variants": len(layouts),
            "layout_root_shapes_observed": len(root_tags),
            "xml_control_nodes": node_count,
            "xml_nodes_with_ids": ids,
            "xml_click_attributes": declared_callbacks,
            "xml_interactive_widgets": interactive,
            "unique_original_class_definitions": len(verified_source_classes),
            "manifest_activity_definitions": manifest_matches,
            "source_reference_counts": {
                k: min(v, 9999) for k, v in methods.items()
            },
            "binding_state": (
                "STATIC_LAYOUT_AND_CLASS_CANDIDATES" if layouts and verified_source_classes
                else "CLASS_ONLY_LAYOUT_UNMAPPED" if verified_source_classes
                else "LAYOUT_ONLY_CODE_UNMAPPED" if layouts
                else "UNMAPPED_NOT_PROVEN"
            ),
            "production_hook_approved": False,
        }
    # The data returned is sanitized and can be placed in a PRIVATE local
    # preflight receipt, but it is never evidence for OEM UI launch acceptance.
    return result
