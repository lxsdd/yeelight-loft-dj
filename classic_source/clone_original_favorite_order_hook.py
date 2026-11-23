#!/usr/bin/env python3
"""Owner-private clone-only FavoriteSceneAdapter e(List)/row DnD integration.

The original constructor, adapter refresh, callbacks and favorite records stay
unaltered. No original SMALI/DEX or user data is exported; unverified bytecode
fails closed before touching the staged clone.
"""
from pathlib import Path
import re
from classic_first import GateFailure

FAVORITE = Path("smali_classes2/com/yeelight/yeelib/ui/adapter/FavoriteSceneAdapter.smali")
OWNER = "Lcom/yeelight/yeelib/ui/adapter/FavoriteSceneAdapter;"
BRIDGE = "Lio/github/lxsdd/yeelightclassicdj/ui/ClassicOriginalListOrder;"
CTOR = "<init>(Ljava/lang/String;ZLcom/yeelight/yeelib/models/v$g;)V"
SETTER = "e(Ljava/util/List;)V"
BINDER = "c(Lcom/yeelight/yeelib/ui/adapter/FavoriteSceneAdapter$FavoriteSceneVH;I)V"
SUPER = "Landroidx/recyclerview/widget/RecyclerView$Adapter;-><init>()V"
REGISTER = BRIDGE + "->registerFavoriteAdapter(Ljava/lang/Object;Ljava/lang/String;Z)V"
WRAP = BRIDGE + "->wrapFavorite(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;"
BIND = BRIDGE + "->bindFavoriteRow(Ljava/lang/Object;Ljava/lang/Object;I)V"

def _method(text, signature):
    pattern = r"(?ms)^\.method[ \t]+[^\\r\n]*[ \t]" + re.escape(signature) + r"[ \t]*\\r?$.*?^\.end method[ \t]*\\r?$"
    found = list(re.finditer(pattern, text))
    if len(found) != 1:
        raise GateFailure("CLASSIC_FAVORITE_ORIGINAL_METHOD_DRIFT")
    return found[0]

def _insert_after(body, needle, text, error):
    points = list(re.finditer(needle, body, re.MULTILINE))
    if len(points) != 1:
        raise GateFailure(error)
    hit = points[0]
    return body[:hit.end()] + "\n" + hit.group("indent") + text + body[hit.end():]

def patch_text(source):
    if type(source) is not str or not 0 < len(source) < 1_000_000:
        raise GateFailure("CLASSIC_FAVORITE_SOURCE_INVALID")
    if len(re.findall(r"(?m)^\.class[ \t]+[^\\r\n]*" + re.escape(OWNER) + r"[ \t]*$", source)) != 1:
        raise GateFailure("CLASSIC_FAVORITE_OWNER_DRIFT")
    if BRIDGE in source:
        raise GateFailure("CLASSIC_FAVORITE_ALREADY_PATCHED")
    ctor = _method(source, CTOR)
    body = ctor.group()
    if body.count(SUPER) != 1:
        raise GateFailure("CLASSIC_FAVORITE_SUPER_DRIFT")
    body = _insert_after(body,
        r"^(?P<indent>[ \t]*)invoke-direct \{p0\\}, " + re.escape(SUPER) + r"[ \t]*$",
        "invoke-static/range {p0 .. p2}, " + REGISTER,
        "CLASSIC_FAVORITE_CONSTRUCTOR_DRIFT")
    source = source[:ctor.start()] + body + source[ctor.end():]
    setter = _method(source, SETTER)
    body = setter.group()
    if body.count("Ljava/util/List;->clear()V") != 1 or \\
       body.count("Ljava/util/List;->addAll(Ljava/util/Collection;)Z") != 1 or \\
       body.count("->notifyDataSetChanged()V") != 1:
        raise GateFailure("CLASSIC_FAVORITE_REFRESH_DRIFT")
    body = _insert_after(body,
        r"^(?P<indent>[ \t]*)\.(?:locals|registers) [0-9]+[ \t]*$",
        "invoke-static/range {p0 .. p1}, " + WRAP +
        "\n" + "    move-result-object p1",
        "CLASSIC_FAVORITE_REGISTER_DRIFT")
    source = source[:setter.start()] + body + source[setter.end():]
    binder = _method(source, BINDER)
    body = binder.group()
    if body.count("Ljava/util/List;->get(I)Ljava/lang/Object;") != 1:
        raise GateFailure("CLASSIC_FAVORITE_BIND_MODEL_DRIFT")
    returns = list(re.finditer(r"(?m)^(?P<indent>[ \t]*)return-void[ \t]*$", body))
    if not 1 <= len(returns) <= 3:
        raise GateFailure("CLASSIC_FAVORITE_BIND_EXIT_DRIFT")
    for ret in reversed(returns):
        body = body[:ret.start()] + ret.group("indent") + \\
               "invoke-static/range {p0 .. p2}, " + BIND + "\n" + body[ret.start():]
    source = source[:binder.start()] + body + source[binder.end():]
    if source.count(REGISTER) != 1 or source.count(WRAP) != 1 or source.count(BIND) != len(returns):
        raise GateFailure("CLASSIC_FAVORITE_FINAL_RECHECK")
    return source

def patch_staged_clone(staged: Path):
    target = staged / FAVORITE
    if not target.is_file() or target.is_symlink():
        raise GateFailure("CLASSIC_FAVORITE_PRIVATE_SOURCE_MISSING")
    original = target.read_text(encoding="utf-8")
    changed = patch_text(original)
    target.write_text(changed, encoding="utf-8")
    return {
        "status": "CLASSIC_ORIGINAL_FAVORITES_LOCAL_DND_STAGED",
        "original_adapter_types": 1,
        "original_records_or_database_writes": 0,
        "device_actions": 0,
        "runtime_oem_verified": False,
        "pixel_acceptance": False,
    }
