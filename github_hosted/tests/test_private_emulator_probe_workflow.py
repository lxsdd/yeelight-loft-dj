"""The probe must be a guarded, owner-only runtime experiment, never a release."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / '.github/workflows/classic-private-emulator-probe.yml'


def validate_contract(source):
    w = yaml.load(source, Loader=yaml.BaseLoader)
    assert list(w['on']) == ['workflow_dispatch']
    assert w['permissions'] == {'contents': 'read'}
    assert w['concurrency']['cancel-in-progress'] == 'false'
    assert set(w['jobs']) == {'guarded-private-emulator-only'}
    job = w['jobs']['guarded-private-emulator-only']
    assert job['runs-on'] == 'ubuntu-latest'
    assert int(job['timeout-minutes']) <= 35
    assert job['environment'] == {'name': 'classic-private-build', 'deployment': 'false'}
    for expr in ("github.actor == 'lxsdd'", "github.ref == 'refs/heads/main'",
                 "github.repository == 'lxsdd/yeelight-loft-dj'",
                 'github.event.repository.private == false'):
        assert expr in job['if']
    steps = job['steps']
    joined = '\n'.join(x.get('run', '') for x in steps)
    checkout = next(x for x in steps if x.get('uses', '').startswith('actions/checkout'))
    assert checkout['with']['persist-credentials'] == 'false'
    assert checkout['with']['ref'] == '${{ github.sha }}'
    assert 'test "$(git rev-parse HEAD)" = "$GITHUB_SHA"' in joined
    assert 'python3 github_hosted/policy.py' in joined
    assert 'classic_source/private_emulator_probe.py' in joined
    assert 'classic_source/run_real_oem_tests.py' in joined
    assert 'classic_source/build_private_unsigned.py' in joined
    assert 'classic_source/private_test_sign.py' in joined
    assert 'classic_source/private_payload_parity.py' in joined
    assert 'classic_source/private_ci/run_android17_emulator.sh' in joined
    assert 'PRIVATE_CLASSIC_EMULATOR_DIAGNOSTIC_PASSED_BASELINE_CLASSPATH_STILL_UNVERIFIED' in source
    assert 'if: always()' in source
    assert 'rm -rf -- "$RUNNER_TEMP/private-original"' in joined
    forbidden = ('pull_request_target:', 'pull_request:', '  push:',
                 'gh release', 'publish_private_signed.sh', 'vault.sh publish',
                 'actions/upload-artifact', 'actions/cache', 'sdkmanager --licenses',
                 'yes |', 'chmod 666', 'chmod 777', 'workflow_run:')
    for term in forbidden:
        assert term not in source, term
    assert 'cache-disabled: true' in source
    secret = [x for x in steps if '${{ secrets.CLASSIC_VAULT_TOKEN }}' in str(x)]
    assert len(secret) == 1
    assert secret[0]['env'] == {'GH_TOKEN': '${{ secrets.CLASSIC_VAULT_TOKEN }}'}
    assert 'github_hosted/vault.sh fetch' in secret[0]['run']
    assert all('GH_TOKEN' not in str(x) and 'CLASSIC_VAULT_TOKEN' not in str(x)
               for x in steps if x is not secret[0])
    names = [x['name'] for x in steps]
    def before(a, b):
        assert names.index(a) < names.index(b), (a, b)
    before('Verify pinned source SHA and protected manual dispatch identity',
           'Fetch only pinned private original (no persistent cache)')
    before('Require exact old OEM gaps for diagnostic only, never qualify them',
           'Assemble quarantined unsigned Classic candidate with original DEX parity')
    before('Reject any candidate OEM graph drift or new classpath defect',
           'Ephemeral private TEST sign only for offline emulator installation')
    before('Independent unsigned/signed payload parity and final candidate graph probe',
           'Real offline Android17 emulator install and native in-Classic DJ UI diagnostic')
    return True


class PrivateEmulatorOnlyWorkflow(unittest.TestCase):
    def test_owner_manual_no_artifact_policy_and_safety_order(self):
        self.assertTrue(validate_contract(PATH.read_text('utf-8')))

    def test_negative_mutations_are_rejected(self):
        src = PATH.read_text('utf-8')
        changes = (
            ("github.actor == 'lxsdd'", "github.actor != 'lxsdd'"),
            ("github.ref == 'refs/heads/main'", "github.ref != 'refs/heads/main'"),
            ('persist-credentials: false', 'persist-credentials: true'),
            ('ref: ${{ github.sha }}', 'ref: main'),
            ('  workflow_dispatch:', '  pull_request:'),
            ('cache-disabled: true', 'cache-disabled: false'),
            ('  contents: read', '  contents: write'),
            ('classic-private-build', 'unprotected'),
            ('private_emulator_probe.py', 'private_startup_audit.py'),
            ('private_payload_parity.py', 'fake_payload_parity.py'),
            ('run_android17_emulator.sh', 'fake_android17_smoke.sh'),
            ('private_test_sign.py', 'fake_sign.py'),
            ('if: always()', 'if: success()'),
        )
        for before, after in changes:
            with self.subTest(before=before):
                self.assertIn(before, src)
                with self.assertRaises((AssertionError, KeyError)):
                    validate_contract(src.replace(before, after))


if __name__ == '__main__':
    unittest.main()
