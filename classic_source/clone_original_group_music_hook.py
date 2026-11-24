#!/usr/bin/env python3
"""Read-only original GroupMusic manager group-list bridge for the PRIVATE clone.

The authenticated original device click remains the gate. At the verified
GroupMusic Activity onCreate key lookup and a0() member-list read, observe
only the opaque original key and List.size(). No member IDs, IPs, selected
state, signals, telemetry, commands, OEM code or user files are exported.
Source only; fails closed against any unverified original instruction shape.
"""
from pathlib import Path
import re
from classic_first import GateFailure

SOURCE_PATH = Path(
    "smali_classes2/com/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity.smali"
)
OWNER = "Lcom/yeelight/yeelib/ui/activity/GroupMusicModeManagerActivity;"
ORIGINAL_GROUP_LOOKUP = (
    "Lcom/yeelight/yeelib/managers/YeelightDeviceManager;->j0(Ljava/lang/String;)"
    "Lcom/yeelight/yeelib/device/base/c;"
)
ORIGINAL_MEMBERS = "Lcom/yeelight/yeelib/device/c;->J1()Ljava/util/List;"
ENTRY_BRIDGE = (
    "Lio/github/lxsdd/yeelightclassicdj/ui/"
    "ClassicOriginalMusicSelection;->observedOriginalGroupEntry(Ljava/lang/String;)V"
)
MEMBERS_BRIDGE = (
    "Lio/github/lxsdd/yeelightclassicdj/ui/"
    "ClassicOriginalMusicSelection;->observedOriginalGroupMembers(Ljava/util/List;)V"
)
INTENT_GET = (
    "Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;"
)
ORIGINAL_SET_ADAPTER = (
    "Landroid/widget/ListView;->setAdapter(Landroid/widget/ListAdapter;)V"
)
ORDER_BRIDGE = (
    "Lio/github/lxsdd/yeelightclassicdj/ui/"
    "ClassicOriginalGroupMemberOrder;->installGroupAdapter"
    "(Landroid/widget/ListView;Landroid/widget/ListAdapter;)V"
)


def _replace_original_adapter_install(body: str) -> str:
    # The static bridge takes exactly the original ListView and ListAdapter
    # registers. No move, spare register or OEM member access is introduced.
    pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)(?P<op>invoke-virtual(?:/range)?)[ \t]+"
        r"(?P<regs>\{[^}\r\n]+\}),[ \t]*" +
        re.escape(ORIGINAL_SET_ADAPTER) + r"[ \t]*\r?$"
    )
    found = list(pattern.finditer(body))
    if len(found) != 1:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_LIST_ADAPTER_ABI_UNVERIFIED")
    item = found[0]
    regs = item.group("regs")
    if item.group("op").endswith("/range"):
        match = re.fullmatch(r"\{([vp])(\d+)[ \t]*\.\.[ \t]*([vp])(\d+)\}", regs)
        if not match or match.group(1) != match.group(3) or \
                int(match.group(4)) != int(match.group(2)) + 1:
            raise GateFailure("CLASSIC_GROUP_ORIGINAL_LIST_REGISTERS_UNVERIFIED")
        opcode = "invoke-static/range"
    else:
        if not re.fullmatch(r"\{[vp]\d+,[ \t]*[vp]\d+\}", regs):
            raise GateFailure("CLASSIC_GROUP_ORIGINAL_LIST_REGISTERS_UNVERIFIED")
        opcode = "invoke-static"
    replacement = item.group("indent") + opcode + " " + regs + ", " + ORDER_BRIDGE
    return body[:item.start()] + replacement + body[item.end():]



def _method(source: str, name: str):
    pattern = re.compile(
        r"(?ms)^\.method[ \t]+[^\r\n]*\b" + re.escape(name) +
        r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$"
    )
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_METHOD_NOT_UNIQUE")
    return matches[0]


def _result_after(body: str, callee: str, occurrence: int = 1):
    pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)invoke-virtual(?:/range)?[ \t]+"
        r"\{[^}]+\},[ \t]*" + re.escape(callee) + r"[ \t]*\r?\n"
        r"(?:(?:[ \t]*\.(?:line|local)\b[^\r\n]*\r?\n)|"
        r"(?:[ \t]*\r?\n))*"
        r"(?P=indent)move-result-object[ \t]+(?P<result>[vp]\d+)[ \t]*\r?$"
    )
    found = list(pattern.finditer(body))
    if len(found) != occurrence:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_RESULT_UNVERIFIED")
    return found[0]


