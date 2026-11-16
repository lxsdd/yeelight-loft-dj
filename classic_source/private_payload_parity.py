#!/usr/bin/env python3
"""Private static payload parity across unsigned -> zipalign -> apksigner.

Android sign block and ZIP offsets can differ. Every non-signature payload,
including OEM native libraries and resources, must remain byte-for-byte
unchanged. ZIP page alignment is not an ELF compatibility or runtime proof.
"""
from pathlib import Path
from zipfile import ZipFile, BadZipFile, ZIP_STORED
import argparse
import hashlib
import re
import struct
import sys

class Refused(ValueError):pass

def _signature_entry(name):
    return bool(re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))",
                             name, re.I))


# Read-only, path-free ELF segment census. A 16-KiB-aligned ZIP can still
# contain 4-KiB PT_LOAD segments, which retain a native page-size mismatch.
# This never edits OEM libraries, reports ELF names or claims runtime acceptance.
ELF_PAGE_SIZE = 16384
ELF_PREFIX_LIMIT = 1024 * 1024


def _elf_load_alignment(data: bytes) -> str:
    if type(data) is not bytes or len(data) < 52 or data[:4] != b"\x7fELF":
        return "UNVERIFIED"
    kind, endian = data[4], data[5]
    if kind not in (1, 2) or endian not in (1, 2):
        return "UNVERIFIED"
    order = "<" if endian == 1 else ">"
    try:
        if kind == 2:
            if len(data) < 64:
                return "UNVERIFIED"
            phoff = struct.unpack_from(order + "Q", data, 32)[0]
            entsize, count = struct.unpack_from(order + "HH", data, 54)
            minimum = 56
        else:
            phoff = struct.unpack_from(order + "I", data, 28)[0]
            entsize, count = struct.unpack_from(order + "HH", data, 42)
            minimum = 32
        if not (0 < count <= 256 and minimum <= entsize <= 512):
            return "UNVERIFIED"
        if phoff > len(data) or count * entsize > len(data) - phoff:
            return "UNVERIFIED"
        has_load = False
        incompatible = False
        for i in range(count):
            start = phoff + i * entsize
            ptype = struct.unpack_from(order + "I", data, start)[0]
            if ptype != 1:  # PT_LOAD only; RELRO is not inferred from PT_LOAD.
                continue
            has_load = True
            if kind == 2:
                offset, vaddr = struct.unpack_from(order + "QQ", data, start + 8)
                alignment = struct.unpack_from(order + "Q", data, start + 48)[0]
            else:
                offset, vaddr = struct.unpack_from(order + "II", data, start + 4)
                alignment = struct.unpack_from(order + "I", data, start + 28)[0]
            if (alignment < ELF_PAGE_SIZE or
                    offset % ELF_PAGE_SIZE != vaddr % ELF_PAGE_SIZE):
                incompatible = True
        if not has_load:
            return "UNVERIFIED"
        return "LOAD_NOT_16K" if incompatible else "LOAD_16K"
    except struct.error:
        return "UNVERIFIED"


def native_elf_16k_census(archive: ZipFile) -> dict:
    """Fixed-status counts only; no private ELF names or raw bytes in output."""
    libraries = [n for n in archive.namelist()
                 if re.fullmatch(r"lib/[^/]+/[^/]+\.so", n)]
    if len(libraries) > 512:
        raise Refused("NATIVE_LIBRARY_COUNT_OUT_OF_BOUNDS")
    counts = {"LOAD_16K": 0, "LOAD_NOT_16K": 0, "UNVERIFIED": 0}
    for name in libraries:
        info = archive.getinfo(name)
        with archive.open(info) as src:
            data = src.read(ELF_PREFIX_LIMIT)
        counts[_elf_load_alignment(data)] += 1
    if not libraries:
        status = "NO_EMBEDDED_NATIVE_LIBRARIES"
    elif counts["LOAD_NOT_16K"]:
        status = "NATIVE_ELF_LOAD_16K_INCOMPATIBLE"
    elif counts["UNVERIFIED"]:
        status = "NATIVE_ELF_HEADERS_UNVERIFIED"
    else:
        status = "NATIVE_ELF_LOAD_16K_COMPATIBLE"
    return {
        "status": status,
        "native_library_count": len(libraries),
        "load_16k_count": counts["LOAD_16K"],
        "load_not_16k_count": counts["LOAD_NOT_16K"],
        "unverified_count": counts["UNVERIFIED"],
        "elf_load_only": True,
        "zip_alignment_independently_verified": False,
        "runtime_compatibility_proven": False,
    }


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
        pa = {n for n in na if not _signature_entry(n)}
        pb = {n for n in nb if not _signature_entry(n)}
        if pa != pb:
            raise Refused("SIGNED_NON_SIGNATURE_PAYLOAD_SET_CHANGED")
        for n in pa - ia:
            if hashlib.sha256(a.read(n)).digest() != hashlib.sha256(b.read(n)).digest():
                raise Refused("SIGNED_NON_SIGNATURE_PAYLOAD_CHANGED")
        native_unsigned = native_elf_16k_census(a)
        native_signed = native_elf_16k_census(b)
        if native_unsigned != native_signed:
            raise Refused("SIGNED_NATIVE_ELF_CENSUS_CHANGED")
    return {"status":"SIGNED_STARTUP_PAYLOAD_IDENTICAL_NOT_RUNTIME_TESTED",
            "audited_entries":len(ia),"all_payload_entries":len(pa),
            "native_and_resource_payloads_identical":True,
            "native_elf_16k_census":native_signed,"runtime":"NOT_TESTED"}

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
