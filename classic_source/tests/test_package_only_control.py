"""Public synthetic tests for the private one-field package-only binary ablation.

No OEM APK, credentials, emulator, network access or test-signing.
"""
import json
import os
import hashlib
import contextlib
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "private_ci"))
import private_test_sign as signer
from classic_first import OLD_PACKAGE, NEW_PACKAGE
from apk_readonly_audit import parse_binary_xml
import package_only_manifest as pkg
import private_package_only_control as control

def binary_manifest(*, utf8=True, duplicate=False, shared=False):
    labels = ["manifest", "package", OLD_PACKAGE,
              OLD_PACKAGE + ".device", "originalName"]
    if duplicate:
        labels.append(OLD_PACKAGE)
    values = []
    offsets = []
    for label in labels:
        offsets.append(sum(map(len, values)))
        if utf8:
            raw = label.encode("utf-8")
            assert len(raw) < 128
            values.append(bytes([len(label), len(raw)]) + raw + b"\0")
        else:
            raw = label.encode("utf-16-le")
            values.append(struct.pack("<H", len(label)) + raw + b"\0\0")
    count = len(labels)
    raw = b"".join(values)
    start = 28 + count * 4
    size = start + len(raw)
    pool = (struct.pack("<HHIIIIII", 1, 28, size, count, 0,
                        0x100 if utf8 else 0, start, 0)
            + struct.pack("<" + "I" * count, *offsets) + raw)
    number = 2 if shared else 1
    node = bytearray(36 + number * 20)
    struct.pack_into("<HHI", node, 0, 0x0102, 36, len(node))
    struct.pack_into("<I", node, 20, 0)
    struct.pack_into("<HHH", node, 24, 20, 20, number)
    for index in range(number):
        name = 1 if index == 0 else 4
        struct.pack_into("<IIIHBBI", node, 36 + index * 20,
                         0xffffffff, name, 2, 8, 0, 3, 2)
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(node)) + pool + node

