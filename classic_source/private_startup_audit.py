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
from activity_provenance import (index_activity_metadata, summarize_missing_activities, ProvenanceError)
from class_source_inventory import classify_pinned_sources, SourceInventoryError
from activity_provenance import (validate_diagnostic_report, ClasspathProvenanceError)

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

FRAMEWORK_MANIFEST=("Landroid/","Lcom/android/")
REQUIRED_MANIFEST_ROLES=("application","activity","service","receiver","provider",
                         "instrumentation","appComponentFactory")
STARTUP_MANIFEST_ROLES=("application","activity","service","receiver","provider","appComponentFactory")
MANIFEST_ROLES=set(COMPONENTS)|{role for group in EXTRA.values() for role in group}

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
    components=Counter(); entries=set(); refs=Counter()
    for node in parsed["elements"]:
        tag=node["tag"]
        if tag not in COMPONENTS: continue
        attrs=node["attributes"]; n=attrs.get(A+"name")
        require(n is not None or tag=="application","MANIFEST_UNNAMED")
        name=canonical(n,pkg) if n is not None else "<default>"
        components[(tag,name)]+=1
        if n is not None:
            desc="L"+name.replace(".","/")+";"
            refs[(tag,desc)]+=1
            if tag!="activity-alias": entries.add(desc)
        if tag=="activity-alias":
            require(attrs.get(A+"targetActivity"),"MANIFEST_ALIAS_TARGET_MISSING")
        for key in EXTRA.get(tag,()):
            value=attrs.get(A+key)
            if value:
                desc="L"+canonical(value,pkg).replace(".","/")+";"
                refs[(key,desc)]+=1
                entries.add(desc)
    require(all(v==1 for v in components.values()),"MANIFEST_DUPLICATE")
    return pkg,components,entries,refs

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
    pkg,components,entrypoints,refs=manifest_index(parsed)
    activity_metadata=index_activity_metadata(parsed,pkg)
    uses_libraries=sum(node["tag"] in
                       ("uses-library","uses-static-library","uses-native-library")
                       for node in parsed["elements"])
    return {"package":pkg,"components":components,"entrypoints":entrypoints,
            "manifest_references":refs,"declared_shared_libraries":uses_libraries,
            "activity_metadata":activity_metadata,
            "definitions":defs,"dex_count":len(files)}

def classify_manifest_references(data, *, exempt_providers=()):
    """Separate manifest identities, executable classes, helpers and framework.

    An alias name is NOT a class. Framework references may live outside DEX,
    but availability on Android 17 is not inferred. All non-platform gaps stay
    unqualified, with role counters only (no proprietary class names).
    """
    refs=data.get("manifest_references")
    require(isinstance(refs,Counter),"MANIFEST_REFERENCE_METADATA_REQUIRED")
    definitions=data["definitions"]
    declared_activities={d for (role,d),n in refs.items() if role=="activity" and n}
    omitted={"L"+name.replace(".","/")+";" for name in exempt_providers}
    roles=Counter();missing=Counter();framework=Counter()
    invalid_alias=0;exempted=0;roots=set()
    for (role,desc),n in refs.items():
        require(role in MANIFEST_ROLES and isinstance(desc,str) and
                desc.startswith("L") and desc.endswith(";") and
                type(n) is int and n>0,"MANIFEST_REFERENCE_INVALID")
        roles[role]+=n
        if role=="activity-alias":
            continue
        if role=="provider" and desc in omitted:
            exempted+=n
            continue
        if role=="targetActivity" and desc not in declared_activities:
            invalid_alias+=n
        if desc in definitions:
            if role in STARTUP_MANIFEST_ROLES: roots.add(desc)
        elif desc.startswith(FRAMEWORK_MANIFEST):
            framework[role]+=n
        else:
            missing[role]+=n
    required=sum(n for role,n in missing.items() if role in REQUIRED_MANIFEST_ROLES)
    supporting=sum(n for role,n in missing.items() if role not in REQUIRED_MANIFEST_ROLES)
    return {"reference_count":sum(roles.values()),
            "roles":dict(sorted(roles.items())),
            "missing_by_role":dict(sorted(missing.items())),
            "framework_by_role":dict(sorted(framework.items())),
            "missing_required":required,
            "missing_supporting":supporting,
            "undeclared_alias_targets":invalid_alias,
            "exempted_removed_providers":exempted,
            "resolved_startup_roots":roots}


