#!/usr/bin/env python3
"""Offline patch of a privately apktool-decoded Classic tree; never bundles OEM bytes."""
import argparse, hashlib, json, pathlib, shutil, sys, re, tempfile, xml.etree.ElementTree as ET

ORIGINAL_SHA = 'abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2'
ANDROID = '{http://schemas.android.com/apk/res/android}'
OLD_PACKAGE = 'com.yeelight.cherry'
NEW_PACKAGE = 'loftdj.private.classic'

class PatchError(Exception): pass

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()

def patch(original_apk, decoded, destination, hook_file, hook_anchor):
    original_apk, decoded, destination = map(pathlib.Path, (original_apk, decoded, destination))
    if sha(original_apk) != ORIGINAL_SHA: raise PatchError('Original APK SHA256 mismatch')
    if destination.exists(): raise PatchError('Output already exists; never overwrite')
    if decoded.is_symlink() or destination.is_symlink(): raise PatchError('Symlinked root refused')
    if not decoded.is_dir() or not (decoded / 'AndroidManifest.xml').is_file(): raise PatchError('Missing decoded apktool tree')
    if pathlib.PurePath(hook_file).is_absolute() or any(part in ('..', '.') for part in pathlib.PurePath(hook_file).parts):
        raise PatchError('Invalid in-tree navigation hook path')
    src_hook = (decoded / hook_file).resolve()
    if not src_hook.is_relative_to(decoded.resolve()) or not src_hook.is_file() or src_hook.suffix != '.smali':
        raise PatchError('Invalid in-tree smali navigation hook')
    text = src_hook.read_text()
    if text.count(hook_anchor) != 1 or 'invoke-static {p0}, Lloftdj/classic/DjEntry;->open(Landroid/app/Activity;)V' in text:
        raise PatchError('Navigation anchor not unique or already patched')
    # Only a verified Activity instance method can pass p0 as android.app.Activity.
    # Do not insert into static methods, arbitrary fragments or constructors.
    before, after = text.split(hook_anchor, 1)
    method_start = before.rfind('.method ')
    method_end = before.rfind('.end method')
    if method_start < 0 or method_end > method_start or '.end method' not in after:
        raise PatchError('Anchor not inside method')
    method_header = before[method_start:].splitlines()[0]
    if re.search(r'\b(static|constructor)\b', method_header):
        raise PatchError('Hook must be an Activity instance method')
    if not re.search(r'^\.super Landroid/app/Activity;$', text, re.M):
        raise PatchError('Hook owner is not a directly verified Activity')
    method_body = before[method_start:] + after.split('.end method', 1)[0]
    if not re.search(r'^\s*\.(?:locals|registers)\s+\d+\s*$', method_body, re.M):
        raise PatchError('Hook lacks locals declaration')
    # Injection is only safe at a verified fall-through tail of a click handler.
    # A comment match inside dead code, a catch block or after a return is not
    # proof that the new screen will ever be reachable from the OEM UI.
    if not re.fullmatch(r'#[^\n]*', hook_anchor.strip()):
        raise PatchError('Navigation hook must be an exact comment marker')
    method_prefix = before[method_start:]
    if re.search(r'^\s*(?:return(?:-\w+)?|throw|goto(?:/\w+)?|packed-switch|sparse-switch)\b', method_prefix, re.M):
        raise PatchError('Navigation hook is after a terminal or branching instruction')
    if re.search(r'^\s*\.(?:catch|catchall|packed-switch|sparse-switch)\b', method_body, re.M):
        raise PatchError('Complex control-flow hook requires manual verification')
    if re.search(r'^\s*:[\w$-]+', method_body, re.M):
        raise PatchError('Labelled control-flow hook requires manual verification')
    tail = after.split('.end method', 1)[0]
    tail_lines = [line.strip() for line in tail.splitlines() if line.strip() and not line.strip().startswith('#')]
    if tail_lines != ['return-void']:
        raise PatchError('Navigation hook must directly precede method return-void')
    if not re.search(r'^\.method\s+.*\b(public|protected)\b.*\([^)]*\)V$', method_header):
        raise PatchError('Navigation hook must be public/protected void handler')
    tree = ET.parse(decoded / 'AndroidManifest.xml'); root = tree.getroot()
    if root.get('package') != OLD_PACKAGE: raise PatchError('Unexpected decoded package')
    app = root.find('application')
    if app is None: raise PatchError('Missing application')
    authorities = set()
    for element in root.iter():
        for k, v in list(element.attrib.items()):
            if k == ANDROID + 'authorities':
                if OLD_PACKAGE not in v: raise PatchError('Unknown provider authority; manual audit required')
                v = v.replace(OLD_PACKAGE, NEW_PACKAGE)
                if v in authorities: raise PatchError('Duplicate provider authority')
                authorities.add(v); element.set(k, v)
    # Preserve original relative Android component class names after package relocation.
    component_tags = {'activity', 'activity-alias', 'service', 'receiver', 'provider', 'application'}
    for element in root.iter():
        if element.tag not in component_tags: continue
        for attr in (ANDROID + 'name', ANDROID + 'targetActivity'):
            name = element.get(attr)
            if not name: continue
            if name.startswith('.'): element.set(attr, OLD_PACKAGE + name)
            elif '.' not in name: element.set(attr, OLD_PACKAGE + '.' + name)
    root.set('package', NEW_PACKAGE)
    if any(e.get(ANDROID + 'name') == 'loftdj.classic.DjActivity' for e in app.findall('activity')):
        raise PatchError('Duplicate DJ activity')
    ET.SubElement(app, 'activity', {ANDROID + 'name':'loftdj.classic.DjActivity',ANDROID + 'exported':'false',ANDROID + 'label':'Loft DJ'})
    # Do not attempt OEM-specific resource/string/intent rewrites without validated mapping.
    # No symlinked decoded content may escape the private staging boundary.
    if any(p.is_symlink() for p in decoded.rglob('*')):
        raise PatchError('Symlink inside decoded tree refused')
    # Stage privately and atomically publish only after every generated file succeeds.
    # No partial output is left if an I/O error or namespace collision occurs.
    stage = pathlib.Path(tempfile.mkdtemp(prefix='.classic-stage-', dir=destination.parent))
    try:
        shutil.copytree(decoded, stage, dirs_exist_ok=True)
        tree.write(stage / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
        _write_injection(stage, hook_file, hook_anchor, text)
        if destination.exists(): raise PatchError('Output appeared during staging')
        stage.rename(destination)
    finally:
        if stage.exists(): shutil.rmtree(stage)
    return {'original_sha256': ORIGINAL_SHA, 'package': NEW_PACKAGE, 'hook': hook_file, 'authorities': sorted(authorities), 'output': str(destination)}

def _write_injection(destination, hook_file, hook_anchor, text):
    dst_hook = destination / hook_file
    dst_hook.write_text(text.replace(hook_anchor, hook_anchor + '\n    invoke-static {p0}, Lloftdj/classic/DjEntry;->open(Landroid/app/Activity;)V', 1))
    smali = destination / 'smali' / 'loftdj' / 'classic'
    if smali.exists(): raise PatchError('Injected namespace already exists')
    smali.mkdir(parents=True)
    (smali / 'DjEntry.smali').write_text('''.class public final Lloftdj/classic/DjEntry;
.super Ljava/lang/Object;
.method public static open(Landroid/app/Activity;)V
    .locals 2
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lloftdj/classic/DjActivity;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    return-void
.end method
''')
    (smali / 'DjActivity.smali').write_text('''.class public Lloftdj/classic/DjActivity;
.super Landroid/app/Activity;
.method public onCreate(Landroid/os/Bundle;)V
    .locals 2
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
    new-instance v0, Landroid/widget/TextView;
    invoke-direct {v0, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V
    const-string v1, "Loft DJ – private integration prototype"
    invoke-virtual {v0, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->setContentView(Landroid/view/View;)V
    return-void
.end method
''')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('original_apk'); parser.add_argument('decoded'); parser.add_argument('destination')
    parser.add_argument('--hook-file', required=True); parser.add_argument('--hook-anchor', required=True)
    a = parser.parse_args()
    try: print(json.dumps(patch(a.original_apk, a.decoded, a.destination, a.hook_file, a.hook_anchor), sort_keys=True))
    except PatchError as e: print('REFUSED: ' + str(e), file=sys.stderr); sys.exit(2)
