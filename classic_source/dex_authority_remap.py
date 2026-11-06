#!/usr/bin/env python3
"""Strict, in-place-length DEX string remap for Classic's own ContentProvider authorities.

Purpose: a private side-by-side Classic fork must not retain the original provider
names in code after changing the manifest. Bytes are rewritten only in copied,
verified OEM DEX string_data_item values, never arbitrary machine instructions.
Unrelated OEM classes and other package-name strings remain unchanged.
"""
from __future__ import annotations
import hashlib
import struct
import zlib

OLD = 'com.yeelight.cherry'
NEW = 'io.lxsdd.yeelightdj'  # exactly the same UTF-8 / UTF-16 length as OLD
AUTHORITIES = frozenset({
    'com.yeelight.cherry.device.database',
    'com.yeelight.cherry.device',
    'com.yeelight.cherry.landevice',
    'com.yeelight.cherry.database.interaction',
    'com.yeelight.cherry.database.configuration',
    'com.yeelight.cherry.fileprovider',
    'com.yeelight.cherry.firebaseinitprovider',
})

class DexRemapError(ValueError): pass

def _u32(b: bytes, off: int) -> int:
    if off < 0 or off+4 > len(b): raise DexRemapError('DEX_OFFSET_INVALID')
    return struct.unpack_from('<I',b,off)[0]

def _uleb(b: bytes, off: int):
    value=0
    for i in range(5):
        if off+i >= len(b): raise DexRemapError('DEX_STRING_LENGTH_TRUNCATED')
        byte=b[off+i];value |= (byte & 127)<<(7*i)
        if not byte&128:return value,off+i+1
    raise DexRemapError('DEX_STRING_LENGTH_TOO_LONG')

def _entries(dex: bytes):
    if len(dex)<112 or dex[:4]!=b'dex\n' or dex[7]!=0 or _u32(dex,32)!=len(dex):
        raise DexRemapError('DEX_HEADER_INVALID')
    n,off=_u32(dex,56),_u32(dex,60)
    if n>100000 or off+n*4>len(dex):raise DexRemapError('DEX_STRING_TABLE_INVALID')
    for i in range(n):
        pos=_u32(dex,off+i*4)
        count,start=_uleb(dex,pos)
        if count>100000 or start>=len(dex):raise DexRemapError('DEX_STRING_VALUE_INVALID')
        end=dex.find(b'\0',start,min(len(dex),start+count*3+1))
        if end<0:raise DexRemapError('DEX_STRING_UNTERMINATED')
        yield start,end,dex[start:end]

def _replacement(original: bytes, authorities=frozenset(AUTHORITIES)):
    """Only literal authority values or exact content://authority with path/query."""
    try:
        value=original.decode('ascii')
    except UnicodeDecodeError:
        return None
    for auth in sorted(authorities,key=len,reverse=True):
        if value==auth:
            return (NEW+value[len(OLD):]).encode('ascii')
        prefix='content://'+auth
        if value==prefix or (value.startswith(prefix) and value[len(prefix):len(prefix)+1] in ('/','?','#')):
            return ('content://'+NEW+value[len('content://'+OLD):]).encode('ascii')
    return None

def remap_dex(dex: bytes, authorities=frozenset(AUTHORITIES)) -> tuple[bytes,dict]:
    if len(NEW.encode()) != len(OLD.encode()) or len(NEW) != len(OLD):
        raise DexRemapError('PACKAGE_REPLACEMENT_LENGTH_MISMATCH')
    if not authorities or not set(authorities).issubset(AUTHORITIES):
        raise DexRemapError('UNKNOWN_AUTHORITY')
    assert_self_consistent_dex(dex)
    buf=bytearray(dex)
    replaced=[]
    for start,end,raw in _entries(dex):
        new=_replacement(raw,authorities)
        if new is None:continue
        if len(new)!=end-start:raise DexRemapError('STRING_SIZE_CHANGED')
        buf[start:end]=new
        replaced.append({'old':raw.decode('ascii'),'new':new.decode('ascii')})
    if replaced:
        buf[12:32]=hashlib.sha1(buf[32:]).digest()
        buf[8:12]=struct.pack('<I',zlib.adler32(buf[12:]) & 0xffffffff)
    return bytes(buf), {'changed_string_count':len(replaced),'changes':replaced,
                         'original_sha256':hashlib.sha256(dex).hexdigest(),
                         'remapped_sha256':hashlib.sha256(buf).hexdigest()}

def assert_self_consistent_dex(dex: bytes):
    # All class-definition offsets, IDs and string counts are untouched by remap.
    if hashlib.sha1(dex[32:]).digest() != dex[12:32]:raise DexRemapError('DEX_SHA1_INVALID')
    if (zlib.adler32(dex[12:]) & 0xffffffff) != _u32(dex,8):raise DexRemapError('DEX_ADLER32_INVALID')
    list(_entries(dex))
