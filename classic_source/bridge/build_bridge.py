#!/usr/bin/env python3
"""Build private, source-only Classic DJ DEX using Loft DJ's Android CI toolchain.

Prefers staged Gradle 9.8.2 / Kotlin 2.4.21, upgrade of Loft DJ; falls back
onto a local Kotlin CLI only if Gradle is absent. Never downloads a tool, signs,
installs or touches a device. Original Classic code is not placed in Gradle.
"""
from pathlib import Path
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile

class ToolError(RuntimeError): pass

def cli(name):
    found = shutil.which(name)
    if not found: raise ToolError('TOOL_MISSING_'+name.upper())
    return found

def sdk_file(sdk, path, windows_ext=''):
    """Compatibility shim: validates an explicitly requested SDK resource."""
    sdk = Path(sdk)
    target = sdk/path
    if os.name == 'nt' and windows_ext: target=target.with_name(target.name+windows_ext)
    if not target.is_file(): raise ToolError('ANDROID_SDK_MISSING_'+path)
    return target

def find_sdk_tools(sdk):
    """Resolve actual SDK packages instead of assuming 35.0.0 exists in Loft DJ CI."""
    sdk = Path(sdk)
    platforms = sdk / 'platforms'
    android = next((platforms / f'android-{version}' / 'android.jar'
                    for version in (36, 35) if (platforms / f'android-{version}' / 'android.jar').is_file()), None)
    if android is None: raise ToolError('ANDROID_SDK_MISSING_PLATFORM_36_OR_35')
    builds = sdk / 'build-tools'
    candidates = sorted((d for d in builds.iterdir() if d.is_dir()),
        key=lambda p: tuple(map(int, re.findall(r'\d+', p.name)[:3] or ['0'])), reverse=True) if builds.is_dir() else []
    ext = '.bat' if os.name == 'nt' else ''
    d8 = next((d / ('d8'+ext) for d in candidates if (d / ('d8'+ext)).is_file()), None)
    aapt = next((d / ('aapt.exe' if os.name == 'nt' else 'aapt')
                 for d in candidates if (d / ('aapt.exe' if os.name == 'nt' else 'aapt')).is_file()), None)
    if not d8 or not aapt: raise ToolError('ANDROID_SDK_MISSING_D8_OR_AAPT')
    return android, d8, aapt

def run(args, timeout=300, cwd=None, env=None):
    return subprocess.run([str(x) for x in args], check=True, timeout=timeout, cwd=cwd, env=env)

# A fixed, source-owned failure vocabulary; raw Gradle text never leaves the
# ephemeral owner-only runner. Gradle stages only public bridge+SDK sources.
GRADLE_BRIDGE_CODES = frozenset((
    "GRADLE_BRIDGE_TRANSIENT_NETWORK",
    "GRADLE_BRIDGE_COMPILER_FAILED",
    "GRADLE_BRIDGE_DEPENDENCY_UNAVAILABLE",
    "GRADLE_BRIDGE_DAEMON_FAILED",
    "GRADLE_BRIDGE_PROCESS_FAILED_UNCLASSIFIED",
    "GRADLE_BRIDGE_TIMEOUT",
))
_GRADLE_NETWORK_TRANSIENT_MARKERS = (
    b"connection reset", b"connection timed out", b"read timed out",
    b"unknownhostexception", b"sockettimeoutexception",
    b"temporary failure in name resolution", b"network is unreachable",
    b"no route to host", b"remote host terminated the handshake",
    b"http 502", b"http 503", b"http 504",
)