def reachable(data, *, missing_parents=None):
    """Validate manifest roots; optionally record absent non-platform parent edges.

    An absent manifest entrypoint never qualifies as a pre-existing baseline gap.
    Original parent gaps may be compared, not presumed resolved at runtime.
    """
    defs=data["definitions"];state={}
    for root in data["entrypoints"]:
        if not root.startswith(SYSTEM):
            require(root in defs,"STARTUP_ENTRYPOINT_CLASS_MISSING")
    def walk(d):
        if d.startswith(SYSTEM):return
        require(d in defs,"STARTUP_CLASS_OR_SUPER_MISSING")
        if state.get(d)==2:return
        require(state.get(d)!=1,"STARTUP_INHERITANCE_CYCLE")
        state[d]=1
        for parent in defs[d]:
            if not parent.startswith(SYSTEM) and parent not in defs and missing_parents is not None:
                missing_parents.add((d,parent))
                continue
            walk(parent)
        state[d]=2
    for d in data["entrypoints"]:walk(d)
    return len(data["entrypoints"])

def unresolved_edges(definitions, owners=None):
    """Return unresolved direct superclass/interface edges, without logging symbols."""
    scope=set(definitions) if owners is None else set(owners)
    return {(owner,parent)
            for owner in scope
            for parent in definitions[owner]
            if not parent.startswith(SYSTEM) and parent not in definitions}

def qualify_report(result):
    """An unchanged but unresolved OEM graph is not a complete startup audit."""
    status=result.get("status")
    count=result.get("original_baseline_unresolved_parent_edges")
    all_count=result.get("original_total_unresolved_parent_edges")
    require(type(count) is int and type(all_count) is int and
            0 <= count <= all_count,"PRIVATE_REPORT_INVALID")
    if status=="PRIVATE_STATIC_PARITY_WITH_UNRESOLVED_OEM_BASELINE_RUNTIME_NOT_TESTED":
        require(all_count>0,"PRIVATE_REPORT_INVALID")
        raise AuditBlocked("ORIGINAL_BASELINE_PARENT_UNVERIFIED")
    require(status=="PRIVATE_STATIC_STARTUP_AUDIT_PASS_RUNTIME_NOT_TESTED" and
            count==0 and all_count==0 and
            result.get("runtime")=="NOT_TESTED" and
            result.get("real_oem_read") is True,
            "PRIVATE_REPORT_INVALID")
    return True

