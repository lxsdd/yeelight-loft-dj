"""Offline negative/positive tests for the PRIVATE real-APK startup audit."""
from collections import Counter
import hashlib
import struct
import unittest
from unittest.mock import patch
import zlib
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import private_startup_audit as audit

ROOT="com.yeelight.cherry"
BASE="Ljava/lang/Object;"

def model():
    names=["com.google.firebase.provider.FirebaseInitProvider",
           "com.yeelight.yeelib.data.DeviceInteractionProvider",
           "com.yeelight.yeelib.data.DeviceBrowserProvider",
           "com.yeelight.cherry.OtherOne",
           "com.yeelight.cherry.OtherTwo",
           "com.yeelight.cherry.OtherThree",
           "com.yeelight.cherry.OtherFour"]
    all_activities=["com.yeelight.cherry.Activity%d"%i for i in range(185)]
    original=Counter({("provider",n):1 for n in names})
    original.update({("activity",n):1 for n in all_activities})
    original[("application","com.yeelight.cherry.App")]=1
    clone=original.copy()
    for n in audit.REMOVED: del clone[("provider",n)]
    for n in audit.ADDED: clone[n]+=1
    orig_desc={"L"+n.replace(".","/")+";" for n in names+all_activities+["com.yeelight.cherry.App"]}
    clone_desc=orig_desc | {"L"+n.replace(".","/")+";" for _,n in audit.ADDED}
    o={"package":audit.OLD,"components":original,"entrypoints":orig_desc,
       "definitions":{d:[BASE] for d in orig_desc},"dex_count":2}
    c={"package":audit.NEW,"components":clone,"entrypoints":clone_desc,
       "definitions":{d:[BASE] for d in clone_desc},"dex_count":3}
    return o,c

def fake_dex():
    """Real minimal DEX header, string/type/class tables and valid SHA/Adler32."""
    types=["Ljava/lang/Object;","Lcom/example/Base;","Lcom/example/Child;"]
    sn=len(types);tn=len(types);cn=2
    strings_start=112+sn*4+tn*4+cn*32
    blob=bytearray(strings_start)
    blob[0:8]=b"dex\n035\0"
    struct.pack_into("<I",blob,56,sn);struct.pack_into("<I",blob,60,112)
    struct.pack_into("<I",blob,64,tn);struct.pack_into("<I",blob,68,112+sn*4)
    struct.pack_into("<I",blob,96,cn);struct.pack_into("<I",blob,100,112+sn*4+tn*4)
    for i,t in enumerate(types):
        struct.pack_into("<I",blob,112+i*4,len(blob))
        blob.append(len(t));blob.extend(t.encode("ascii"));blob.append(0)
        struct.pack_into("<I",blob,112+sn*4+i*4,i)
    c=112+sn*4+tn*4
    struct.pack_into("<IIIIIIII",blob,c,1,0,0,0,0xffffffff,0,0,0)
    struct.pack_into("<IIIIIIII",blob,c+32,2,0,1,0,0xffffffff,0,0,0)
    struct.pack_into("<I",blob,32,len(blob))
    blob[12:32]=hashlib.sha1(blob[32:]).digest()
    struct.pack_into("<I",blob,8,zlib.adler32(blob[12:])&0xffffffff)
    return bytes(blob)

class TestDexIndex(unittest.TestCase):
    def test_real_binary_class_defs_and_superclasses(self):
        classes=audit.dex_index(fake_dex())
        self.assertEqual(classes["Lcom/example/Base;"],["Ljava/lang/Object;"])
        self.assertEqual(classes["Lcom/example/Child;"],["Lcom/example/Base;"])
    def test_modified_dex_payload_rejected(self):
        wrong=bytearray(fake_dex());wrong[-2]^=4
        with self.assertRaisesRegex(audit.AuditBlocked,"DEX_HASH"):
            audit.dex_index(bytes(wrong))
    def test_malformed_header_rejected(self):
        with self.assertRaisesRegex(audit.AuditBlocked,"DEX_HEADER"):
            audit.dex_index(b"not dex")
    def test_unresolved_parent_cannot_pass_as_class_def(self):
        o,c=model()
        d=next(iter(c["entrypoints"]))
        c["definitions"][d]=["Lvendor/missing/Super;"]
        with self.assertRaisesRegex(audit.AuditBlocked,"STARTUP_CLASS_OR_SUPER_MISSING"):
            audit.reachable(c)
    def test_cycle_rejected(self):
        o,c=model();d=next(iter(c["entrypoints"]));c["definitions"][d]=[d]
        with self.assertRaisesRegex(audit.AuditBlocked,"STARTUP_INHERITANCE_CYCLE"):
            audit.reachable(c)

class TestPrivatePairs(unittest.TestCase):
    def checked(self,o,c):
        with patch.object(audit,"inspect_original"),patch.object(audit,"inspect_apk",side_effect=[o,c]):
            return audit.audit_pair(Path("not-real-original.apk"),Path("not-real-clone.apk"))
    def test_full_manifest_and_both_hierarchies_pass(self):
        o,c=model();info=self.checked(o,c)
        self.assertEqual(info["status"],"PRIVATE_STATIC_STARTUP_AUDIT_PASS_RUNTIME_NOT_TESTED")
        self.assertEqual(info["original_dex"],2)
        self.assertEqual(info["clone_dex"],3)
        self.assertEqual(info["runtime"],"NOT_TESTED")
    def test_missing_oem_device_browser_provider_refused(self):
        o,c=model()
        descriptor="Lcom/yeelight/yeelib/data/DeviceBrowserProvider;"
        o["definitions"].pop(descriptor)
        with self.assertRaisesRegex(audit.AuditBlocked,"STARTUP_CLASS_OR_SUPER_MISSING"):
            self.checked(o,c)
    def test_no_provider_deletion_guess_accepted(self):
        o,c=model();del c["components"][("provider","com.yeelight.yeelib.data.DeviceBrowserProvider")]
        with self.assertRaisesRegex(audit.AuditBlocked,"CLONE_COMPONENT_DRIFT"):
            self.checked(o,c)
    def test_new_unapproved_component_refused(self):
        o,c=model();c["components"][("service","third.party.Unreviewed")]=1
        with self.assertRaisesRegex(audit.AuditBlocked,"CLONE_COMPONENT_DRIFT"):
            self.checked(o,c)
    def test_wrong_original_provider_count_rejected(self):
        o,c=model();del o["components"][("provider","com.yeelight.cherry.OtherOne")]
        with self.assertRaisesRegex(audit.AuditBlocked,"ORIGINAL_COMPONENT_COUNTS"):
            self.checked(o,c)
    def test_not_all_classes_present_in_clone(self):
        o,c=model()
        c["definitions"].pop("Lcom/yeelight/yeelib/data/DeviceBrowserProvider;")
        with self.assertRaisesRegex(audit.AuditBlocked,"STARTUP_CLASS_OR_SUPER_MISSING"):
            self.checked(o,c)
    def test_does_not_accept_more_or_fewer_dex_files(self):
        o,c=model();c["dex_count"]=4
        with self.assertRaisesRegex(audit.AuditBlocked,"DEX_SHAPE"):
            self.checked(o,c)

if __name__=="__main__":unittest.main()
