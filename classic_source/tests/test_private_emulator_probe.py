"""Synthetic contract tests only; never download the OEM APK or launch devices."""
from pathlib import Path
from copy import deepcopy
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import private_emulator_probe as probe
from test_private_startup_audit import model


class EmulatorOnlyBaseline(unittest.TestCase):
    def setUp(self):
        self.o, self.c = model()
        self.o['declared_shared_libraries'] = 1
        self.c['declared_shared_libraries'] = 1
        # Three manifest-declared original Activities are not defined in any
        # base DEX. This is a defect, NOT a disabled component or waiver.
        activity = sorted(k for k in self.o['definitions'] if '/Activity' in k)
        self.missing = activity[:3]
        for desc in self.missing:
            del self.o['definitions'][desc]
            del self.c['definitions'][desc]
        # Six non-startup direct parents still need a real ClassLoader proof.
        self.dormant = []
        for i in range(6):
            owner = f'Lsynthetic/dormant/Owner{i};'
            parent = f'Lsynthetic/missing/Parent{i};'
            self.o['definitions'][owner] = [parent]
            self.c['definitions'][owner] = [parent]
            self.dormant.append((owner, parent))

    def test_broken_original_is_probe_only_never_published(self):
        result = probe.baseline_contract(self.o)
        self.assertEqual(result['missing_activity_count'], 3)
        self.assertEqual(result['missing_parent_edge_count'], 6)
        self.assertEqual(result['classloader'], 'UNVERIFIED')
        self.assertEqual(result['runtime'], 'NOT_TESTED')
        self.assertEqual(result['missing_classes_waived'], 0)
        self.assertIs(result['publishable'], False)

    def test_byte_equivalent_oem_graph_and_new_bridge_can_be_probed(self):
        result = probe.pair_contract(self.o, self.c)
        self.assertEqual(result['status'], 'CANDIDATE_STATIC_PARITY_PROBE_ONLY_NOT_PRODUCT_QUALIFIED')
        self.assertIs(result['publishable'], False)
        self.assertEqual(result['candidate_added_bridge_entries'], 5)
        self.assertIn(probe.audit.NATIVE_MUSIC_PROVIDER, probe.audit.ADDED)

    def test_original_gap_count_changes_fail_in_both_directions(self):
        for count in (2, 4):
            with self.subTest(count=count):
                bad, _ = model()
                bad['declared_shared_libraries'] = 1
                activity = sorted(k for k in bad['definitions'] if '/Activity' in k)
                for desc in activity[:count]:
                    del bad['definitions'][desc]
                for i in range(6):
                    bad['definitions'][f'Lsynthetic/dormant/Owner{i};'] = [f'Lsynthetic/missing/Parent{i};']
                with self.assertRaisesRegex(probe.ProbeBlocked, 'ORIGINAL_REFERENCE_GAPS_DRIFT'):
                    probe.baseline_contract(bad)

    def test_missing_original_parent_count_change_fails(self):
        del self.o['definitions'][self.dormant[0][0]]
        with self.assertRaisesRegex(probe.ProbeBlocked, 'ORIGINAL_PARENT_GAPS_DRIFT'):
            probe.baseline_contract(self.o)

    def test_new_parent_gap_in_original_startup_fails(self):
        # Introduce a new parent gap in a manifest entry, but keep total=6 by
        # removing one dormant gap: counts ALONE must never grant a probe.
        self.o['definitions'][self.dormant[0][0]] = ['Ljava/lang/Object;']
        app = 'Lcom/yeelight/cherry/App;'
        self.o['definitions'][app] = ['Lsynthetic/unbundled/AppParent;']
        with self.assertRaisesRegex(probe.ProbeBlocked, 'ORIGINAL_STARTUP_PARENT_GAP'):
            probe.baseline_contract(self.o)

    def test_missing_original_application_or_provider_fails(self):
        for needle in ('Lcom/yeelight/cherry/App;', 'Lcom/yeelight/cherry/OtherOne;'):
            with self.subTest(needle=needle):
                bad, _ = model()
                bad['declared_shared_libraries'] = 1
                bad['definitions'].pop(needle)
                with self.assertRaisesRegex(probe.ProbeBlocked, 'ORIGINAL_REFERENCE_GAPS_DRIFT'):
                    probe.baseline_contract(bad)

    def test_native_music_provider_exact_and_negative_drift(self):
        provider = probe.audit.NATIVE_MUSIC_PROVIDER
        self.assertEqual(provider[0], 'provider')
        self.assertEqual(probe.audit.ADDED.count(provider), 1)
        self.assertEqual(self.c['components'][provider], 1)
        desc = 'L' + provider[1].replace('.', '/') + ';'
        self.assertIn(desc, self.c['entrypoints'])
        self.assertIn(desc, self.c['definitions'])
        self.assertEqual(self.c['manifest_references'][('provider', desc)], 1)
        variants = (
            ('missing-provider', lambda c: c['components'].pop(provider),
             'CANDIDATE_COMPONENT_DRIFT'),
            ('foreign-provider', lambda c: c['components'].__setitem__(
                ('provider', 'synthetic.Unapproved'), 1), 'CANDIDATE_COMPONENT_DRIFT'),
            ('missing-definition', lambda c: c['definitions'].pop(desc),
             'CANDIDATE_BRIDGE_MISSING'),
            ('wrong-role', lambda c: c['manifest_references'].__setitem__(
                ('provider', desc), 0), 'CANDIDATE_MANIFEST_ROLES_DRIFT'),
            ('missing-parent', lambda c: c['definitions'].__setitem__(
                desc, ['Lsynthetic/missing/NewParent;']), 'CANDIDATE_NEW_CLASS_PARENT_UNVERIFIED'),
        )
        for label, mutate, reason in variants:
            with self.subTest(label=label):
                modified = deepcopy(self.c)
                mutate(modified)
                with self.assertRaisesRegex(probe.ProbeBlocked, reason):
                    probe.pair_contract(self.o, modified)

    def test_candidate_manifest_component_or_role_drifts_fail(self):
        self.c['components'][('service', 'unapproved.Service')] = 1
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_COMPONENT_DRIFT'):
            probe.pair_contract(self.o, self.c)

    def test_candidate_manifest_alias_or_reference_drift_fails(self):
        self.c['manifest_references'][('receiver', 'Lsynthetic/Unexpected;')] = 1
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_MANIFEST_ROLES_DRIFT'):
            probe.pair_contract(self.o, self.c)

    def test_oem_classgraph_modification_fails(self):
        self.c['definitions'][self.dormant[0][0]] = ['Ljava/lang/Object;']
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_OEM_GRAPH_DRIFT'):
            probe.pair_contract(self.o, self.c)

    def test_bridge_missing_fails(self):
        added = 'L' + probe.audit.ADDED[0][1].replace('.', '/') + ';'
        del self.c['definitions'][added]
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_BRIDGE_MISSING'):
            probe.pair_contract(self.o, self.c)

    def test_bridge_new_missing_parent_fails(self):
        added = 'L' + probe.audit.ADDED[0][1].replace('.', '/') + ';'
        self.c['definitions'][added] = ['Lsynthetic/unsafe/NewParent;']
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_NEW_CLASS_PARENT_UNVERIFIED'):
            probe.pair_contract(self.o, self.c)

    def test_new_dormant_class_missing_parent_fails(self):
        self.c['definitions']['Lsynthetic/new/Dormant;'] = ['Lsynthetic/unavailable/Missing;']
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_NEW_CLASS_PARENT_UNVERIFIED'):
            probe.pair_contract(self.o, self.c)

    def test_shared_library_drift_fails(self):
        self.c['declared_shared_libraries'] = 0
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_SHARED_LIBRARY_DRIFT'):
            probe.pair_contract(self.o, self.c)

    def test_missing_early_component_fails_even_when_activity_gap_is_known(self):
        # The OEM provider count remains; its class disappears from candidate.
        original = 'Lcom/yeelight/cherry/OtherTwo;'
        del self.c['definitions'][original]
        with self.assertRaisesRegex(probe.ProbeBlocked, 'CANDIDATE_OEM_GRAPH_DRIFT'):
            probe.pair_contract(self.o, self.c)

    def test_standard_original_audit_remains_closed_on_same_synthetic_gaps(self):
        with patch.object(probe.audit, 'inspect_original'), \
             patch.object(probe.audit, 'inspect_apk', return_value=self.o):
            report = probe.audit.original_prebuild_report(Path('synthetic.apk'))
        self.assertEqual(report['status'], 'PRIVATE_ORIGINAL_REQUIRED_MANIFEST_CLASS_MISSING_BLOCKED')
        with self.assertRaisesRegex(probe.audit.AuditBlocked, 'ORIGINAL_PREBUILD_REQUIRED_CLASS_UNVERIFIED'):
            probe.audit.qualify_original_prebuild(report)


if __name__ == '__main__':
    unittest.main()
