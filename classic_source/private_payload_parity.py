#!/usr/bin/env python3
"""Private static payload parity across unsigned -> zipalign -> apksigner.

Android sign block and ZIP offsets can differ; Manifest and every DEX payload
must remain byte-for-byte unchanged, otherwise never publish a signed APK.
"""
from pathlib import Path
from zipfile import ZipFile, BadZipFile, ZIP_STORED
import argparse
import hashlib
import re
import sys

class Refused(ValueError):pass

def parity(unsigned:Path,signed:Path):
    with ZipFile(unsigned) as a,ZipFile(signed) as b:
        na=a.namelist();nb=b.namelist()
        if len(na)!=len(set(na)) or len(nb)!=len(set(nb)):
            raise Refused("DUPLICATE_ZIP_ENTRY")
        ia={n for n in na if n=="AndroidManifest.xml" or re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex",n)}
        ib={n for n in nb if n=="AndroidManifest.xml" or re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex",n)}
        if ia!=ib or "classes.dex" not in ia or "AndroidManifest.xml" not in ia:
            raise Refused("STARTUP_PAYLOAD_SET_CHANGED")
        dex_entries = ia - {"AndroidManifest.xml"}
        if dex_entries != {"classes.dex", "classes2.dex", "classes3.dex"}:
            raise Refused("SIGNED_MULTIDEX_ENTRY_SET_CHANGED")
        if any(a.getinfo(n).compress_type != ZIP_STORED or
               b.getinfo(n).compress_type != ZIP_STORED for n in dex_entries):
            raise Refused("SIGNED_MULTIDEX_NOT_ZIP_STORED")
        for n in ia:
            if hashlib.sha256(a.read(n)).digest()!=hashlib.sha256(b.read(n)).digest():
                raise Refused("SIGNED_STARTUP_PAYLOAD_CHANGED")
    return {"status":"SIGNED_STARTUP_PAYLOAD_IDENTICAL_NOT_RUNTIME_TESTED",
            "audited_entries":len(ia),"runtime":"NOT_TESTED"}

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--unsigned",type=Path,required=True)
    p.add_argument("--signed",type=Path,required=True)
    args=p.parse_args()
    try:
        from json import dumps
        print(dumps(parity(args.unsigned,args.signed)))
    except (Refused,OSError,BadZipFile,KeyError,ValueError) as exc:
        print("PRIVATE_SIGNED_PAYLOAD_PARITY_BLOCKED_"+(str(exc) if isinstance(exc,Refused) else "INPUT_INVALID"),file=sys.stderr)
        sys.exit(2)