def _gradle_stage_reason(raw: bytes) -> str:
    """Return an allowlisted failure reason, never raw stderr."""
    tail = raw[-131072:].lower()
    if any(marker in tail for marker in _GRADLE_NETWORK_TRANSIENT_MARKERS):
        return "GRADLE_BRIDGE_TRANSIENT_NETWORK"
    if (b"compilation error" in tail or b"compilation failed" in tail
            or b"compilation failure" in tail
            or b"execution failed for task ':compilekotlin" in tail
            or b"execution failed for task ':compilejava" in tail):
        return "GRADLE_BRIDGE_COMPILER_FAILED"
    if ((b"plugin [" in tail and b"was not found" in tail)
            or b"could not find " in tail or b"could not resolve " in tail):
        return "GRADLE_BRIDGE_DEPENDENCY_UNAVAILABLE"
    if b"gradle build daemon disappeared unexpectedly" in tail:
        return "GRADLE_BRIDGE_DAEMON_FAILED"
    return "GRADLE_BRIDGE_PROCESS_FAILED_UNCLASSIFIED"


def stage_gradle_inputs(gradle, project, sdk, out):
    """No new toolchain or OEM inputs; retry only one proven transient I/O.

    All raw build output stays inside the already-private build tree and is
    removed on *every* exit path. Compiler/plugin, daemon, timeout and unknown
    failures never trigger an automatic repeat or unsafe fallback.
    """
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk)
    cmd = [gradle, "--no-daemon", "--stacktrace",
           "--project-dir", project, "stageBridgeInputs"]
    private_log = out / "gradle-bridge-private.log"
    try:
        for attempt in range(2):
            try:
                with private_log.open("wb") as private:
                    result = subprocess.run(
                        [str(x) for x in cmd], check=False, timeout=360,
                        env=env, stdout=private, stderr=subprocess.STDOUT)
            except subprocess.TimeoutExpired:
                raise ToolError("GRADLE_BRIDGE_TIMEOUT") from None
            if result.returncode == 0:
                return
            with private_log.open("rb") as private:
                private.seek(max(0, private_log.stat().st_size - 131072))
                reason = _gradle_stage_reason(private.read(131072))
            if reason == "GRADLE_BRIDGE_TRANSIENT_NETWORK" and attempt == 0:
                continue
            raise ToolError(reason)
        raise ToolError("GRADLE_BRIDGE_PROCESS_FAILED_UNCLASSIFIED")
    finally:
        private_log.unlink(missing_ok=True)


def dex_descriptors(dex):
    """Extract class_def descriptors from DEX; used for OEM/sidecar collision gate."""
    import struct
    if len(dex) < 112 or dex[:4] != b'dex\n': raise ToolError('INVALID_DEX_HEADER')
    string_size, string_off = struct.unpack_from('<II', dex, 56)
    type_size, type_off = struct.unpack_from('<II', dex, 64)
    class_size, class_off = struct.unpack_from('<II', dex, 96)
    if string_size > 1000000 or type_size > 100000 or class_size > 100000:
        raise ToolError('INVALID_DEX_TABLE_LIMIT')
    if string_off + string_size * 4 > len(dex) or type_off + type_size * 4 > len(dex) or class_off + class_size * 32 > len(dex):
        raise ToolError('INVALID_DEX_TABLE_BOUNDS')
    strings = []
    for i in range(string_size):
        pos, = struct.unpack_from('<I', dex, string_off + i*4)
        if pos >= len(dex): raise ToolError('INVALID_DEX_STRING_OFFSET')
        for _ in range(5):
            if pos >= len(dex): raise ToolError('INVALID_DEX_STRING_ULEB')
            b = dex[pos]; pos += 1
            if b < 128: break
        else: raise ToolError('INVALID_DEX_STRING_ULEB')
        end = dex.find(b'\0', pos)
        if end == -1: raise ToolError('INVALID_DEX_STRING_END')
        strings.append(dex[pos:end].decode('utf-8', errors='replace'))
    types = []
    for i in range(type_size):
        idx, = struct.unpack_from('<I', dex, type_off + i*4)
        if idx >= len(strings): raise ToolError('INVALID_DEX_TYPE_INDEX')
        types.append(strings[idx])
    result = set()
    for i in range(class_size):
        type_idx, = struct.unpack_from('<I', dex, class_off + i*32)
        if type_idx >= len(types): raise ToolError('INVALID_DEX_CLASS_INDEX')
        result.add(types[type_idx])
    if len(result) != class_size: raise ToolError('INVALID_DEX_DUPLICATE_CLASS')
    return result