def original_prebuild_report(original, *, candidate_specs=()):
    """Classify every manifest class role before a private clone is assembled.

    All original OEM names remain in memory only; the serialized report contains
    role labels, counts and sanitized status codes. Missing classes are NOT
    inferred to exist from a library name or framework-looking vendor prefix.
    """
    inspect_original(Path(original))
    baseline=inspect_apk(original)
    require(baseline["package"]==OLD,"ORIGINAL_PREBUILD_PACKAGE")
    require(baseline["dex_count"]==2,"ORIGINAL_PREBUILD_DEX_SHAPE")
    providers={n for kind,n in baseline["components"] if kind=="provider"}
    activities={n for kind,n in baseline["components"] if kind=="activity"}
    require(len(providers)==7 and len(activities)==185,
            "ORIGINAL_PREBUILD_COMPONENT_COUNTS")
    require(set(REMOVED)<=providers,"ORIGINAL_PREBUILD_PROVIDER_CONTRACT")
    summary=classify_manifest_references(baseline,exempt_providers=REMOVED)
    require("activity_metadata" in baseline,"ORIGINAL_ACTIVITY_PROVENANCE_MISSING")
    declared={d for (role,d),n in baseline["manifest_references"].items()
              if role=="activity" and n}
    activity_provenance=summarize_missing_activities(
        baseline["activity_metadata"],baseline["definitions"],declared)
    # Pinned splits/libraries are candidate inventories, not actual classloader proof.
    classpath_provenance=classify_pinned_sources(
        baseline["definitions"],declared,system_prefixes=SYSTEM,
        candidate_specs=candidate_specs,dex_reader=dex_index)
    require(activity_provenance["missing_count"]==summary["missing_by_role"].get("activity",0),
            "ORIGINAL_ACTIVITY_PROVENANCE_COUNTER_DRIFT")
    missing_start=set()
    try:
        reachable(dict(baseline,entrypoints=summary["resolved_startup_roots"]),
                  missing_parents=missing_start)
    except AuditBlocked as exc:
        raise AuditBlocked("ORIGINAL_PREBUILD_INHERITANCE_INVALID") from exc
    missing_all=unresolved_edges(baseline["definitions"])
    require(missing_start<=missing_all,"ORIGINAL_PREBUILD_GRAPH_COUNTER_INVALID")
    validate_diagnostic_report(
        classpath_provenance,
        expected_missing_activities=activity_provenance["missing_count"],
        expected_unresolved_edges=len(missing_all))
    if summary["undeclared_alias_targets"]:
        status="PRIVATE_ORIGINAL_ALIAS_TARGET_UNDECLARED_BLOCKED"
    elif summary["missing_required"]:
        status="PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED"
    elif summary["missing_supporting"]:
        status="PRIVATE_ORIGINAL_SUPPORT_MANIFEST_CLASS_MISSING_BLOCKED"
    elif missing_all:
        status="PRIVATE_ORIGINAL_BASELINE_UNRESOLVED_PREBUILD_BLOCKED"
    else:
        status="PRIVATE_ORIGINAL_BASELINE_STRUCTURAL_READY_RUNTIME_NOT_TESTED"
    return {"status":status,
            "original_missing_activity_provenance":activity_provenance,
            "original_classpath_provenance":classpath_provenance,
            "manifest_reference_count":summary["reference_count"],
            "manifest_roles":summary["roles"],
            "manifest_missing_by_role":summary["missing_by_role"],
            "manifest_framework_by_role":summary["framework_by_role"],
            "manifest_missing_required_count":summary["missing_required"],
            "manifest_missing_support_count":summary["missing_supporting"],
            "manifest_invalid_alias_target_count":summary["undeclared_alias_targets"],
            "original_retained_startup_entrypoints":len(summary["resolved_startup_roots"]),
            "original_missing_startup_parent_edges":len(missing_start),
            "original_total_missing_parent_edges":len(missing_all),
            "original_declared_removed_providers":len(REMOVED),
            "original_exempt_removed_provider_references":summary["exempted_removed_providers"],
            "original_declared_shared_libraries":baseline["declared_shared_libraries"],
            "real_oem_read":True,"runtime":"NOT_TESTED"}

