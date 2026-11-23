#!/usr/bin/env python3
"""Clone-only, private, pinned original Scene/Room List adapter order hooks.

Scene model t.l() is the original scene-manager lookup key; room model
y3.a.n() is used by the original room manager lookup. A read-through List
view changes only display order and retains original model objects/clicks.
Never publish OEM bytecode, read/write original databases or control a bulb.
"""
from pathlib import Path
import re
from classic_first import GateFailure

SCENE = Path("smali_classes2/com/yeelight/cherry/ui/fragment/SceneRecyclerViewAdapter.smali")
ROOM = Path("smali_classes2/com/yeelight/cherry/ui/adapter/RoomRecyclerViewAdapter.smali")
BRIDGE = "Lio/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalListOrder;"
SUPER = "Landroidx/recyclerview/widget/RecyclerView$Adapter;-><init>()V"
CONSTRUCTOR = "<init>(Ljava/util/List;)V"
SCENE_BIND = "a(Lcom/yeelight/cherry/ui/fragment/SceneRecyclerViewAdapter$ViewHolder;I)V"
ROOM_BIND = "a(Lcom/yeelight/cherry/ui/adapter/RoomRecyclerViewAdapter$ViewHolder;I)V"

def method(source, signature):
    matches=list(re.finditer(
        r"(?ms)^\.method[ \t]+[^\r\n]*[ \t]" + re.escape(signature) +
        r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$", source))
    if len(matches)!=1:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_METHOD_DRIFT")
    return matches[0]

def patch_single(source, owner, bind_signature, wrap_name, bind_name):
    if type(source) is not str or not 0 < len(source) < 1_000_000:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_SOURCE_INVALID")
    if len(re.findall(r"(?m)^\.class\s+[^\r\n]*" +
                      re.escape(owner) + r"\s*$", source)) != 1:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_OWNER_DRIFT")
    if BRIDGE in source:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_ALREADY_PATCHED")
    initial=method(source,CONSTRUCTOR)
    body=initial.group()
    if len(re.findall(r"(?m)^[ \\t]*return-void[ \\t]*$",body))!=1:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_CONSTRUCTOR_EXIT_DRIFT")
    # Requires exact one actual RecyclerView.Adapter() super-init; no guessed
    # register allocation or altered OEM field assignment.
    matches=list(re.finditer(
        r"(?m)^(?P<indent>[ \t]*)invoke-direct \{p0\}, " +
        re.escape(SUPER) + r"[ \t]*$",body))
    if len(matches)!=1 or body.count(SUPER)!=1:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_SUPER_DRIFT")
    super_call=matches[0]
    invocation=(
        "\n" + super_call.group("indent") +
        "invoke-static/range {p0 .. p1}, " + BRIDGE + "->" + wrap_name +
        "(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;" +
        "\n" + super_call.group("indent") + "move-result-object p1")
    changed=source[:initial.start()]+body[:super_call.end()]+invocation+body[super_call.end():]+source[initial.end():]
    binder=method(changed,bind_signature)
    binder_text=binder.group()
    # Actual original Scene/Room row binders use List.get(int), not a
    # guessed field/index/scene name. Preserve all original get calls.
    found=binder_text.count("Ljava/util/List;->get(I)Ljava/lang/Object;")
    if not 1 <= found <= 2:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_BINDER_MODEL_DRIFT")
    returns=list(re.finditer(r"(?m)^(?P<indent>[ \t]*)return-void[ \t]*$",binder_text))
    if not 1 <= len(returns) <= 3:
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_ROW_EXITS_DRIFT")
    hook=BRIDGE+"->"+bind_name+"(Ljava/lang/Object;Ljava/lang/Object;I)V"
    for exit in reversed(returns):
        binder_text=(binder_text[:exit.start()]+exit.group("indent")+
                     "invoke-static/range {p0 .. p2}, "+hook+"\n"+
                     binder_text[exit.start():])
    changed=(changed[:binder.start()]+binder_text+changed[binder.end():])
    if changed.count("->"+wrap_name+"(")!=1 or changed.count(hook)!=len(returns):
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_RECHECK")
    return changed

def patch_text(scene, room):
    return (
        patch_single(scene,
            "Lcom/yeelight/cherry/ui/fragment/SceneRecyclerViewAdapter;",
            SCENE_BIND,"wrapScene","bindSceneRow"),
        patch_single(room,
            "Lcom/yeelight/cherry/ui/adapter/RoomRecyclerViewAdapter;",
            ROOM_BIND,"wrapRoom","bindRoomRow")
    )

def patch_staged_clone(staged: Path):
    paths=(staged/SCENE,staged/ROOM)
    if any(not path.is_file() or path.is_symlink() for path in paths):
        raise GateFailure("CLASSIC_ORIGINAL_MODEL_ADAPTER_PRIVATE_SOURCE_MISSING")
    source=[path.read_text(encoding="utf-8") for path in paths]
    changed=patch_text(*source)
    # All validation before staged private OEM Smali is touched.
    for path,data in zip(paths,changed):
        path.write_text(data,encoding="utf-8")
    return {
        "status":"CLASSIC_ORIGINAL_SCENE_ROOM_DND_LIST_VIEWS_STAGED",
        "original_model_adapter_types":2,
        "original_model_or_database_writes":0,
        "runtime_ui_verified":False,
        "pixel_acceptance":False
    }
