#!/usr/bin/env python3
"""Verify actual stock color4 LAN error table and transport text, offline only.

No disassembler, socket, authentication, OTA, firmware modification, or export
of OEM image content. The listed error strings are *compiled firmware data*;
they do NOT reveal the quota accounting or prove the 49.7-day hypothesis.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct

from oem_image_evidence import esp32

MODEL="yeelink.light.color4"
VERSION="2.1.7_0039"
STOCK_SHA256="67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa"
STOCK_SIZE=1_502_532
MAX_FILE=2*1024*1024
ERRORS=(
    b"ok", b"invalid command", b"method not supported",
    b"invalid parameter", b"out of memory",
    b"client quota exceeded", b"fail",
)
LABELS={
    "classic_lan":(
        b"lan_ctrl_start", b"lan_ctrl_init", b"lan_clnt_recv",
        b"handle_lan_command", b"lan_cmd_ack",
    ),
    "music_protocol":(b"set_music",b"lan_open_mchannel"),
    "normal_command_strings":(b"get_prop",b"set_rgb",b"set_bright"),
    "timer_sdk_strings":(b"esp_timer_start_periodic",b"ets_timer_arm"),
}


def map_offset_to_address(image:bytes, offset:int):
    if offset<24 or len(image)<24 or image[0]!=0xe9:
        return None
    count=image[1]
    if not 1<=count<=16:
        return None
    cursor=24
    for _ in range(count):
        if cursor+8>len(image):
            return None
        base,size=struct.unpack_from("<II",image,cursor)
        start=cursor+8
        if size>MAX_FILE or start+size>len(image):
            return None
        if start<=offset<start+size:
            return base+(offset-start)
        cursor=start+size
    return None


def map_address_to_offset(image:bytes,address:int):
    if not isinstance(address,int) or len(image)<24 or image[0]!=0xe9:
        return None
    count=image[1]
    if not 1<=count<=16:
        return None
    cursor=24
    for _ in range(count):
        if cursor+8>len(image):
            return None
        base,size=struct.unpack_from("<II",image,cursor)
        start=cursor+8
        if size>MAX_FILE or start+size>len(image):
            return None
        if base<=address<base+size:
            return start+address-base
        cursor=start+size
    return None


def firmware_error_table(image:bytes):
    """Validate each complete 7-word pointer table by resolving every string.

    Generic words such as "fail" and "out of memory" repeat elsewhere in the
    OEM image; searching for their *first* string occurrence is incorrect.
    Decode the contiguous table itself and reject missing/ambiguous vectors.
    """
    if len(image)<24 or image[0]!=0xe9:
        return None
    matches=[]
    for table in range(0,len(image)-len(ERRORS)*4+1,4):
        first=struct.unpack_from("<I",image,table)[0]
        location=map_address_to_offset(image,first)
        if location is None or image[location:location+3]!=b"ok\0":
            continue
        pointers=struct.unpack_from("<7I",image,table)
        entries=[]
        for ptr,expected in zip(pointers,ERRORS):
            off=map_address_to_offset(image,ptr)
            if off is None or image[off:off+len(expected)+1]!=expected+b"\0":
                break
            entries.append((off,ptr))
        else:
            if map_offset_to_address(image,table) is not None:
                matches.append((table,entries))
    if len(matches)!=1:
        return None
    table,entries=matches[0]
    return {
        "pointer_table_file_offset_hex":hex(table),
        "pointer_table_mapped_address_hex":hex(map_offset_to_address(image,table)),
        "quota_error_index_zero_based":ERRORS.index(b"client quota exceeded"),
        "quota_error_string_file_offset_hex":hex(entries[5][0]),
        "quota_error_string_mapped_address_hex":hex(entries[5][1]),
        "verified_error_names":[x.decode("ascii") for x in ERRORS],
    }


def inspect(image:bytes):
    result={
        "model":MODEL,"version":VERSION,"status":"REJECTED",
        "stock_image_verified":False,"firmware_timer_bug_proven":False,
        "ota_signature_verified":False,"flash_safe":False,
    }
    if len(image)!=STOCK_SIZE or hashlib.sha256(image).hexdigest()!=STOCK_SHA256:
        result["reason"]="not exact catalogued stock color4 2.1.7_0039"
        return result
    structure=esp32(image)
    if not structure.get("structure_ok") or structure.get("segments")!=7:
        result["reason"]="ESP32 image integrity or segment count mismatch"
        return result
    error_table=firmware_error_table(image)
    if error_table is None:
        result["reason"]="complete error table not uniquely mapped"
        return result
    markers={}
    for family,values in LABELS.items():
        markers[family]=[value.decode("ascii") for value in values
                         if image.find(value+b"\0")>=0]
    result.update(
        status="STOCK_LAN_ERROR_TABLE_CONFIRMED",
        stock_image_verified=True,
        segment_count=7,
        image_internal_checksum_verified=True,
        image_embedded_sha256_verified=structure["internal_sha256_valid"],
        image_vendor_crc_verified=structure["xiaomi_four_byte_crc_valid"],
        error_table=error_table,
        compiled_protocol_text=markers,
        music_mode_supported_by_this_lamp="NOT_PROVEN_BY_STATIC_STRINGS",
        firmware_refill_implementation="NOT_IDENTIFIED",
        reason="Error enum and strings confirmed; real quota accounting and timing remain unknown",
    )
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image",type=Path)
    args=parser.parse_args()
    try:
        with args.image.open("rb") as f:
            data=f.read(MAX_FILE+1)
        output=inspect(data)
    except OSError as exc:
        output={"status":"REJECTED","reason":type(exc).__name__,
                "flash_safe":False}
    print(json.dumps(output,sort_keys=True,indent=2))
    return 0 if output.get("stock_image_verified") else 2


if __name__=="__main__":
    raise SystemExit(main())