def no_oem_class_collisions(original_apk, sidecar_dex):
    with zipfile.ZipFile(original_apk) as z:
        originals = set()
        for name in z.namelist():
            if re.fullmatch(r'classes(?:\d+)?\.dex', name):
                current = dex_descriptors(z.read(name))
                if originals & current: raise ToolError('OEM_DUPLICATE_DEX_CLASS')
                originals |= current
    added = dex_descriptors(Path(sidecar_dex).read_bytes())
    clash = sorted(originals & added)
    if clash: raise ToolError('SIDECAR_OEM_CLASS_COLLISION:'+','.join(clash[:5]))
    expected = {'Lio/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjActivity;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicNearbyGateActivity;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicInternalAudioConsentActivity;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjService;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjEntryView;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicDjEntryProvider;',
                'Lio/github/lxsdd/yeelightclassicdj/ui/ClassicMusicInlineControls;'}
    if not expected <= added: raise ToolError('SIDECAR_REQUIRED_COMPONENTS_MISSING')
    return {'oem_classes': len(originals), 'added_classes': len(added), 'collisions': 0}

def filter_runtime_jars(inputs, original_apk, output):
    """Keep OEM-owned classes; add only missing Kotlin runtime classes to sidecar.

    OEM already includes some Kotlin stdlib classes. Bundle only missing classes;
    D8 must not introduce duplicate descriptors. This is a structural preflight,
    not proof that OEM's older Kotlin ABI works at runtime.
    """
    if original_apk is None:
        raise ToolError('ORIGINAL_REQUIRED_FOR_KOTLIN_RUNTIME_RESOLUTION')
    with zipfile.ZipFile(original_apk) as z:
        owned = set()
        for n in z.namelist():
            if re.fullmatch(r'classes(?:\d+)?\.dex', n):
                classes = dex_descriptors(z.read(n))
                if classes & owned: raise ToolError('OEM_DUPLICATE_DEX_CLASS')
                owned |= classes
    output.mkdir(parents=True, exist_ok=True)
    result = []
    kept = set()
    skipped = 0
    for jar in inputs:
        jar = Path(jar)
        if not jar.is_file(): raise ToolError('BRIDGE_JAR_MISSING')
        dst = output / jar.name
        if dst.exists(): raise ToolError('DUPLICATE_BRIDGE_JAR_NAME')
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(dst, 'w') as filtered:
            for i in source.infolist():
                name = i.filename
                if name.endswith('.class'):
                    descriptor = 'L' + name[:-6] + ';'
                    if descriptor in owned:
                        if 'kotlin-stdlib' not in jar.name:
                            raise ToolError('BRIDGE_OWN_CLASS_DUPLICATES_OEM')
                        skipped += 1
                        continue
                    if descriptor in kept:
                        raise ToolError('DUPLICATE_BRIDGE_RUNTIME_CLASS')
                    kept.add(descriptor)
                if name.startswith('/') or '..' in Path(name).parts or '\\' in name:
                    raise ToolError('BRIDGE_JAR_PATH_TRAVERSAL')
                filtered.writestr(i, source.read(name))
        result.append(dst)
    if not skipped:
        # Empty overlap is fine for future OEM revisions, but exact APK is pinned.
        pass
    return result, {'filtered_duplicate_kotlin_classes':skipped,
                    'source_classes':len(kept),'oem_classes':len(owned)}

def verify_compiled_checkpoint_abi(jar):
    """Verify the actual owner bridge JAR in both Python invocation modes.

    The private APK builder imports bridge.build_bridge as a package, whereas
    this file can also run directly with bridge/ on sys.path. Resolve the
    same exact ABI validator without depending on a caller-specific sys.path.
    """
    if __package__:
        from .checkpoint_abi import verify_bridge_jar, AbiGateError
    else:
        from checkpoint_abi import verify_bridge_jar, AbiGateError
    try:
        return verify_bridge_jar(jar)
    except AbiGateError:
        raise ToolError("CLASSIC_CHECKPOINT_RESULT_ABI_UNVERIFIED") from None


