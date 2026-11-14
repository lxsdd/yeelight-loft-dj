"""Synthetic-only regression for ART sorted DEX authority changes and Smali."""
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from zipfile import ZipFile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from classic_first import GateFailure
from dex_authority_remap import remap_dex, NEW
from smali_authority_reassembly import rewrite_literals, stage_reassembled_oem
from build_private_unsigned import _art_string_ids_sorted


def dex_fixture(values):
    values = sorted(values)
    data = bytearray(b"\0" * (112 + 4 * len(values)))
    data[0:8] = b"dex\n039\0"
    struct.pack_into("<I", data, 56, len(values))
    struct.pack_into("<I", data, 60, 112)
    for i, value in enumerate(values):
        assert value.isascii() and len(value) < 127
        struct.pack_into("<I", data, 112 + i*4, len(data))
        data.extend(bytes([len(value)]) + value.encode("ascii") + b"\0")
    struct.pack_into("<I", data, 32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


class ReassemblyTests(unittest.TestCase):
    def test_old_inplace_dex_remap_corrupts_art_string_order(self):
        raw = dex_fixture(["alpha", "com.yeelight.cherry.device",
                           "hamster", "zulu"])
        self.assertTrue(_art_string_ids_sorted(raw))
        patched, audit = remap_dex(raw)
        self.assertEqual(audit["changed_string_count"], 1)
        self.assertFalse(_art_string_ids_sorted(patched))
        self.assertTrue(_art_string_ids_sorted(raw))

    def test_whole_ascii_authority_literals_only(self):
        src = ('const-string v0, "com.yeelight.cherry.device"\n'
               'const-string v1, "content://com.yeelight.cherry.device/path"\n'
               'const-string v2, "com.yeelight.cherry.device/other"\n'
               'const-string v3, "com.yeelight.cherry.other"\n'
               'const-string v4, "\\u0063om.yeelight.cherry.device"\n')
        result, found = rewrite_literals(src)
        self.assertIn('"' + NEW + '.device"', result)
        self.assertIn('"content://' + NEW + '.device/path"', result)
        self.assertIn('"com.yeelight.cherry.device/other"', result)
        self.assertIn('"com.yeelight.cherry.other"', result)
        self.assertIn(r'"\u0063om.yeelight.cherry.device"', result)
        self.assertEqual(found, {
            b"com.yeelight.cherry.device",
            b"content://com.yeelight.cherry.device/path"
        })

    def test_exact_staging_rewrites_and_preserves_native_bridge(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source, stage, orig = root/"code", root/"staged", root/"orig.apk"
            (source/"smali").mkdir(parents=True)
            (source/"smali_classes2").mkdir()
            stage.mkdir()
            first = dex_fixture(["alpha", "com.yeelight.cherry.device", "zulu"])
            second = dex_fixture(["alpha", "com.yeelight.cherry.landevice", "zulu"])
            with ZipFile(orig, "w") as z:
                z.writestr("classes.dex", first)
                z.writestr("classes2.dex", second)
            (source/"smali"/"A.smali").write_text(
                'const-string v0, "com.yeelight.cherry.device"\n')
            (source/"smali_classes2"/"B.smali").write_text(
                'const-string v0, "com.yeelight.cherry.landevice"\n')
            (stage/"classes.dex").write_bytes(first)
            (stage/"classes2.dex").write_bytes(second)
            (stage/"classes3.dex").write_bytes(b"native-bridge")
            report = stage_reassembled_oem(orig, source, stage)
            self.assertEqual(report["original_dex_count"], 2)
            self.assertFalse((stage/"classes.dex").exists())
            self.assertFalse((stage/"classes2.dex").exists())
            self.assertEqual((stage/"classes3.dex").read_bytes(), b"native-bridge")
            self.assertIn(NEW+".device", (stage/"smali"/"A.smali").read_text())
            self.assertIn(NEW+".landevice",
                          (stage/"smali_classes2"/"B.smali").read_text())

    def test_missing_authority_literal_fails_without_removing_raw_dex(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source, stage, orig = root/"code", root/"staged", root/"orig.apk"
            (source/"smali").mkdir(parents=True)
            (source/"smali_classes2").mkdir()
            stage.mkdir()
            first = dex_fixture(["alpha", "com.yeelight.cherry.device", "zulu"])
            second = dex_fixture(["alpha", "com.yeelight.cherry.landevice", "zulu"])
            with ZipFile(orig,"w") as z:
                z.writestr("classes.dex",first)
                z.writestr("classes2.dex",second)
            (source/"smali"/"A.smali").write_text('const-string v0, "other"\n')
            (source/"smali_classes2"/"B.smali").write_text(
                'const-string v0, "com.yeelight.cherry.landevice"\n')
            (stage/"classes.dex").write_bytes(first)
            (stage/"classes2.dex").write_bytes(second)
            with self.assertRaisesRegex(GateFailure,"SMALI_REMAP_LITERAL_COVERAGE_INVALID"):
                stage_reassembled_oem(orig,source,stage)
            self.assertTrue((stage/"classes.dex").exists())
            self.assertTrue((stage/"classes2.dex").exists())


if __name__ == "__main__":
    unittest.main()
