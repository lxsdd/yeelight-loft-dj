#!/usr/bin/env python3
"""One read-only, fail-closed original Classic music-device click bridge.

Only the SHA-pinned PRIVATE clone's original DiscoFragment$c onClick, where the
original SDK device key is already passed to the original GroupMusic activity.
No OEM bytes, key, user data or APK may be sent to public CI or GitHub.
The original OEM click and all group/device/network actions stay unchanged.
"""
from pathlib import Path
import re
from classic_first import GateFailure

SOURCE_PATH = Path(
    "smali_classes2/com/yeelight/cherry/ui/fragment/DiscoFragment$c.smali"
)
OWNER = "Lcom/yeelight/cherry/ui/fragment/DiscoFragment$c;"
DEVICE = "Lcom/yeelight/yeelib/device/base/c;->G()Ljava/lang/String;"
ORIGINAL_SELECTION = (
    "Lcom/yeelight/cherry/ui/fragment/DiscoFragment;->z("
    "Lcom/yeelight/cherry/ui/fragment/DiscoFragment;)"
    "Lcom/yeelight/yeelib/device/base/e;"
)
BRIDGE = (
    "Lio/github/lxsdd/yeelightclassicdj/ui/"
    "ClassicOriginalMusicSelection;->observedFromOriginalDevice(Ljava/lang/String;)V"
)
METHOD = re.compile(
    r"(?ms)^\.method[ \t]+[^\r\n]*\bonClick\(Landroid/view/View;\)V"
    r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$"
)
GETTER = re.compile(
    r"(?m)^(?P<indent>[ \t]*)invoke-virtual\s+"
    r"\{(?P<receiver>[vp]\d+)\},\s*" + re.escape(DEVICE) + r"[ \t]*\r?\n"
    r"(?:(?:[ \t]*\.(?:line|local)\b[^\r\n]*\r?\n)|(?:[ \t]*\r?\n))*"
    r"(?P=indent)move-result-object\s+(?P<result>[vp]\d+)[ \t]*\r?$"
)


def patch_text(source: str) -> str:
    if type(source) is not str or not 0 < len(source) < 1_000_000:
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_INPUT_INVALID")
    if len(re.findall(
        r"(?m)^\.class\s+[^\r\n]*" + re.escape(OWNER) + r"\s*$", source
    )) != 1:
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_OWNER_UNVERIFIED")
    if BRIDGE in source:
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_ALREADY_PATCHED")
    candidates = list(METHOD.finditer(source))
    if len(candidates) != 1:
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_CLICK_UNVERIFIED")
    match = candidates[0]
    body = match.group()
    if (body.count(DEVICE) != 1 or
        body.count(ORIGINAL_SELECTION) != 1 or
        body.count("Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)") != 1 or
        body.count("->startActivity(Landroid/content/Intent;)V") != 1):
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_CALLCHAIN_UNVERIFIED")
    getter = list(GETTER.finditer(body))
    if len(getter) != 1:
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_RESULT_UNVERIFIED")
    m = getter[0]
    if (body.index(ORIGINAL_SELECTION) >= m.start() or
        body.index("->putExtra(Ljava/lang/String;Ljava/lang/String;)") <= m.end() or
        body.index("->startActivity(Landroid/content/Intent;)V") <= m.end()):
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_ORDER_UNVERIFIED")
    register = m.group("result")
    # /range supports any register index; consume the ORIGINAL move-result.
    # No new v-register, branch, setter, device request or OEM callback.
    added = (
        "\n" + m.group("indent") + "invoke-static/range {" + register +
        " .. " + register + "}, " + BRIDGE
    )
    changed_body = body[:m.end()] + added + body[m.end():]
    result = source[:match.start()] + changed_body + source[match.end():]
    if result.count(BRIDGE) != 1 or result.count(DEVICE) != 1:
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_PATCH_RECHECK_FAILED")
    return result


def patch_staged_clone(staged: Path) -> dict:
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("CLASSIC_MUSIC_ORIGINAL_SELECTION_SMALI_MISSING")
    original = path.read_text(encoding="utf-8")
    changed = patch_text(original)
    path.write_text(changed, encoding="utf-8")
    return {
        "status": "PRIVATE_CLASSIC_ORIGINAL_SELECTION_READONLY_HOOK_STAGED",
        "original_oem_methods_modified": 1,
        "original_music_click_preserved": True,
        "original_group_activity_preserved": True,
        "device_id_or_address_exported": False,
        "participant_authorized": False,
        "device_or_scene_writes": 0,
        "runtime_ui_verified": False,
    }
