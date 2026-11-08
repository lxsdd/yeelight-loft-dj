#!/usr/bin/env python3
"""Read-only audit of private Classic OEM/startup components. Never sign or upload."""
from collections import Counter
from pathlib import Path
from zipfile import ZipFile, BadZipFile
import argparse
import json
import re
import sys
from apk_readonly_audit import parse_binary_xml, AxmlError
from classic_first import inspect_original, GateFailure
from dex_authority_remap import assert_self_consistent_dex, DexRemapError
from bridge.build_bridge import dex_descriptors, ToolError

OLD="com.yeelight.cherry"
NEW="io.lxsdd.yeelightdj"
A="{http://schemas.android.com/apk/res/android}"
COMPONENTS=("application","activity","activity-alias","service","receiver","provider","instrumentation")
EXTRA={"application":("appComponentFactory","backupAgent","manageSpaceActivity","zygotePreloadName"),
       "activity":("parentActivityName",),"activity-alias":("targetActivity",)}
SYSTEM=("Ljava/","Ljavax/","Landroid/","Ldalvik/","Lorg/json/","Lorg/xmlpull/",
        "Lorg/w3c/dom/","Lorg/xml/sax/","Lorg/apache/http/","Lcom/android/")
REMOVED=("com.google.firebase.provider.FirebaseInitProvider","com.yeelight.yeelib.data.DeviceInteractionProvider")
ADDED=(("activity","io.github.lxsdd.yeelightclassicdj.ui.ClassicDjActivity"),
       ("service","io.github.lxsdd.yeelightclassicdj.ui.ClassicDjService"))

class AuditBlocked(ValueError): pass

def require(value,reason):
    if not value: raise AuditBlocked(reason)

def num(b,o,n):
    require(0<=o and o+n<=len(b),"DEX_BOUNDS")
    return int.from_bytes(b[o:o+n],"little")

def dex_index(b):
    require(len(b)>=112 and b[:4]==b"dex\n" and b[7]==0,"DEX_HEADER")
    try: assert_self_consistent_dex(b)
    except DexRemapError as e: raise AuditBlocked("DEX_HASH") from e
    require(num(b,32,4)==len(b),"DEX_SIZE")
    sn,so=num(b,56,4),num(b,60,4)
    tn,to=num(b,64,4),num(b,68,4)
    cn,co=num(b,96,4),num(b,100,4)
    require(sn<=1000000 and tn<=100000 and cn<=100000,"DEX_TABLE_LIMIT")
    require(so+sn*4<=len(b) and to+tn*4<=len(b) and co+cn*32<=len(b),"DEX_TABLE_BOUNDS")
    offsets=[num(b,so+i*4,4) for i in range(sn)]
    types=[]
    for i in range(tn):
        ix=num(b,to+i*4,4)
        require(ix<sn,"DEX_TYPE_INDEX")
        pos=offsets[ix]
        for _ in range(5):
            require(pos<len(b),"DEX_STRING_BOUNDS")
            n=b[pos];pos+=1
            if n<128:break
        else: raise AuditBlocked("DEX_ULEB")
        end=b.find(b"\0",pos)
        require(end>=0,"DEX_STRING_END")
        try:types.append(b[pos:end].decode("ascii"))
        except UnicodeDecodeError as e:raise AuditBlocked("DEX_NONASCII_TYPE") from e
    classes={}
    for i in range(cn):
        off=co+i*32
        own,sup,inter=num(b,off,4),num(b,off+8,4),num(b,off+12,4)
        require(own<tn and (sup==0xffffffff or sup<tn),"DEX_CLASS_INDEX")
        parents=[] if sup==0xffffffff else [types[sup]]
        if inter:
            size=num(b,inter,4)
            require(size<=10000 and inter+4+size*2<=len(b),"DEX_INTERFACES")
            for j in range(size):
                t=num(b,inter+4+j*2,2)
                require(t<tn,"DEX_INTERFACE_INDEX")
                parents.append(types[t])
        name=types[own]
        require(name.startswith("L") and name.endswith(";") and name not in classes,"DEX_CLASS_DEF")
        classes[name]=parents
    try: require(set(classes)==dex_descriptors(b),"DEX_READERS_DISAGREE")
    except ToolError as e: raise AuditBlocked("DEX_SECOND_READER") from e
    return classes

def canonical(n,p):
    require(isinstance(n,str) and n and "$"+"{" not in n,"COMPONENT_NAME")
    return p+n if n.startswith(".") else p+"."+n if "." not in n else n

def manifest_index(parsed):
    roots=[x for x in parsed["elements"] if x["tag"]=="manifest"]
    require(len(roots)==1,"MANIFEST_ROOT")
    pkg=roots[0]["attributes"].get("package")
    require(pkg in (OLD,NEW),"MANIFEST_PACKAGE")
    components=Counter(); entries=set()
    for node in parsed["elements"]:
        tag=node["tag"]
        if tag not in COMPONENTS:continue
        attrs=node["attributes"];n=attrs.get(A+"name")
        require(n is not None or tag=="application","MANIFEST_UNNAMED")
        name=canonical(n,pkg) if n is not None else "<default>"
        components[(tag,name)]+=1
        if n is not None and tag!="activity-alias":entries.add("L"+name.replace(".","/")+";")
        for key in EXTRA.get(tag,()):
            v=attrs.get(A+key)
            if v:entries.add("L"+canonical(v,pkg).replace(".","/")+";")
    require(all(v==1 for v in components.values()),"MANIFEST_DUPLICATE")
    return pkg,components,entries

