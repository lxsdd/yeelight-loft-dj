#!/usr/bin/env python3
"""Read-only fingerprint of original Classic embedded component families.

This is NOT a vulnerability scan, license approval, an SDK replacement, or proof
of runtime compatibility. It outputs no OEM classes, DEX bytes, account values,
network destinations, device aliases or addresses.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
from bridge.build_bridge import dex_descriptors

ORIGINAL_SHA256 = 'abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2'
FAMILIES = {
 'kotlin_stdlib': 'Lkotlin/',
 'kotlinx': 'Lkotlinx/',
 'androidx': 'Landroidx/',
 'legacy_android_support': 'Landroid/support/',
 'gson': 'Lcom/google/gson/',
 'okhttp': 'Lokhttp3/',
 'okio': 'Lokio/',
 'retrofit': 'Lretrofit2/',
 'firebase': 'Lcom/google/firebase/',
 'play_services': 'Lcom/google/android/gms/',
 'rxjava': 'Lio/reactivex/',
 'glide': 'Lcom/bumptech/glide/',
}

class PinnedOriginalFailure(ValueError):
    pass

def sha256_file(p: Path):
    with p.open('rb') as f:
        h=hashlib.sha256()
        for chunk in iter(lambda:f.read(1024*1024),b''):
            h.update(chunk)
        return h.hexdigest()

def audit(path: Path, require_hash: str=ORIGINAL_SHA256):
    path = Path(path)
    if not path.is_file() or sha256_file(path) != require_hash:
        raise PinnedOriginalFailure('ORIGINAL_APK_SHA_MISMATCH')
    with zipfile.ZipFile(path) as zipf:
        dex_names=sorted((i.filename for i in zipf.infolist()
                          if re.fullmatch(r'classes(?:\d+)?\.dex',i.filename)))
        if not dex_names: raise PinnedOriginalFailure('ORIGINAL_APK_NO_DEX')
        owned=set()
        for name in dex_names:
            classes=dex_descriptors(zipf.read(name))
            if classes & owned:
                raise PinnedOriginalFailure('ORIGINAL_APK_DUPLICATE_DEX_CLASS')
            owned |= classes
        nativelibs = [i.filename for i in zipf.infolist()
                      if re.fullmatch(r'lib/[^/]+/[^/]+\.so',i.filename)]
        native_abis=sorted({p.split('/')[1] for p in nativelibs})
    family_counts={name:sum(s.startswith(prefix) for s in owned) for name,prefix in FAMILIES.items()}
    return {
      'status':'READ_ONLY_VENDOR_COMPONENT_INVENTORY_NOT_RUNTIME_VERIFIED',
      'original_sha256':require_hash,
      'dex_count':len(dex_names),'total_class_count':len(owned),
      'family_class_counts':family_counts,
      'native_library_count':len(nativelibs),
      'native_abi_count':len(native_abis),
      'native_abis':native_abis,
      'vendor_replace_policy':'FREEZE_ORIGINAL_OEM_DEX_NATIVE_AND_AUTH_COMPONENTS_UNTIL_COMPATIBILITY_PROVEN',
      'safe_to_upgrade':'OWN_DJ_SIDECAR_ONLY_AFTER_DEX_COLLISION_GUARD_AND_REAL_ANDROID_SMOKE',
      'component_versions':'NOT_VERIFIED_BY_CLASS_PRESENCE',
      'license_and_vulnerability_scan':'NOT_PERFORMED',
      'account_ble_miio_lan_hardware':'NOT_TESTED',
    }

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--original',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args(argv)
    if args.output.exists() or not args.output.parent.is_dir():
        raise SystemExit('BLOCKED_OUTPUT_NOT_FRESH_OR_PARENT_MISSING')
    try:r=audit(args.original)
    except (PinnedOriginalFailure, zipfile.BadZipFile, OSError, ValueError) as e:
        raise SystemExit('BLOCKED_'+str(e))
    args.output.write_text(json.dumps(r,indent=2)+'\n')
    print('READ_ONLY_VENDOR_AUDIT_OK')
    return 0
if __name__=='__main__':raise SystemExit(main())