def build(sdk, out, original_apk=None):
    sdk, out = Path(sdk), Path(out)
    if out.exists(): raise ToolError('OUTPUT_ALREADY_EXISTS')
    if not out.parent.is_dir(): raise ToolError('OUTPUT_PARENT_MISSING')
    root = Path(__file__).resolve().parents[1]
    android, d8, _ = find_sdk_tools(sdk)
    gradle = shutil.which('gradle')
    inputs = []
    out.mkdir()
    dex = out / 'dex'; dex.mkdir()
    if gradle:
        project = root / 'private_gradle_bridge'
        stage_gradle_inputs(gradle, project, sdk, out)
        staged = project / 'build' / 'dex-input'
        inputs = sorted(staged.glob('*.jar'))
        if len(inputs) < 2 or not any('kotlin-stdlib' in i.name for i in inputs):
            raise ToolError('GRADLE_BRIDGE_AND_KOTLIN_RUNTIME_MISSING')
    else:
        classes = out / 'classes'; classes.mkdir()
        java = sorted((root/'bridge/src').rglob('*.java'))
        kotlin = sorted((root/'checkpoint/src').glob('*.kt')) + sorted((root/'bridge/kotlin').rglob('*.kt'))
        if len(java) < 4 or len(kotlin) < 8: raise ToolError('BRIDGE_SOURCE_INCOMPLETE')
        run([cli('javac'), '-source','8','-target','8','-classpath',android,'-d',classes,*java])
        run([cli('kotlinc'),'-jvm-target','1.8','-classpath',str(android)+os.pathsep+str(classes),
            *kotlin,'-d',classes])
        jar = out / 'bridge.jar'
        run([cli('jar'),'cf',jar,'-C',classes,'.'])
        inputs = [jar]
        kbin = Path(cli('kotlinc')).resolve()
        # Kotlin distribution's own stdlib, not a JAR downloaded from an unrelated source.
        maybe = [kbin.parent.parent/'lib/kotlin-stdlib.jar', kbin.parent.parent/'lib/kotlin-stdlib-jdk8.jar']
        if not maybe[0].is_file(): raise ToolError('KOTLIN_STDLIB_UNAVAILABLE')
        inputs += [i for i in maybe if i.is_file()]
    # Before D8, verify the true compiled CheckpointRuntime classfile. Original
    # Classic can carry older Kotlin stdlib definitions; a getstatic on
    # kotlin.Result.Companion must never reach the private clone.
    owner_jars = [jar for jar in inputs if jar.name in ("classic-bridge.jar", "bridge.jar")]
    if len(owner_jars) != 1:
        raise ToolError("CLASSIC_CHECKPOINT_BRIDGE_JAR_NOT_UNIQUE")
    verify_compiled_checkpoint_abi(owner_jars[0])
    inputs, runtime_report = filter_runtime_jars(inputs, original_apk, out / 'filtered-runtime')
    import json
    (out / 'runtime_resolution.json').write_text(json.dumps(runtime_report, indent=2)+'\n', encoding='utf-8')
    run([d8,'--min-api','23','--lib',android,'--output',dex,*inputs],timeout=240)
    generated = sorted(dex.glob('classes*.dex'))
    if len(generated) != 1 or generated[0].stat().st_size < 200:
        raise ToolError('EXPECTED_SINGLE_SIDECAR_DEX')
    return generated[0]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--original', type=Path, required=True)
    args=parser.parse_args()
    try:
        print(build(args.sdk,args.output,original_apk=args.original))
    except (ToolError,subprocess.SubprocessError,OSError,zipfile.BadZipFile) as error:
        print('BLOCKED: '+str(error),file=sys.stderr)
        return 2
    return 0

if __name__=='__main__': raise SystemExit(main())
