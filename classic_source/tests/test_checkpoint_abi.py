"""Synthetic positive/negative Classfile ABI gate regressions; zero OEM inputs."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from zipfile import ZipFile

SRC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SRC / "bridge"))
from checkpoint_abi import AbiGateError, inspect_class, verify_bridge_jar, TARGET


def fixture(owner=b"kotlin/Result", field=b"Companion", kind=9):
    """Construct a minimal JVM constant pool with a symbolic fieldref."""
    entries = [None, (1, owner), (7, 1), (1, field),
               (1, b"Ljava/lang/Object;"), (12, 3, 4),
               (kind, 2, 5), (1, b"configurePulse")]
    data = bytearray(b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(entries)))
    for entry in entries[1:]:
        tag, *values = entry
        data.append(tag)
        if tag == 1:
            item = values[0]
            data.extend(struct.pack(">H", len(item)) + item)
        elif tag == 7:
            data.extend(struct.pack(">H", values[0]))
        else:
            data.extend(struct.pack(">HH", *values))
    return bytes(data)


class CheckpointAbiTests(unittest.TestCase):
    def test_old_kotlin_result_companion_field_rejected(self):
        with self.assertRaisesRegex(AbiGateError, "KOTLIN_RESULT_COMPANION_ABI_BLOCKED"):
            inspect_class(fixture())

    def test_different_kotlin_field_allowed(self):
        result = inspect_class(fixture(field=b"OtherField"))
        self.assertEqual(result["field_ref_restriction"], "KOTLIN_RESULT_COMPANION_ABSENT")
        self.assertFalse(result["runtime_qualified"])

    def test_other_owner_companion_allowed(self):
        self.assertFalse(inspect_class(fixture(owner=b"other/OwnedResult"))["runtime_qualified"])

    def test_similar_methodref_not_confused_with_fieldref(self):
        self.assertFalse(inspect_class(fixture(kind=10))["runtime_qualified"])

    def test_invalid_magic_rejected(self):
        with self.assertRaisesRegex(AbiGateError, "MAGIC_INVALID"):
            inspect_class(b"not!" + fixture()[4:])

    def test_truncated_constant_pool_rejected(self):
        with self.assertRaisesRegex(AbiGateError, "CP_TRUNCATED"):
            inspect_class(fixture()[:-2])

    def test_jar_positive_and_negative(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "bridge.jar"
            with ZipFile(jar, "w") as out:
                out.writestr(TARGET, fixture(field=b"OwnField"))
            self.assertFalse(verify_bridge_jar(jar)["runtime_qualified"])
            with ZipFile(jar, "w") as out:
                out.writestr(TARGET, fixture())
            with self.assertRaisesRegex(AbiGateError, "KOTLIN_RESULT_COMPANION_ABI_BLOCKED"):
                verify_bridge_jar(jar)

    def test_missing_target_class_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "bridge.jar"
            with ZipFile(jar, "w") as out:
                out.writestr("other.class", fixture())
            with self.assertRaisesRegex(AbiGateError, "CLASS_NOT_FOUND"):
                verify_bridge_jar(jar)

    def test_source_no_inline_result_reference(self):
        runtime = (SRC / "bridge/kotlin/io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.kt").read_text("utf-8")
        method = runtime.split("override fun configurePulse(", 1)[1].split("override fun start(", 1)[0]
        self.assertNotIn("runCatching {", method)
        self.assertIn("catch (invalid: IllegalArgumentException)", method)
        self.assertIn("ClassicPulseScene.Options(rgb, beatSync", method)

    def test_actual_owner_build_uses_compiled_abi_gate(self):
        build = (SRC / "bridge/build_bridge.py").read_text("utf-8")
        self.assertIn("verify_bridge_jar(", build)
        self.assertIn("CLASSIC_CHECKPOINT_RESULT_ABI_UNVERIFIED", build)

    def test_android_ci_uses_actual_compiled_bridge_jar(self):
        workflow = (SRC.parent / ".github/workflows/android.yml").read_text("utf-8")
        self.assertIn("python3 classic_source/bridge/checkpoint_abi.py", workflow)
        self.assertIn("classic_source/private_gradle_bridge/build/dex-input/classic-bridge.jar", workflow)

if __name__ == "__main__":
    unittest.main()
