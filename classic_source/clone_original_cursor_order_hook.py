#!/usr/bin/env python3
"""Private clone-only original Device/Group cursor order hooks.

The pinned original adapter and its OEM database/query/click flow are retained.
A source-only, exact-method patch wraps a verified cursor and adds an optional
on-row drag handle. Never distribute OEM smali or original APK data.
"""
from pathlib import Path
import re
from classic_first import GateFailure

FOLDER = Path("smali_classes2/com/yeelight/cherry/ui/fragment")
DEVICE = FOLDER / "DeviceRecyclerViewAdapter.smali"
GROUP = FOLDER / "GroupRecyclerViewAdapter.smali"
BASE = "Lcom/yeelight/cherry/ui/adapter/RecyclerViewCursorAdapter;"
BRIDGE = "Lio/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalCursorOrder;"
BIND = BRIDGE + "->bindRow(Ljava/lang/Object;Ljava/lang/Object;Landroid/database/Cursor;)V"
ROW_METHOD = "c(Landroidx/recyclerview/widget/RecyclerView$ViewHolder;Landroid/database/Cursor;)V"
INIT = "<init>(Landroid/content/Context;Landroid/database/Cursor;)V"
DEVICE_INIT = "<init>(Landroid/content/Context;Landroid/database/Cursor;Z)V"

def method(source, signature):
    regex = re.compile(
        r"(?ms)^\.method[ \t]+[^\r\n]*[ \t]" + re.escape(signature) +
        r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$")
    matches = list(regex.finditer(source))
    if len(matches) != 1:
        raise GateFailure("CLASSIC_OEM_CURSOR_METHOD_NOT_UNIQUE")
    return matches[0]

def constructor(source, signature, wrapper):
    m = method(source, signature)
    body = m.group()
    # The original OEM may use .locals 0/1/10+ or .registers.
    # This wrapper only uses named parameters p1/p2, never a new v-register.
    declarations = list(re.finditer(
        r"(?m)^(?P<indent>[ \t]*)\.(?P<kind>locals|registers)[ \t]+"
        r"(?P<count>0|[1-9][0-9]*)[ \t]*\r?$", body))
    if len(declarations) != 1:
        raise GateFailure("CLASSIC_OEM_CURSOR_REGISTERS_DRIFT")
    decl = declarations[0]
    count = int(decl.group("count"))
    parameters = 4 if signature == DEVICE_INIT else 3
    if count > 65535 or (decl.group("kind") == "registers" and count < parameters):
        raise GateFailure("CLASSIC_OEM_CURSOR_REGISTERS_DRIFT")
    target = BASE + "->" + INIT
    calls = list(re.finditer(
        r"(?m)^[ \t]*invoke-direct(?:/range)? \{p0, p1, p2\}, " +
        re.escape(target) + r"[ \t]*\r?$", body))
    if len(calls) != 1 or body.count(target) != 1:
        raise GateFailure("CLASSIC_OEM_CURSOR_SUPER_DRIFT")
    before = body[decl.end():calls[0].start()]
    # Original 3.5.4 constructor must be a direct super call. No register
    # mutations of p1/p2 or invocation of unknown OEM code may precede it.
    if re.search(r"(?m)^\s*(?:invoke-|move|iput|iget|new-|return-|const)", before):
        raise GateFailure("CLASSIC_OEM_CURSOR_PRE_SUPER_DRIFT")
    injected = (
        "\n" + decl.group("indent") +
        "invoke-static/range {p1 .. p2}, " + BRIDGE + "->" + wrapper +
        "(Landroid/content/Context;Landroid/database/Cursor;)Landroid/database/Cursor;\n" +
        decl.group("indent") + "move-result-object p2"
    )
    revised = body[:decl.end()] + injected + body[decl.end():]
    if revised.count("->" + wrapper + "(") != 1:
        raise GateFailure("CLASSIC_OEM_CURSOR_INIT_RECHECK")
    return source[:m.start()] + revised + source[m.end():]

def bind_row(source):
    m = method(source, ROW_METHOD)
    body = m.group()
    if BIND in body:
        raise GateFailure("CLASSIC_OEM_CURSOR_ALREADY_PATCHED")
    returns = list(re.finditer(r"(?m)^(?P<indent>[ \t]*)return-void[ \t]*\r?$", body))
    if not 1 <= len(returns) <= 3:
        raise GateFailure("CLASSIC_OEM_CURSOR_ROW_EXITS_DRIFT")
    for r in reversed(returns):
        body = (body[:r.start()] + r.group("indent") +
                "invoke-static/range {p0 .. p2}, " + BIND + "\n" +
                body[r.start():])
    if body.count(BIND) != len(returns):
        raise GateFailure("CLASSIC_OEM_CURSOR_ROW_RECHECK")
    return source[:m.start()] + body + source[m.end():]

def patch_text(device, group):
    if any(type(src) is not str or not 0 < len(src) < 1_000_000
           for src in (device, group)):
        raise GateFailure("CLASSIC_OEM_CURSOR_INVALID_INPUT")
    out = []
    for source, original_name, wrapper in (
        (device, "DeviceRecyclerViewAdapter", "wrapDevices"),
        (group, "GroupRecyclerViewAdapter", "wrapGroups")
    ):
        owner = "Lcom/yeelight/cherry/ui/fragment/" + original_name + ";"
        if len(re.findall(r"(?m)^\.class[ \t]+[^\r\n]*" +
                          re.escape(owner) + r"[ \t]*$", source)) != 1:
            raise GateFailure("CLASSIC_OEM_CURSOR_CLASS_DRIFT")
        if BRIDGE in source:
            raise GateFailure("CLASSIC_OEM_CURSOR_ALREADY_PATCHED")
        if wrapper == "wrapDevices":
            short = method(source, INIT).group()
            if short.count(owner + "->" + DEVICE_INIT) != 1:
                raise GateFailure("CLASSIC_OEM_CURSOR_DEVICE_DELEGATE_DRIFT")
        revised = constructor(source, DEVICE_INIT if wrapper == "wrapDevices"
                              else INIT, wrapper)
        revised = bind_row(revised)
        if revised.count("->" + wrapper + "(") != 1 or revised.count(BIND) < 1:
            raise GateFailure("CLASSIC_OEM_CURSOR_FINAL_RECHECK")
        out.append(revised)
    return tuple(out)

def patch_staged_clone(staged: Path):
    paths = [staged / DEVICE, staged / GROUP]
    if any(not p.is_file() or p.is_symlink() for p in paths):
        raise GateFailure("CLASSIC_OEM_CURSOR_PRIVATE_SOURCE_MISSING")
    originals = [p.read_text(encoding="utf-8") for p in paths]
    outputs = patch_text(*originals)
    # Validate both originals before mutating either staged private copy.
    for path, result in zip(paths, outputs):
        path.write_text(result, encoding="utf-8")
    return {
        "status": "CLASSIC_ORIGINAL_DEVICE_GROUP_CURSOR_DND_STAGED",
        "original_adapter_types": 2,
        "oem_database_or_scene_writes": 0,
        "device_actions": 0,
        "runtime_ui_verified": False,
        "pixel_acceptance": False,
    }
