#!/usr/bin/env python3
"""One explicit original SceneFragment lifecycle bridge, private-clone only.

The owner-provided, SHA-pinned original APK is decoded only in the private
build. Only SceneFragment.onViewCreated/onStop are changed; the OEM Scene
adapter, model, onClick and database are untouched. No original bytes or
symbols are exported. Never invoke this against a user/device APK.
"""
from pathlib import Path
import re
from classic_first import GateFailure

SOURCE_PATH = Path("smali_classes2/com/yeelight/cherry/ui/fragment/SceneFragment.smali")
OWNER = "Lcom/yeelight/cherry/ui/fragment/SceneFragment;"
BRIDGE = "Lio/github/lxsdd/yeelightclassicdj/ui/ClassicSceneGalleryPanel;"
METHODS = (
    ("onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
     "Landroidx/fragment/app/Fragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
     "invoke-static/range {p0 .. p1}, " + BRIDGE + "->attach(Ljava/lang/Object;Landroid/view/View;)V"),
    ("onStop()V", "Landroidx/fragment/app/Fragment;->onStop()V",
     "invoke-static/range {p0 .. p0}, " + BRIDGE + "->detach(Ljava/lang/Object;)V"),
)

def patch_text(source: str) -> str:
    if type(source) is not str or not 0 < len(source) < 1000000:
        raise GateFailure("SCENE_GALLERY_SMALI_INVALID")
    if len(re.findall(r"(?m)^\.class\s+[^\r\n]*" + re.escape(OWNER) + r"\s*$", source)) != 1:
        raise GateFailure("SCENE_GALLERY_OWNER_MISMATCH")
    if BRIDGE in source:
        raise GateFailure("SCENE_GALLERY_ALREADY_PATCHED")
    result = source
    for method_name, base_call, injected in METHODS:
        method_re = re.compile(r"(?ms)^\.method[ \t]+[^\r\n]*\b" +
            re.escape(method_name) + r"[ \t]*\r?$.*?^\.end method[ \t]*\r?$")
        matches = list(method_re.finditer(result))
        if len(matches) != 1:
            raise GateFailure("SCENE_GALLERY_LIFECYCLE_METHOD_NOT_UNIQUE")
        match = matches[0]
        body = match.group(0)
        if len(re.findall(r"(?m)^\s*\.(?:locals|registers)\s+\d+\s*$", body)) != 1:
            raise GateFailure("SCENE_GALLERY_REGISTER_DECLARATION_INVALID")
        # Register provenance comes from the original signature, never from
        # a guessed v-register. /range works even when locals exceed v15.
        expected_args = "p0, p1, p2" if method_name.startswith("onViewCreated") else "p0"
        direct = re.compile(r"(?m)^(?P<indent>[ \t]*)invoke-super " +
            r"\{" + re.escape(expected_args) + r"\}, " +
            re.escape(base_call) + r"[ \t]*$")
        matches_calls = list(direct.finditer(body))
        if len(matches_calls) != 1:
            raise GateFailure("SCENE_GALLERY_SUPER_CALL_PROVENANCE_UNVERIFIED")
        at = matches_calls[0]
        updated_body = body[:at.end()] + "\n" + at.group("indent") + injected + body[at.end():]
        if updated_body.count(injected) != 1:
            raise GateFailure("SCENE_GALLERY_PATCH_RECHECK_FAILED")
        result = result[:match.start()] + updated_body + result[match.end():]
    if result.count(BRIDGE) != 2:
        raise GateFailure("SCENE_GALLERY_EXACT_TWO_CALLBACKS_REQUIRED")
    return result

def patch_staged_clone(staged: Path) -> dict:
    path = staged / SOURCE_PATH
    if not path.is_file() or path.is_symlink():
        raise GateFailure("SCENE_GALLERY_SMALI_SOURCE_MISSING")
    original = path.read_text(encoding="utf-8")
    changed = patch_text(original)
    path.write_text(changed, encoding="utf-8")
    return {
        "status": "PRIVATE_CLONE_SCENE_GALLERY_LIFECYCLE_HOOK_STAGED",
        "original_oem_methods_modified": 2,
        "original_scene_adapter_unchanged": True,
        "device_or_scene_writes": 0,
        "runtime_ui_verified": False,
    }
