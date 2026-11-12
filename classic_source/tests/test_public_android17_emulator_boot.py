"""No emulator invoked by unit tests. Public PR CI performs the real boot.

A PR event's GITHUB_SHA may be a synthetic merge SHA. The workflow must
checkout and prove its actual head, or a success cannot qualify that candidate.
"""
from pathlib import Path
import subprocess
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
BASH = ROOT / "classic_source/private_ci/run_public_android17_emulator_boot.sh"
WORKFLOW = ROOT / ".github/workflows/classic-public-emulator-boot.yml"
KVM = ROOT / "classic_source/private_ci/hosted_kvm_scope.sh"

REQUIRED_PATHS = (
    'classic_source/**',
    '.github/workflows/classic-*.yml',
    'github_hosted/private_candidate_gate.py',
    'github_hosted/tests/test_private_candidate_gate.py',
    '!classic_source/*.md',
    '!classic_source/**/*.md',
)


def verify_exact_public_candidate_gate(source: str):
    workflow = yaml.safe_load(source)
    triggers = workflow.get('on', workflow.get(True))
    assert set(triggers) == {'pull_request'}, 'no push/manual/privileged automatic dispatch'
    assert triggers['pull_request']['branches'] == ['main']
    paths = triggers['pull_request']['paths']
    assert paths == list(REQUIRED_PATHS), 'all native Classic source, not only selected subtrees, must rerun public smoke'
    assert workflow['permissions'] == {'contents': 'read'}
    assert workflow['concurrency']['cancel-in-progress'] is True
    assert set(workflow['jobs']) == {'android17-no-oem-boot'}
    job = workflow['jobs']['android17-no-oem-boot']
    assert job['runs-on'] == 'ubuntu-latest' and job['timeout-minutes'] <= 18
    predicate = job['if']
    for part in ("github.repository == 'lxsdd/yeelight-loft-dj'",
                 "github.actor == 'lxsdd'",
                 "github.event_name == 'pull_request'",
                 "github.event.pull_request.head.repo.full_name == 'lxsdd/yeelight-loft-dj'"):
        assert part in predicate, 'PR must be same-repo, owner-triggered and non-fork'
    assert len(job['steps']) == 4
    checkout, pin, setup, boot = job['steps']
    assert checkout['uses'] == 'actions/checkout@v7'
    assert checkout['with']['persist-credentials'] is False
    assert checkout['with']['ref'] == '${{ github.event.pull_request.head.sha }}', 'must not checkout PR merge ref'
    assert pin['shell'] == 'bash'
    assert pin['env']['EXPECTED_SHA'] == '${{ github.event.pull_request.head.sha }}'
    for fragment in ('set -euo pipefail', '^[0-9a-f]{40}$',
                     'test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"',
                     'CLASSIC_PUBLIC_QA_CANDIDATE_SHA'):
        assert fragment in pin['run'], 'must check and report exact candidate SHA'
    assert setup['uses'] == 'actions/setup-python@v6'
    assert boot['name'] == 'Cold boot exact API37 emulator without OEM, license assent, artifacts or devices'
    assert boot['run'] == 'bash classic_source/private_ci/run_public_android17_emulator_boot.sh --no-oem'
    for forbidden in ('secrets.', 'workflow_dispatch:', 'push:', 'upload-artifact',
                      'gh release', 'contents: write', 'runs-on: self-hosted',
                      'accept-android-sdk-licenses', 'pull_request_target:'):
        assert forbidden not in source, 'no private scope in public PR QA'


