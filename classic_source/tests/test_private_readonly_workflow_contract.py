"""Public static security contract of owner-gated private read-only workflow."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / ".github/workflows/classic-private-readonly-parity.yml"


def verify_workflow(text):
    data = yaml.safe_load(text)
    # YAML 1.1 parses 'on' as True. Whichever spelling the reader uses, reject
    # all other triggers and any caller-controlled untrusted dispatch inputs.
    assert isinstance(data, dict)
    assert set(data.get("on", data.get(True))) == {"workflow_dispatch"}
    assert data.get("permissions") == {"contents": "read"}
    jobs = data.get("jobs")
    assert isinstance(jobs, dict) and set(jobs) == {"private-readonly-comparison"}
    job = jobs["private-readonly-comparison"]
    condition = job.get("if")
    assert isinstance(condition, str)
    for required in ("github.actor == 'lxsdd'", "github.ref == 'refs/heads/main'",
                     "github.repository == 'lxsdd/yeelight-loft-dj'",
                     "github.event.repository.private == false"):
        assert required in condition
    assert job["runs-on"] == "ubuntu-latest"
    assert job["environment"]["name"] == "classic-private-build"
    assert job["timeout-minutes"] <= 15
    steps = job["steps"]
    assert len(steps) == 6
    assert [s.get("uses") for s in steps if "uses" in s] == [
        "actions/checkout@v7", "actions/setup-python@v6"]
    assert steps[0]["with"]["persist-credentials"] is False
    assert steps[-1]["if"] == "always()"
    assert "rm -rf -- \"$RUNNER_TEMP/classic-original\"" in steps[-1]["run"]
    for token in ("python3 github_hosted/policy.py", "bash github_hosted/vault.sh fetch",
                  "secrets.CLASSIC_VAULT_TOKEN", "secrets.CLASSIC_PIXEL6A_READONLY_RECEIPT",
                  "python3 classic_source/private_ci/readonly_route_comparison.py",
                  "printf '%s'", "set +x"):
        assert token in text
    for forbidden in ("build_private_unsigned", "private_test_sign", "upload-artifact@",
                      "gh release create", "gh release upload", "publish_private_signed",
                      "adb ", "apksigner", "bundletool", "gradle ", "sdkmanager --install",
                      "persist-credentials: true", "pull_request_target:", "pull_request:",
                      "push:", "cache: true"):
        assert forbidden not in text
    assert text.count("workflow_dispatch:") == 1


class ReadonlyWorkflowContract(unittest.TestCase):
    def test_workflow_exact_allowlist_and_zero_write(self):
        verify_workflow(SOURCE.read_text("utf-8"))
        # The extended diagnostic must still call the original pinned receipt/
        # Classpath gate; it never replaces the independent private-APK pin.
        scripts = ROOT / "classic_source/private_ci"
        new = (scripts / "readonly_route_comparison.py").read_text("utf-8")
        old = (scripts / "readonly_vault_compare.py").read_text("utf-8")
        self.assertIn("from private_ci.readonly_vault_compare import diagnose as diagnose_baseline", new)
        self.assertIn("inspect_original_routes(original_path)", new)
        self.assertIn("fingerprint(original_path, ORIGINAL_SHA256)", old)
        self.assertIn('"manifest_context_proves_classloader": False', new)


    def test_fail_on_owner_scope_or_environment_changes(self):
        good = SOURCE.read_text("utf-8")
        for original, bad in (("github.actor == 'lxsdd'", "github.actor == 'anyone'"),
                              ("github.ref == 'refs/heads/main'", "github.ref == 'refs/heads/test'"),
                              ("name: classic-private-build", "name: unprotected"),
                              ("permissions:\n  contents: read", "permissions:\n  contents: write")):
            with self.subTest(original=original), self.assertRaises(AssertionError):
                verify_workflow(good.replace(original,bad))

    def test_fail_on_publish_build_or_missing_cleanup(self):
        good = SOURCE.read_text("utf-8")
        with self.assertRaises(AssertionError):
            verify_workflow(good + "\n# gh release create forbidden\n")
        with self.assertRaises(AssertionError):
            verify_workflow(good.replace("if: always()", "if: success()"))
        with self.assertRaises(AssertionError):
            verify_workflow(good.replace("bash github_hosted/vault.sh fetch", "echo skipped"))
        with self.assertRaises(AssertionError):
            verify_workflow(good.replace("secrets.CLASSIC_PIXEL6A_READONLY_RECEIPT", "github.event.inputs.data"))


if __name__ == "__main__":
    unittest.main()