def qualify_original_prebuild(report):
    """Only fully classified and internally consistent original graphs pass."""
    start=report.get("original_missing_startup_parent_edges")
    total=report.get("original_total_missing_parent_edges")
    required=report.get("manifest_missing_required_count")
    support=report.get("manifest_missing_support_count")
    alias=report.get("manifest_invalid_alias_target_count")
    count=report.get("manifest_reference_count")
    roots=report.get("original_retained_startup_entrypoints")
    libraries=report.get("original_declared_shared_libraries")
    missing=report.get("manifest_missing_by_role")
    framework=report.get("manifest_framework_by_role")
    roles=report.get("manifest_roles")
    require(all(type(x) is int and x>=0 for x in
                (start,total,required,support,alias,count,roots,libraries)) and
            start<=total and
            report.get("real_oem_read") is True and report.get("runtime")=="NOT_TESTED" and
            report.get("original_declared_removed_providers")==len(REMOVED) and
            report.get("original_exempt_removed_provider_references")==len(REMOVED) and
            all(isinstance(x,dict) and
                all(key in MANIFEST_ROLES and type(n) is int and n>0
                    for key,n in x.items())
                for x in (missing,framework,roles)) and
            sum(missing.values())==required+support and
            sum(roles.values())==count and
            sum(n for role,n in missing.items() if role in REQUIRED_MANIFEST_ROLES)==required,
            "ORIGINAL_PREBUILD_REPORT_INVALID")
    provenance=report.get("original_missing_activity_provenance")
    require(isinstance(provenance,dict) and
            type(provenance.get("missing_count")) is int and
            isinstance(provenance.get("declared_enabled_by_missing"),dict) and
            set(provenance["declared_enabled_by_missing"])==
                {"explicit_true","explicit_false","inherited","unresolved_resource"} and
            all(type(n) is int and n>=0 for n in provenance["declared_enabled_by_missing"].values()) and
            sum(provenance["declared_enabled_by_missing"].values())==provenance["missing_count"] and
            isinstance(missing,dict) and
            provenance["missing_count"]==missing.get("activity",0) and
            type(provenance.get("split_labeled_missing_count")) is int and
            0<=provenance["split_labeled_missing_count"]<=provenance["missing_count"] and
            type(provenance.get("process_labeled_missing_count")) is int and
            0<=provenance["process_labeled_missing_count"]<=provenance["missing_count"] and
            provenance.get("missing_classes_waived")==0 and
            provenance.get("runtime")=="NOT_TESTED",
            "ORIGINAL_ACTIVITY_PROVENANCE_REPORT_INVALID")
    cp=report.get("original_classpath_provenance")
    try:
        validate_diagnostic_report(
            cp, expected_missing_activities=provenance["missing_count"],
            expected_unresolved_edges=total)
    except ClasspathProvenanceError as exc:
        raise AuditBlocked("ORIGINAL_CLASSPATH_PROVENANCE_REPORT_INVALID") from exc
    status=report.get("status")
    if alias:
        require(status=="PRIVATE_ORIGINAL_ALIAS_TARGET_UNDECLARED_BLOCKED",
                "ORIGINAL_PREBUILD_REPORT_INVALID")
        raise AuditBlocked("ORIGINAL_PREBUILD_ALIAS_TARGET_UNDECLARED")
    if required:
        require(status=="PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED",
                "ORIGINAL_PREBUILD_REPORT_INVALID")
        raise AuditBlocked("ORIGINAL_PREBUILD_REQUIRED_CLASS_UNVERIFIED")
    if support:
        require(status=="PRIVATE_ORIGINAL_SUPPORT_MANIFEST_CLASS_MISSING_BLOCKED",
                "ORIGINAL_PREBUILD_REPORT_INVALID")
        raise AuditBlocked("ORIGINAL_PREBUILD_SUPPORT_CLASS_UNVERIFIED")
    if total:
        require(status=="PRIVATE_ORIGINAL_BASELINE_UNRESOLVED_PREBUILD_BLOCKED",
                "ORIGINAL_PREBUILD_REPORT_INVALID")
        raise AuditBlocked("ORIGINAL_PREBUILD_PARENT_UNVERIFIED")
    require(status=="PRIVATE_ORIGINAL_BASELINE_STRUCTURAL_READY_RUNTIME_NOT_TESTED"
            and start==0,"ORIGINAL_PREBUILD_REPORT_INVALID")
    return True


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
    expected_roles=o["manifest_references"].copy()
    for name in REMOVED:
        expected_roles[("provider","L"+name.replace(".","/")+";")]-=1
        if expected_roles[("provider","L"+name.replace(".","/")+";")]==0:
            del expected_roles[("provider","L"+name.replace(".","/")+";")]
    for kind,name in ADDED:
        expected_roles[(kind,"L"+name.replace(".","/")+";")]+=1
    require(expected_roles==c["manifest_references"],
            "CLONE_MANIFEST_REFERENCE_ROLE_DRIFT")
    # Exact OEM class definitions and their parent/interface relationships
    # must survive the rebuild. A missing retained OEM class is never waived.
    for descriptor,parents in o["definitions"].items():
        require(c["definitions"].get(descriptor)==parents,"OEM_DEX_GRAPH_DRIFT")
    # A removed provider is no longer a startup entrypoint of the clone.
    # However if any retained class inherits from it, reachable() still visits
    # it and refuses an unresolved superclass. No blanket third-party bypass.
    # All OEM class defs + direct parents/interfaces were already checked for
    # byte-identical structure. A missing inherited type in the *original*
    # can reflect a separately supplied classpath, but runtime is NOT verified.
    retained=dict(o,entrypoints=o["entrypoints"] & c["entrypoints"])
    inherited_missing=set()
    clone_missing=set()
    try:
        old_n=reachable(retained,missing_parents=inherited_missing)
    except AuditBlocked as exc:
        if str(exc)=="STARTUP_ENTRYPOINT_CLASS_MISSING":
            raise AuditBlocked("RETAINED_ORIGINAL_ENTRYPOINT_CLASS_MISSING") from exc
        raise
    # Intentionally removed providers cannot remain necessary OEM superclasses.
    require(not any(parent in removed for _,parent in inherited_missing),
            "RETAINED_INHERITS_REMOVED_PROVIDER")
    try:
        new_n=reachable(c,missing_parents=clone_missing)
    except AuditBlocked as exc:
        if str(exc)=="STARTUP_ENTRYPOINT_CLASS_MISSING":
            raise AuditBlocked("CANDIDATE_ENTRYPOINT_CLASS_MISSING") from exc
        raise
    # Identical inherited gaps are an explicit original limitation; any
    # *additional* unresolved direct edge in the clone is a hard failure.
    require(clone_missing==inherited_missing,"CANDIDATE_UNRESOLVED_PARENT_DRIFT")
    # New DJ roots cannot introduce a dependency on an unresolved OEM parent.
    try:
        reachable(dict(c,entrypoints=added))
    except AuditBlocked as exc:
        if str(exc) in ("STARTUP_ENTRYPOINT_CLASS_MISSING","STARTUP_CLASS_OR_SUPER_MISSING"):
            raise AuditBlocked("BRIDGE_ENTRYPOINT_OR_PARENT_MISSING") from exc
        raise
    old_roles=classify_manifest_references(o,exempt_providers=REMOVED)
    new_roles=classify_manifest_references(c)
    require(not old_roles["undeclared_alias_targets"] and
            not new_roles["undeclared_alias_targets"],
            "ORIGINAL_OR_CLONE_ALIAS_TARGET_INVALID")
    require(not old_roles["missing_required"] and
            not old_roles["missing_supporting"],"ORIGINAL_MANIFEST_REFERENCES_UNVERIFIED")
    require(not new_roles["missing_required"] and
            not new_roles["missing_supporting"],"CANDIDATE_MANIFEST_REFERENCES_UNVERIFIED")
    # Also inspect dormant OEM classes: reflection and later navigation can
    # load types that are not reachable from immediate manifest entrypoints.
    full_original_missing=unresolved_edges(o["definitions"])
    require(inherited_missing<=full_original_missing,"ORIGINAL_EDGE_COUNTER_DRIFT")
    new_classes=set(c["definitions"])-set(o["definitions"])
    require(not unresolved_edges(c["definitions"],new_classes),
            "CANDIDATE_NEW_CLASS_PARENT_UNVERIFIED")
    status=("PRIVATE_STATIC_PARITY_WITH_UNRESOLVED_OEM_BASELINE_RUNTIME_NOT_TESTED"
            if full_original_missing else "PRIVATE_STATIC_STARTUP_AUDIT_PASS_RUNTIME_NOT_TESTED")
    return {"status":status,"original_baseline_unresolved_parent_edges":len(inherited_missing),
            "original_total_unresolved_parent_edges":len(full_original_missing),
            "original_components":sum(o["components"].values()),
            "clone_components":sum(c["components"].values()),
            "original_entrypoints":old_n,"clone_entrypoints":new_n,
            "original_dex":o["dex_count"],"clone_dex":c["dex_count"],
            "real_oem_read":True,"runtime":"NOT_TESTED"}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--original",type=Path,required=True)
    p.add_argument("--candidate",type=Path)
    p.add_argument("--original-only",action="store_true",
                   help="Preflight pinned original classgraph without a candidate")
    p.add_argument("--supplemental-apk",type=Path,action="append",default=[])
    p.add_argument("--supplemental-sha256",action="append",default=[])
    p.add_argument("--supplemental-kind",choices=("split_apk","shared_library"),
                   action="append",default=[])
    args=p.parse_args()
    try:
        lengths={len(args.supplemental_apk),len(args.supplemental_sha256),
                 len(args.supplemental_kind)}
        require(len(lengths)==1,"CANDIDATE_SOURCE_INPUT_MISMATCH")
        specs=tuple(zip(args.supplemental_apk,args.supplemental_sha256,
                        args.supplemental_kind))
        if args.original_only:
            require(args.candidate is None,"ORIGINAL_PREBUILD_CANDIDATE_FORBIDDEN")
            report=original_prebuild_report(args.original,candidate_specs=specs)
            print(json.dumps(report,sort_keys=True))
            qualify_original_prebuild(report)
        else:
            require(not specs,"CANDIDATE_SOURCE_ORIGINAL_ONLY_REQUIRED")
            require(args.candidate is not None,"CANDIDATE_REQUIRED")
            report=audit_pair(args.original,args.candidate)
            print(json.dumps(report,sort_keys=True))
            qualify_report(report)
        return 0
    except (AuditBlocked,ProvenanceError,SourceInventoryError,ClasspathProvenanceError,
            GateFailure,AxmlError,OSError,BadZipFile,KeyError,ValueError) as e:
        reason=str(e) if isinstance(
            e,(AuditBlocked,ProvenanceError,SourceInventoryError,ClasspathProvenanceError)) else "PRIVATE_INPUT_INVALID"
        print("PRIVATE_STARTUP_AUDIT_BLOCKED_"+reason,file=sys.stderr)
        return 2
if __name__=="__main__":raise SystemExit(main())