def inspect_apk(path):
    with ZipFile(path) as z:
        names=z.namelist()
        require(len(names)==len(set(names)),"APK_DUPLICATE_ENTRY")
        files=sorted(n for n in names if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex",n))
        require("classes.dex" in files,"PRIMARY_DEX_ABSENT")
        parsed=parse_binary_xml(z.read("AndroidManifest.xml"))
        defs={}
        for filename in files:
            found=dex_index(z.read(filename))
            require(not defs.keys()&found.keys(),"DUPLICATE_MULTIDEX_CLASS")
            defs.update(found)
    pkg,components,entrypoints=manifest_index(parsed)
    return {"package":pkg,"components":components,"entrypoints":entrypoints,
            "definitions":defs,"dex_count":len(files)}

def reachable(data):
    defs=data["definitions"];state={}
    def walk(d):
        if d.startswith(SYSTEM):return
        require(d in defs,"STARTUP_CLASS_OR_SUPER_MISSING")
        if state.get(d)==2:return
        require(state.get(d)!=1,"STARTUP_INHERITANCE_CYCLE")
        state[d]=1
        for parent in defs[d]:walk(parent)
        state[d]=2
    for d in data["entrypoints"]:walk(d)
    return len(data["entrypoints"])

def audit_pair(original,candidate):
    inspect_original(Path(original))
    o=inspect_apk(original);c=inspect_apk(candidate)
    require(o["package"]==OLD and c["package"]==NEW,"PACKAGE_PAIR")
    require(o["dex_count"]==2 and c["dex_count"]==3,"DEX_SHAPE")
    old_providers=[name for tag,name in o["components"] if tag=="provider"]
    old_activities=[name for tag,name in o["components"] if tag=="activity"]
    require(len(old_providers)==7 and len(old_activities)==185,"ORIGINAL_COMPONENT_COUNTS")
    require(set(REMOVED)<=set(old_providers),"ORIGINAL_EXPECTED_PROVIDER_ABSENT")
    expected=o["components"].copy()
    for n in REMOVED:del expected[("provider",n)]
    for entry in ADDED:expected[entry]+=1
    require(expected==c["components"],"CLONE_COMPONENT_DRIFT")
    # Only the two intentionally removed, non-executable provider entrypoints
    # may disappear from the clone. Reject any other manifest graph drift.
    removed={"L"+n.replace(".","/")+";" for n in REMOVED}
    added={"L"+n.replace(".","/")+";" for _,n in ADDED}
    require(o["entrypoints"]-c["entrypoints"]==removed,"REMOVED_PROVIDER_ENTRYPOINT_DRIFT")
    require(c["entrypoints"]-o["entrypoints"]==added,"ADDED_BRIDGE_ENTRYPOINT_DRIFT")
    # Exact OEM class definitions and their parent/interface relationships
    # must survive the rebuild. A missing retained OEM class is never waived.
    for descriptor,parents in o["definitions"].items():
        require(c["definitions"].get(descriptor)==parents,"OEM_DEX_GRAPH_DRIFT")
    # A removed provider is no longer a startup entrypoint of the clone.
    # However if any retained class inherits from it, reachable() still visits
    # it and refuses an unresolved superclass. No blanket third-party bypass.
    retained=dict(o,entrypoints=o["entrypoints"] & c["entrypoints"])
    try:
        old_n=reachable(retained)
    except AuditBlocked as exc:
        if str(exc)=="STARTUP_CLASS_OR_SUPER_MISSING":
            raise AuditBlocked("RETAINED_ORIGINAL_CLASS_OR_SUPER_MISSING") from exc
        raise
    try:
        new_n=reachable(c)
    except AuditBlocked as exc:
        if str(exc)=="STARTUP_CLASS_OR_SUPER_MISSING":
            raise AuditBlocked("CANDIDATE_CLASS_OR_SUPER_MISSING") from exc
        raise
    return {"status":"PRIVATE_STATIC_STARTUP_AUDIT_PASS_RUNTIME_NOT_TESTED",
            "original_components":sum(o["components"].values()),
            "clone_components":sum(c["components"].values()),
            "original_entrypoints":old_n,"clone_entrypoints":new_n,
            "original_dex":o["dex_count"],"clone_dex":c["dex_count"],
            "real_oem_read":True,"runtime":"NOT_TESTED"}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--original",type=Path,required=True)
    p.add_argument("--candidate",type=Path,required=True)
    args=p.parse_args()
    try:
        print(json.dumps(audit_pair(args.original,args.candidate),sort_keys=True))
        return 0
    except (AuditBlocked,GateFailure,AxmlError,OSError,BadZipFile,KeyError,ValueError) as e:
        reason=str(e) if isinstance(e,AuditBlocked) else "PRIVATE_INPUT_INVALID"
        print("PRIVATE_STARTUP_AUDIT_BLOCKED_"+reason,file=sys.stderr)
        return 2
if __name__=="__main__":raise SystemExit(main())
