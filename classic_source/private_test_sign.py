#!/usr/bin/env python3
"""Prepare a privately installable TEST APK using an ephemeral, fresh Android test key.

This intentionally does not produce a durable production signing identity: updates require
uninstall/reinstall until a separate owner-controlled persistent signing key is configured.
Only run on an explicitly approved private GitHub-hosted job. No account/device operations.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile

PACKAGE = 'io.lxsdd.yeelightdj'
INPUT_NAME = 'classic-loft-dj-UNSIGNED.apk'
OUTPUT_NAME = 'classic-loft-dj-TEST-SIGNED.apk'

class Refused(RuntimeError):
    pass

def sha256(path: Path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1048576),b''): h.update(block)
    return h.hexdigest()

def tool(sdk: Path, name: str):
    candidates=[]
    for d in (sdk / 'build-tools').glob('*'):
        try: version=tuple(int(x) for x in d.name.split('.'))
        except ValueError: continue
        candidates.append((version,d/name))
    for _, p in sorted(candidates,reverse=True):
        if p.is_file(): return p
    raise Refused('ANDROID_SDK_SIGN_TOOL_MISSING_'+name)

def run(args, *, env=None):
    subprocess.run([str(x) for x in args],env=env,check=True,timeout=90,
                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

def sign(source: Path, preflight: Path, output: Path, sdk: Path,
         *, identity_control=False):
    if type(identity_control) is not bool:
        raise Refused('IDENTITY_ABLATION_BAD_MODE')
    if identity_control:
        root = os.environ.get('RUNNER_TEMP', '')
        allowed = (bool(root) and
                   source == Path(root)/'private-output'/'classic-loft-dj-IDENTITY-UNSIGNED.apk' and
                   output == Path(root)/'private-output'/'classic-loft-dj-IDENTITY-TEST-SIGNED.apk' and
                   preflight == source.with_suffix(source.suffix+'.preflight.json') and
                   os.environ.get('GITHUB_REPOSITORY') == 'lxsdd/yeelight-loft-dj' and
                   os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
                   os.environ.get('GITHUB_ACTOR') == 'lxsdd' and
                   os.environ.get('GITHUB_REF') == 'refs/heads/main' and
                   os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted' and
                   not os.environ.get('GH_TOKEN') and
                   not os.environ.get('CLASSIC_VAULT_TOKEN'))
        if not allowed:
            raise Refused('IDENTITY_ABLATION_OWNER_OFFLINE_SCOPE_REQUIRED')
    elif source.name != INPUT_NAME or output.name != OUTPUT_NAME:
        raise Refused('ONLY_EXACT_PRIVATE_CLASSIC_TEST_APK_ALLOWED')
    if output.exists():
        raise Refused('ONLY_EXACT_PRIVATE_CLASSIC_TEST_APK_ALLOWED')
    if not source.is_file() or not preflight.is_file() or not sdk.is_dir():
        raise Refused('PRIVATE_SOURCE_OR_SDK_MISSING')
    if source.resolve() == output.resolve() or source.stat().st_size < 1000:
        raise Refused('BAD_PRIVATE_INPUT')
    try: d=json.loads(preflight.read_text(encoding='utf-8'))
    except (ValueError,OSError): raise Refused('BAD_PREFLIGHT_REPORT')
    package = 'com.yeelight.cherry' if identity_control else PACKAGE
    if (d.get('status')!='UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED'
            or d.get('signed') is not False or d.get('installed') is not False
            or d.get('package')!=package):
        raise Refused('NO_QUALIFIED_CLASSIC_UNSIGNED_APK')
    if identity_control and (d.get('identity_control_offline_only') is not True or
            d.get('dex_reassembly_mode') != 'raw' or
            any(x != 0 for x in d.get('controlled_authority_string_remaps',{}).values()) or
            d.get('original_oem_dex_intentionally_changed') is not False):
        raise Refused('IDENTITY_CONTROL_OEM_BYTES_OR_SCOPE_NOT_PRESERVED')
    unsigned_sha=sha256(source)
    if d.get('apk_sha256')!=unsigned_sha: raise Refused('UNSIGNED_SHA256_MISMATCH')
    keytool=shutil.which('keytool')
    if keytool is None: raise Refused('JAVA_KEYTOOL_MISSING')
    zipalign=tool(sdk,'zipalign')
    apksigner=tool(sdk,'apksigner')
    # No reusable secret in output: key and its random password are confined to private temp.
    with tempfile.TemporaryDirectory(prefix='.classic-ephemeral-sign-') as dtmp:
        root=Path(dtmp)
        key=root/'one-test-build-only.p12'
        aligned=root/'aligned.apk'
        temp_signed=root/'signed.apk'
        env=os.environ.copy()
        env['CLASSIC_EPHEMERAL_SIGN_PASS']=secrets.token_urlsafe(32)
        try:
            run([keytool,'-genkeypair','-noprompt','-storetype','PKCS12',
                 '-keystore',key,'-alias','classic-test-only','-keyalg','RSA',
                 '-keysize','3072','-validity','365',
                 '-dname','CN=Yeelight Classic Loft DJ PRIVATE TEST,O=Local Test',
                 '-storepass:env','CLASSIC_EPHEMERAL_SIGN_PASS',
                 '-keypass:env','CLASSIC_EPHEMERAL_SIGN_PASS'],env=env)
            run([zipalign,'-f','-p','4',source,aligned],env=env)
            run([apksigner,'sign','--ks',key,'--ks-type','PKCS12',
                 '--ks-key-alias','classic-test-only',
                 '--ks-pass','env:CLASSIC_EPHEMERAL_SIGN_PASS',
                 '--key-pass','env:CLASSIC_EPHEMERAL_SIGN_PASS',
                 '--out',temp_signed,aligned],env=env)
            run([apksigner,'verify','--verbose','--min-sdk-version','23',temp_signed],env=env)
            if temp_signed.stat().st_size < 1000: raise Refused('SIGNED_OUTPUT_INVALID')
            # Signature is tied only to this test build; no vendor key or stable app identity is claimed.
            shutil.copyfile(temp_signed,output)
        finally:
            env.pop('CLASSIC_EPHEMERAL_SIGN_PASS',None)
    report={
        'status':'PRIVATE_CLASSIC_TEST_APK_SIGNED_NOT_INSTALLED_NOT_HARDWARE_TESTED',
        'package':package,
        'identity_control_offline_only':identity_control,
        'signed':True,
        'installed':False,
        'signature':'EPHEMERAL_UNIQUE_TEST_KEY_NOT_SUITABLE_FOR_APP_UPDATES',
        'unsigned_sha256':unsigned_sha,
        'apk_sha256':sha256(output),
        'hardware':'NOT_TESTED',
        'yeelight_classic_compatibility':'NOT_TESTED',
        'source_build_report_sha256':sha256(preflight),
    }
    output.with_name(output.name+'.test-signing.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--unsigned',type=Path,required=True)
    p.add_argument('--preflight',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--android-sdk',type=Path,required=True)
    p.add_argument('--identity-control',action='store_true',
                   help='isolated emulator-only original package probe')
    a=p.parse_args()
    try:
        print(json.dumps(sign(a.unsigned,a.preflight,a.output,a.android_sdk,
                              identity_control=a.identity_control)))
        return 0
    except (Refused,subprocess.SubprocessError,OSError) as e:
        print('PRIVATE_TEST_SIGN_BLOCKED_'+type(e).__name__,file=sys.stderr)
        return 2

if __name__=='__main__': sys.exit(main())