class PublicAndroid17BootContracts(unittest.TestCase):
    def test_public_workflow_pins_qualifying_head_and_covers_native_classic(self):
        verify_exact_public_candidate_gate(WORKFLOW.read_text('utf-8'))

    def test_negative_workflow_regressions_fail_closed(self):
        text = WORKFLOW.read_text('utf-8')
        alterations = (
            ('ref: ${{ github.event.pull_request.head.sha }}', 'ref: ${{ github.sha }}'),
            ('git rev-parse HEAD', 'git rev-parse HEAD~1'),
            ("github.actor == 'lxsdd'", "github.actor != 'nobody'"),
            ("github.event.pull_request.head.repo.full_name == 'lxsdd/yeelight-loft-dj'", 'true'),
            ('classic_source/**', 'classic_source/tests/**'),
            ('.github/workflows/classic-*.yml', '.github/workflows/classic-public-*.yml'),
            ('github_hosted/private_candidate_gate.py', 'github_hosted/vault.sh'),
            ('github_hosted/tests/test_private_candidate_gate.py', 'github_hosted/tests/unused.py'),
            ('!classic_source/*.md', 'classic_source/*.md'),
            ('persist-credentials: false', 'persist-credentials: true'),
            ('contents: read', 'contents: write'),
            ('pull_request:', 'pull_request_target:'),
            ('- name: Cold boot exact API37 emulator', '- name: Fake boot'),
        )
        for original, replacement in alterations:
            with self.subTest(original=original):
                self.assertIn(original, text)
                with self.assertRaises(AssertionError):
                    verify_exact_public_candidate_gate(text.replace(original, replacement))

    def test_public_runner_keeps_no_apk_no_new_licenses_and_fixed_serial(self):
        script = BASH.read_text(encoding='utf-8')
        for required in ('source classic_source/private_ci/hosted_kvm_scope.sh',
                         'sdkmanager" --install --channel=0',
                         'select_android17_image.py', '"$EMU" -accel-check',
                         'kvm_scope_acquire', 'kvm_scope_restore',
                         '-accel on -gpu swangle_indirect -feature -Vulkan',
                         'emulator_boot_diagnosis.py', 'ANDROID17_PUBLIC_EMULATOR_COLD_BOOT_PROVEN_NO_OEM',
                         'ANDROID17_PUBLIC_GLOBAL_RESUME_GRAMMAR_PROVEN_NO_OEM',
                         'dumpsys activity activities', 'inspect_top_resumed',
                         'trap cleanup EXIT', 'getprop ro.build.version.sdk'):
            self.assertIn(required, script)
        self.assertLess(script.index('dumpsys activity activities'),
                        script.index('ANDROID17_PUBLIC_GLOBAL_RESUME_GRAMMAR_PROVEN_NO_OEM'))
        self.assertLess(script.index('ANDROID17_PUBLIC_GLOBAL_RESUME_GRAMMAR_PROVEN_NO_OEM'),
                        script.index('ANDROID17_PUBLIC_EMULATOR_COLD_BOOT_PROVEN_NO_OEM'))
        self.assertIn('state["category"] != "FOREGROUND_OTHER_PACKAGE"', script)
        self.assertIn('ACTIVITY_RESUME_UNVERIFIED', script)
        for forbidden in ('apksigner', 'vault.sh', 'CLASSIC_VAULT_TOKEN', 'sign ',
                          'gh release', 'upload-artifact', 'chmod 666', 'chmod 777',
                          'sudo ', 'adb -d ', 'adb install', 'sdkmanager --licenses',
                          'yes |'):
            self.assertNotIn(forbidden, script)
        result = subprocess.run(['bash', '-n', str(BASH)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_one_runner_user_kvm_permission_scope_restored_no_global_mode(self):
        helper = KVM.read_text(encoding='utf-8')
        self.assertIn('kvm_scope_acquire()', helper)
        self.assertIn('kvm_scope_restore()', helper)
        self.assertIn('KVM_SCOPE_OLD_OWNER', helper)
        self.assertIn('KVM_SCOPE_OLD_MODE', helper)
        self.assertIn('sudo -n chown "$(id -u):$(id -g)" /dev/kvm', helper)
        self.assertIn('sudo -n chmod 0600 /dev/kvm', helper)
        self.assertIn('sudo -n chown "$KVM_SCOPE_OLD_OWNER" /dev/kvm', helper)
        self.assertIn('sudo -n chmod "$KVM_SCOPE_OLD_MODE" /dev/kvm', helper)
        for dangerous in ('0666', '0777', 'udevadm', 'modprobe', 'apt-get',
                          'setfacl -m g::rwx', 'chmod 666', 'chmod 777'):
            self.assertNotIn(dangerous, helper)
        self.assertIn('kvm_scope_restore', BASH.read_text())
        checked = subprocess.run(['bash', '-n', str(KVM)], capture_output=True, text=True)
        self.assertEqual(checked.returncode, 0, checked.stderr)

    def test_rejects_local_execution_before_any_emulator_or_download(self):
        result = subprocess.run(['bash', str(BASH), '--no-oem'],
                                capture_output=True, text=True, timeout=10,
                                cwd=ROOT, env={'PATH': '/usr/bin:/bin'})
        self.assertEqual(result.returncode, 2)
        self.assertIn('ANDROID17_PUBLIC_EMULATOR_BLOCKED_INVALID_CONTEXT', result.stderr)
        self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()