def patch_text(source: str) -> str:
    if not isinstance(source, str) or not 0 < len(source) < 1_000_000:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_INPUT_INVALID")
    if len(re.findall(r"(?m)^\.class\s+[^\r\n]*" +
                      re.escape(OWNER) + r"\s*$", source)) != 1:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_OWNER_UNVERIFIED")
    if ENTRY_BRIDGE in source or MEMBERS_BRIDGE in source:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_ALREADY_PATCHED")
    entry = _method(source, "onCreate(Landroid/os/Bundle;)V")
    entry_body = entry.group()
    if (entry_body.count(INTENT_GET) != 1 or
        entry_body.count(ORIGINAL_GROUP_LOOKUP) != 1 or
        "->a0()V" not in entry_body):
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_ENTRY_CHAIN_UNVERIFIED")
    key = _result_after(entry_body, INTENT_GET)
    if (key.end() > entry_body.index(ORIGINAL_GROUP_LOOKUP) or
        entry_body.index(ORIGINAL_GROUP_LOOKUP) > entry_body.index("->a0()V")):
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_ENTRY_ORDER_UNVERIFIED")
    member_method = _method(source, "a0()V")
    body = member_method.group()
    # SHA-pinned private 3.5.4 atlas: J1() is called four times in a0(),
    # once in onPause() and once in onResume(). Check ALL six callsites.
    pause_body = _method(source, "onPause()V").group()
    resume_body = _method(source, "onResume()V").group()
    if (body.count(ORIGINAL_MEMBERS) != 4 or
        pause_body.count(ORIGINAL_MEMBERS) != 1 or
        resume_body.count(ORIGINAL_MEMBERS) != 1 or
        source.count(ORIGINAL_MEMBERS) != 6):
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_MEMBER_CHAIN_UNVERIFIED")
    first = _result_after_first(body, ORIGINAL_MEMBERS)
    key_call = (
        "\n" + key.group("indent") + "invoke-static/range {" +
        key.group("result") + " .. " + key.group("result") + "}, " + ENTRY_BRIDGE
    )
    member_call = (
        "\n" + first.group("indent") + "invoke-static/range {" +
        first.group("result") + " .. " + first.group("result") + "}, " + MEMBERS_BRIDGE
    )
    # Replace later block first, preserving each original callsite offset.
    changed = (source[:member_method.start()] +
               body[:first.end()] + member_call + body[first.end():] +
               source[member_method.end():])
    refreshed = _method(changed, "onCreate(Landroid/os/Bundle;)V")
    refreshed_body = refreshed.group()
    refreshed_key = _result_after(refreshed_body, INTENT_GET)
    changed = (changed[:refreshed.start()] +
               refreshed_body[:refreshed_key.end()] + key_call +
               refreshed_body[refreshed_key.end():] +
               changed[refreshed.end():])
    entry_now = _method(changed, "onCreate(Landroid/os/Bundle;)V")
    entry_body = _replace_original_adapter_install(entry_now.group())
    changed = changed[:entry_now.start()] + entry_body + changed[entry_now.end():]
    if (changed.count(ENTRY_BRIDGE) != 1 or
        changed.count(MEMBERS_BRIDGE) != 1 or
        changed.count(ORDER_BRIDGE) != 1 or
        ORIGINAL_SET_ADAPTER in changed or
        changed.count(ORIGINAL_MEMBERS) != 6 or
        _method(changed, "a0()V").group().count(ORIGINAL_MEMBERS) != 4 or
        _method(changed, "onPause()V").group() != pause_body or
        _method(changed, "onResume()V").group() != resume_body or
        changed.count(ORIGINAL_GROUP_LOOKUP) != 1):
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_RECHECK_FAILED")
    return changed


def _result_after_first(body: str, callee: str):
    # The other J1() calls need not use an identical destination register.
    pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)invoke-virtual(?:/range)?[ \t]+"
        r"\{[^}]+\},[ \t]*" + re.escape(callee) + r"[ \t]*\r?\n"
        r"(?:(?:[ \t]*\.(?:line|local)\b[^\r\n]*\r?\n)|"
        r"(?:[ \t]*\r?\n))*"
        r"(?P=indent)move-result-object[ \t]+(?P<result>[vp]\d+)[ \t]*\r?$"
    )
    found = list(pattern.finditer(body))
    if len(found) != 4:
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_FIRST_MEMBER_RESULT_UNVERIFIED")
    return found[0]


def patch_staged_clone(staged: Path) -> dict:
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("CLASSIC_GROUP_ORIGINAL_SMALI_MISSING")
    original = path.read_text(encoding="utf-8")
    changed = patch_text(original)
    path.write_text(changed, encoding="utf-8")
    return {
        "status": "PRIVATE_CLASSIC_ORIGINAL_GROUP_COUNT_READONLY_HOOK_STAGED",
        "original_oem_methods_modified": 2,
        "original_group_entry_and_list_preserved": True,
        "original_member_display_order_adapter": True,
        "observed_selected_members": False,
        "participant_authorized": False,
        "device_id_or_address_exported": False,
        "device_or_scene_writes": 0,
        "runtime_ui_verified": False,
    }
