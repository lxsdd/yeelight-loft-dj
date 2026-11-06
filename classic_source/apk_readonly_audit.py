#!/usr/bin/env python3
"""Passive Android binary-XML inspection of EXACT original Classic APK.

No decoder/decompiler dependency, never changes bytes. Only emits package,
component/authority structure and layout roots: no accounts, tokens, device data.
"""
import argparse
import json
import struct
from pathlib import Path
from zipfile import ZipFile
from classic_first import inspect_original, ORIGINAL_SHA256

class AxmlError(ValueError): pass


def u16(data, offset):
    if offset < 0 or offset + 2 > len(data): raise AxmlError('u16 out of bounds')
    return struct.unpack_from('<H', data, offset)[0]

def u32(data, offset):
    if offset < 0 or offset + 4 > len(data): raise AxmlError('u32 out of bounds')
    return struct.unpack_from('<I', data, offset)[0]

def variable_utf8_length(data, offset):
    if offset >= len(data): raise AxmlError('truncated utf8 len')
    b = data[offset]; offset += 1
    if b & 0x80:
        if offset >= len(data): raise AxmlError('truncated utf8 len')
        return ((b & 0x7f) << 8) | data[offset], offset + 1
    return b, offset

def variable_utf16_length(data, offset):
    a = u16(data,offset); offset += 2
    if a & 0x8000:
        b = u16(data,offset); offset += 2
        return ((a & 0x7fff) << 16) | b, offset
    return a, offset

def string_pool(data, start, end):
    header = u16(data,start+2)
    n = u32(data,start+8)
    flags = u32(data,start+16)
    offsets = start+header
    text_offset = start+u32(data,start+20)
    if n > 200000 or offsets+n*4 > end: raise AxmlError('bad pool lengths')
    strings=[]
    for i in range(n):
        off = text_offset+u32(data,offsets+4*i)
        if off >= end: raise AxmlError('bad string offset')
        if flags & 256:
            _, off = variable_utf8_length(data,off)
            byte_len, off = variable_utf8_length(data,off)
            if off+byte_len+1 > end or data[off+byte_len] != 0: raise AxmlError('bad utf8 string')
            value=data[off:off+byte_len].decode('utf8')
        else:
            count, off=variable_utf16_length(data,off)
            if off+count*2+2 > end or u16(data,off+count*2) != 0: raise AxmlError('bad utf16 string')
            value=data[off:off+count*2].decode('utf-16-le')
        strings.append(value)
    return strings

def parse_binary_xml(data):
    if len(data) < 8 or u16(data,0) != 0x0003 or u16(data,2) != 8 or u32(data,4) != len(data):
        raise AxmlError('not Android binary XML')
    off=8; pool=None; elements=[]; namespaces={}
    while off < len(data):
        typ=u16(data,off); header=u16(data,off+2); size=u32(data,off+4)
        if header < 8 or size < header or off+size > len(data): raise AxmlError('malformed xml chunk')
        if typ == 0x0001:
            if pool is not None: raise AxmlError('duplicate string pool')
            pool=string_pool(data,off,off+size)
        if typ in (0x0100,0x0102):
            if pool is None: raise AxmlError('missing string pool')
        if typ==0x0100:
            prefix=u32(data,off+16); uri=u32(data,off+20)
            if prefix<len(pool) and uri<len(pool):namespaces[pool[prefix]]=pool[uri]
        if typ==0x0102:
            if header < 16: raise AxmlError('bad start element')
            ix=u32(data,off+20)
            if ix>=len(pool):raise AxmlError('bad element name')
            attr_start=u16(data,off+24); attr_size=u16(data,off+26); num=u16(data,off+28)
            if attr_size < 20 or num > 1000:raise AxmlError('bad attribute count')
            pos=off+16+attr_start
            if pos+num*attr_size > off+size: raise AxmlError('attributes overflow chunk')
            attrs={}
            for i in range(num):
                p=pos+i*attr_size; ns_id=u32(data,p); name_id=u32(data,p+4)
                raw=u32(data,p+8); vt=data[p+15]; val=u32(data,p+16)
                if name_id>=len(pool):raise AxmlError('bad attribute name')
                name=pool[name_id]
                if ns_id!=0xffffffff and ns_id<len(pool): name='{'+pool[ns_id]+'}'+name
                if raw != 0xffffffff and raw < len(pool):value=pool[raw]
                elif vt == 3 and val<len(pool):value=pool[val]
                elif vt in (0x10,0x11):value=str(val)
                elif vt==0x12:value='true' if val else 'false'
                else:value=f'@type{vt:02x}:{val:08x}'
                attrs[name]=value
            elements.append({'tag':pool[ix], 'attributes':attrs})
        off += size
    if pool is None or not elements: raise AxmlError('missing XML')
    return {'elements':elements, 'namespaces':namespaces, 'string_count':len(pool)}


def inspect(path):
    original=inspect_original(path)
    with ZipFile(path) as z:
        manifest=parse_binary_xml(z.read('AndroidManifest.xml'))
        # The static verified mapping identified res/Ms.xml as activity_main_new.
        layout=parse_binary_xml(z.read('res/Ms.xml'))
    e=manifest['elements']; a=lambda tag: [x for x in e if x['tag']==tag]
    mn=a('manifest')[0]['attributes']
    package=mn.get('package')
    if package != 'com.yeelight.cherry': raise AxmlError('unexpected OEM package')
    p=[x['attributes'] for x in a('provider')]
    activities=a('activity')
    sdk=a('uses-sdk')
    return {'original_sha256':original['original_sha256'], 'package':package,
        'manifest_element_count':len(e), 'manifest_activities':len(activities),
        'uses_sdk':sdk[0]['attributes'] if sdk else {},
        'providers':p,
        'layout_resource_original':'res/Ms.xml',
        'layout_root':layout['elements'][0]['tag'],
        'layout_element_count':len(layout['elements']),
        'notes':'Manifest and layout are static binary XML, not OEM runtime behavior'}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--original',type=Path,required=True)
    args=parser.parse_args()
    try:
        result=inspect(args.original);print(json.dumps(result,indent=2,ensure_ascii=False));return 0
    except (OSError,AxmlError,ValueError,KeyError) as e:
        print(json.dumps({'status':'REFUSED','reason':str(e)}));return 2
if __name__=='__main__':raise SystemExit(main())