class PackageOnlyTests(unittest.TestCase):
    def test_both_android_xml_pool_encodings_change_exactly_one_attribute(self):
        for utf8 in (True, False):
            with self.subTest(utf8=utf8):
                source = binary_manifest(utf8=utf8)
                result = pkg.replace_package_only(source)
                original = parse_binary_xml(source)
                rewritten = parse_binary_xml(result)
                self.assertEqual(original["elements"][0]["attributes"]["package"], OLD_PACKAGE)
                self.assertEqual(rewritten["elements"][0]["attributes"]["package"], NEW_PACKAGE)
                self.assertGreater(len(result), len(source))
                self.assertEqual(rewritten["string_count"], original["string_count"] + 1)
                # Unrelated full-length original authority is preserved as bytes.
                self.assertIn(OLD_PACKAGE.encode("utf-8" if utf8 else "utf-16-le")
                              + ".device".encode("utf-8" if utf8 else "utf-16-le"), result)

    def test_duplicate_pool_literal_not_referenced_by_root_is_safe(self):
        # Apktool may duplicate a literal in the pool; only the referenced
        # manifest package entry is ever modified, never every text match.
        payload = binary_manifest(duplicate=True)
        changed = pkg.replace_package_only(payload)
        self.assertEqual(parse_binary_xml(changed)["elements"][0]["attributes"]["package"], NEW_PACKAGE)
        self.assertIn(OLD_PACKAGE.encode("utf-8"), changed)
        self.assertNotEqual(payload, changed)

    def test_shared_pool_reference_changes_only_manifest_package(self):
        for utf8 in (True, False):
            with self.subTest(utf8=utf8):
                payload = binary_manifest(shared=True, utf8=utf8)
                before = parse_binary_xml(payload)
                after_data = pkg.replace_package_only(payload)
                after = parse_binary_xml(after_data)
                self.assertEqual(after["elements"][0]["attributes"]["package"], NEW_PACKAGE)
                self.assertEqual(after["elements"][0]["attributes"]["originalName"], OLD_PACKAGE)
                self.assertEqual(before["elements"][0]["attributes"]["originalName"], OLD_PACKAGE)
                self.assertEqual(after["string_count"], before["string_count"] + 1)
                self.assertEqual(after_data.count(OLD_PACKAGE.encode(
                    "utf-8" if utf8 else "utf-16-le")), payload.count(
                    OLD_PACKAGE.encode("utf-8" if utf8 else "utf-16-le")))

    def test_divergent_typed_index_fail_closed(self):
        payload = bytearray(binary_manifest())
        # Manifest's raw string index 2 and typed index 2 must not silently
        # disagree about the old package identity.
        pos = len(payload) - (36 + 20) + 36
        struct.pack_into("<I", payload, pos + 16, 3)
        with self.assertRaisesRegex(pkg.PackageOnlyBlocked, "PACKAGE_ONLY_DUAL_STRING_REFERENCE"):
            pkg.replace_package_only(bytes(payload))

    def test_append_string_does_not_shift_original_string_indices(self):
        payload = binary_manifest(shared=True)
        after = pkg.replace_package_only(payload)
        before_xml = parse_binary_xml(payload)
        after_xml = parse_binary_xml(after)
        self.assertEqual(before_xml["namespaces"], after_xml["namespaces"])
        self.assertEqual(before_xml["elements"][0]["tag"], after_xml["elements"][0]["tag"])
        self.assertEqual(after_xml["elements"][0]["attributes"]["originalName"], OLD_PACKAGE)
        self.assertEqual(struct.unpack_from("<I", after, 4)[0], len(after))

    def test_no_package_string_pool_entry_is_mutated_when_alias_is_shared(self):
        payload = binary_manifest(shared=True, duplicate=True)
        changed = pkg.replace_package_only(payload)
        original = parse_binary_xml(payload)
        revised = parse_binary_xml(changed)
        self.assertEqual(original["string_count"] + 1, revised["string_count"])
        self.assertEqual(revised["elements"][0]["attributes"]["package"], NEW_PACKAGE)
        self.assertEqual(revised["elements"][0]["attributes"]["originalName"], OLD_PACKAGE)

    def test_garbage_xml_and_wrong_package_refused(self):
        for value in (b"", b"<manifest package='com.yeelight.cherry'/>",
                      b"\0" * 2048):
            with self.assertRaises((pkg.PackageOnlyBlocked, ValueError)):
                pkg.replace_package_only(value)
        with self.assertRaisesRegex(pkg.PackageOnlyBlocked, "PACKAGE_ONLY_ORIGINAL_PACKAGE_INVALID"):
            pkg.replace_package_only(pkg.replace_package_only(binary_manifest()))

    def test_scope_refuses_public_ci_and_misnamed_output(self):
        with tempfile.TemporaryDirectory() as d:
            temp = Path(d)
            with patch.dict(os.environ, {"RUNNER_TEMP": str(temp)}, clear=True):
                with self.assertRaisesRegex(pkg.PackageOnlyBlocked, "PACKAGE_ONLY_OWNER_OFFLINE_SCOPE_REQUIRED"):
                    pkg.scope(temp / pkg.SRC, temp / pkg.DST)
                with self.assertRaisesRegex(signer.Refused, "PACKAGE_ONLY_OWNER_OFFLINE_SCOPE_REQUIRED"):
                    signer.sign(temp / pkg.SRC, temp / "source.json",
                                temp / "signed.apk", temp,
                                package_only_control=True)
                with self.assertRaises(control.BaselineBlocked):
                    control.scope(temp / "original.apk", temp / "test-signed.apk",
                                  serial="emulator-5554", approved=True, env={})
        with self.assertRaisesRegex(signer.Refused, "IDENTITY_ABLATION_BAD_MODE"):
            signer.sign(Path("a"),Path("b"),Path("c"),Path("d"),
                        identity_control=True, package_only_control=True)

    def test_exact_identity_unsigned_preflight_and_signature_receipt_schema(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            out = root / "private-output"
            out.mkdir()
            src = out / pkg.SRC
            src.write_bytes(b"fake-private-binary-for-contract-test")
            preflight = src.with_suffix(src.suffix + ".preflight.json")
            first = {
                "status": "UNSIGNED_PRIVATE_APK_STRUCTURAL_PREFLIGHT_PASS_NOT_INSTALLED",
                "package": OLD_PACKAGE,
                "identity_control_offline_only": True,
                "dex_reassembly_mode": "raw",
                "original_oem_dex_intentionally_changed": False,
                "controlled_authority_string_remaps": {"classes.dex": 0, "classes2.dex": 0},
                "apk_sha256": hashlib.sha256(src.read_bytes()).hexdigest(),
            }
            preflight.write_text(json.dumps(first), encoding="utf-8")
            receipt = out / (pkg.IDENTITY_SIGNED + ".test-signing.json")
            second = {
                "identity_control_offline_only": True,
                "package": OLD_PACKAGE,
                "signed": True,
                "unsigned_sha256": first["apk_sha256"],
                "source_build_report_sha256": hashlib.sha256(preflight.read_bytes()).hexdigest(),
            }
            receipt.write_text(json.dumps(second), encoding="utf-8")
            env = {
                "RUNNER_TEMP": str(root),
                "GITHUB_REPOSITORY": "lxsdd/yeelight-loft-dj",
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_ACTOR": "lxsdd",
                "GITHUB_REF": "refs/heads/main",
                "RUNNER_ENVIRONMENT": "github-hosted",
            }
            with patch.dict(os.environ, env, clear=True):
                self.assertEqual(pkg.scope(src, out / pkg.DST)["package"], OLD_PACKAGE)
                first["controlled_authority_string_remaps"]["classes2.dex"] = 1
                preflight.write_text(json.dumps(first), encoding="utf-8")
                with self.assertRaisesRegex(pkg.PackageOnlyBlocked,
                                            "PACKAGE_ONLY_IDENTITY_INPUT_NOT_QUALIFIED"):
                    pkg.scope(src, out / pkg.DST)
                with patch.dict(os.environ, {"GITHUB_REF":"refs/heads/untrusted"}):
                    with self.assertRaisesRegex(pkg.PackageOnlyBlocked,
                                                "PACKAGE_ONLY_OWNER_OFFLINE_SCOPE_REQUIRED"):
                        pkg.scope(src, out / pkg.DST)

    def test_cli_reports_allowlisted_cause_only_not_private_values(self):
        with patch("sys.argv", ["tool","--source","dummy","--output","dummy2"]):
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr), patch.object(
                    pkg, "build", side_effect=pkg.PackageOnlyBlocked(
                        "PACKAGE_ONLY_ROOT_ATTR_NOT_UNIQUE")):
                self.assertEqual(pkg.main(), 2)
            self.assertEqual(stderr.getvalue().strip(),
                             "PRIVATE_PACKAGE_ONLY_CONTROL_BLOCKED_PACKAGE_ONLY_ROOT_ATTR_NOT_UNIQUE")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr), patch.object(
                    pkg, "build", side_effect=pkg.PackageOnlyBlocked(
                        "OEM-secret-private-component")):
                self.assertEqual(pkg.main(), 2)
            self.assertNotIn("OEM-secret-private-component", stderr.getvalue())
            self.assertIn("PRIVATE_PACKAGE_ONLY_CONTROL_BLOCKED_UNCLASSIFIED", stderr.getvalue())

    def test_one_shot_result_never_qualifies_product(self):
        launch = ("Status: ok\nActivity: io.lxsdd.yeelightdj/"
                  "com.yeelight.cherry.Main\nComplete\n")
        none = {"target_crash": False, "unattributed_fatal": False,
                "exception_category": "NONE"}
        npe = dict(none, target_crash=True, exception_category="NULL_DEREFERENCE")
        with patch.object(control, "_classic_foreground", return_value=(True,"SAME_CLONE_ORIGINAL_ACTIVITY")):
            good = control.classify_result(0, launch, "synthetic", none)
            bad = control.classify_result(0, launch, "synthetic", npe)
            foreign = control.classify_result(0, launch.replace("io.lxsdd.yeelightdj", "foreign.app"),
                                              "synthetic", none)
        self.assertEqual(good["observed_start_state"], "RESUMED_NO_TARGET_FATAL")
        self.assertEqual(bad["observed_start_state"], "NULL_DEREFERENCE")
        self.assertEqual(foreign["observed_start_state"], "NOT_RESUMED")
        for result in (good,bad,foreign):
            self.assertFalse(result["classic_functionally_accepted"])
            self.assertFalse(result["native_loft_dj_functionally_accepted"])
            self.assertFalse(result["raw_private_payload_published"])

    def test_shell_sequence_stays_private_and_failed_clone_remains_failure(self):
        shell = (ROOT / "private_ci" / "run_android17_emulator.sh").read_text("utf-8")
        one = shell.index("android17_start_smoke.py")
        stock = shell.index("private_original_baseline.py")
        identity = shell.index("private_identity_control.py")
        package = shell.index("private_package_only_control.py")
        self.assertLess(one, stock)
        self.assertLess(stock, identity)
        self.assertLess(identity, package)
        self.assertIn("private_ci/package_only_manifest.py", shell)
        self.assertIn("--package-only-control", shell)
        self.assertTrue(shell.rstrip().endswith("fail\nfi"))
        for bad in ("upload-artifact", "gh release", "adb -d ", "adb -s RF"):
            self.assertNotIn(bad, shell)

if __name__ == "__main__":
    unittest.main()
