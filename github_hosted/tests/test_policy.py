from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import unittest

MODULE=Path(__file__).resolve().parents[1] / 'policy.py'
spec=importlib.util.spec_from_file_location('classic_ci_policy', MODULE)
policy=importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(policy)

class PolicyTests(unittest.TestCase):
    def valid(self):
        return {
            'GITHUB_ACTIONS':'true', 'GITHUB_REPOSITORY':policy.SOURCE_REPO,
            'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/main',
            'GITHUB_ACTOR':'lxsdd','RUNNER_ENVIRONMENT':'github-hosted',
            'GITHUB_SHA':'a'*40, 'GITHUB_WORKFLOW_REF':
                policy.SOURCE_REPO+'/'+policy.WORKFLOW_FILE+'@refs/heads/main',
            'GITHUB_SERVER_URL':'https://github.com'
        }
    def event(self):
        return {'repository':{'full_name':policy.SOURCE_REPO,'private':False},'ref':'refs/heads/main'}
    def test_owner_protected_main_passes(self):
        self.assertEqual(policy.require_approved_dispatch(self.valid(),self.event())['source_sha'],'a'*40)
    def test_exact_six_private_classic_workflows_allowed(self):
        allowed = (policy.WORKFLOW_FILE, policy.SIGNED_WORKFLOW_FILE,
                   policy.AUDIT_WORKFLOW_FILE, policy.READONLY_WORKFLOW_FILE,
                   policy.STOCK_ORIGINAL_WORKFLOW_FILE, policy.PROBE_WORKFLOW_FILE)
        self.assertEqual(len(policy.ALLOWED_WORKFLOW_REFS), 6)
        for path in allowed:
            with self.subTest(path=path):
                e=self.valid()
                e['GITHUB_WORKFLOW_REF']=policy.SOURCE_REPO+'/'+path+'@refs/heads/main'
                self.assertEqual(policy.require_approved_dispatch(e,self.event())['source_sha'], 'a'*40)
    def test_owner_stock_original_workflow_exactly_matches_protected_implementation(self):
        ref = policy.SOURCE_REPO + '/' + policy.STOCK_ORIGINAL_WORKFLOW_FILE + '@refs/heads/main'
        path = Path(__file__).resolve().parents[2] / policy.STOCK_ORIGINAL_WORKFLOW_FILE
        self.assertTrue(path.is_file())
        self.assertIn(ref, policy.ALLOWED_WORKFLOW_REFS)
        env = self.valid()
        env['GITHUB_WORKFLOW_REF'] = ref
        self.assertEqual(policy.require_approved_dispatch(env, self.event())['source_sha'], 'a'*40)
        for bad_ref in (
            ref.replace('@refs/heads/main', '@refs/heads/feature'),
            ref.replace('@refs/heads/main', '@refs/tags/release'),
            ref.replace('@refs/heads/main', '@refs/pull/90/merge'),
            ref.replace('@refs/heads/main', '@refs/heads/main/'),
            ref.replace('/classic-private-stock-original-android17.yml', '/classic-private-stock-original-android17.yml.bak'),
            ref.replace(policy.SOURCE_REPO, 'attacker/yeelight-loft-dj', 1),
            ref.replace('/classic-private-stock-original-android17.yml', '/classic-private-test-signed.yml'),
        ):
            with self.subTest(ref=bad_ref):
                env['GITHUB_WORKFLOW_REF'] = bad_ref
                if bad_ref in policy.ALLOWED_WORKFLOW_REFS:
                    # The old signed workflow remains authorized on main, but
                    # stock tests must not implicitly claim it is a stock run.
                    self.assertNotEqual(bad_ref, ref)
                else:
                    with self.assertRaisesRegex(policy.PolicyFailure, 'DISPATCH_DENIED_WORKFLOW_REF'):
                        policy.require_approved_dispatch(env, self.event())
        for key, value in (
            ('GITHUB_ACTOR', 'other'),
            ('GITHUB_REF', 'refs/heads/feature'),
            ('GITHUB_EVENT_NAME', 'push'),
            ('GITHUB_REPOSITORY', 'attacker/yeelight-loft-dj'),
            ('RUNNER_ENVIRONMENT', 'self-hosted'),
            ('GITHUB_HEAD_REF', 'branch'),
        ):
            env = self.valid()
            env['GITHUB_WORKFLOW_REF'] = ref
            env[key] = value
            with self.subTest(key=key), self.assertRaises(policy.PolicyFailure):
                policy.require_approved_dispatch(env, self.event())
        for bad_event in (
            {'repository': {'full_name': policy.SOURCE_REPO, 'private': True},
             'ref':'refs/heads/main'},
            {'repository': {'full_name': 'attacker/yeelight-loft-dj', 'private': False},
             'ref':'refs/heads/main'},
            {'repository': {'full_name': policy.SOURCE_REPO, 'private': False},
             'ref':'refs/heads/feature'},
        ):
            with self.subTest(event=bad_event), self.assertRaises(policy.PolicyFailure):
                policy.require_approved_dispatch(env=self.valid() |
                    {'GITHUB_WORKFLOW_REF': ref}, event=bad_event)

    def test_unlisted_workflows_and_unprotected_refs_rejected(self):
        forbidden=(
            policy.SOURCE_REPO+'/.github/workflows/not-classic.yml@refs/heads/main',
            policy.SOURCE_REPO+'/'+policy.SIGNED_WORKFLOW_FILE+'@refs/heads/test',
            policy.SOURCE_REPO+'/'+policy.SIGNED_WORKFLOW_FILE+'@refs/tags/v1',
            policy.SOURCE_REPO+'/'+policy.SIGNED_WORKFLOW_FILE+'@refs/heads/main/',
            policy.SOURCE_REPO+'/'+policy.SIGNED_WORKFLOW_FILE.upper()+'@refs/heads/main',
            'attacker/yeelight-loft-dj/'+policy.SIGNED_WORKFLOW_FILE+'@refs/heads/main',
            policy.SOURCE_REPO+'/'+policy.WORKFLOW_FILE+'@refs/heads/feature',
        )
        for ref in forbidden:
            with self.subTest(ref=ref):
                e=self.valid()
                e['GITHUB_WORKFLOW_REF']=ref
                with self.assertRaises(policy.PolicyFailure):
                    policy.require_approved_dispatch(e,self.event())
    def test_audit_rejects_unprotected_and_foreign_workflow_refs(self):
        forbidden=(
            policy.SOURCE_REPO+'/'+policy.AUDIT_WORKFLOW_FILE+'@refs/heads/audit-fix',
            policy.SOURCE_REPO+'/'+policy.AUDIT_WORKFLOW_FILE+'@refs/tags/v1',
            policy.SOURCE_REPO+'/'+policy.AUDIT_WORKFLOW_FILE+'@refs/heads/main/',
            policy.SOURCE_REPO+'/'+policy.AUDIT_WORKFLOW_FILE.upper()+'@refs/heads/main',
            'attacker/yeelight-loft-dj/'+policy.AUDIT_WORKFLOW_FILE+'@refs/heads/main',
            policy.SOURCE_REPO+'/'+policy.AUDIT_WORKFLOW_FILE+'@refs/pull/77/merge',
        )
        for ref in forbidden:
            with self.subTest(ref=ref):
                e=self.valid()
                e['GITHUB_WORKFLOW_REF']=ref
                with self.assertRaisesRegex(policy.PolicyFailure,'DISPATCH_DENIED_WORKFLOW_REF'):
                    policy.require_approved_dispatch(e,self.event())
    def test_audit_preserves_other_owner_and_dispatch_gates(self):
        for key,value in [('GITHUB_EVENT_NAME','push'),('GITHUB_REF','refs/heads/feature'),
                          ('GITHUB_ACTOR','attacker'),('GITHUB_REPOSITORY','attacker/repo'),
                          ('RUNNER_ENVIRONMENT','self-hosted'),('GITHUB_SERVER_URL','https://evil.invalid')]:
            with self.subTest(key=key):
                e=self.valid()
                e['GITHUB_WORKFLOW_REF']=policy.SOURCE_REPO+'/'+policy.AUDIT_WORKFLOW_FILE+'@refs/heads/main'
                e[key]=value
                with self.assertRaises(policy.PolicyFailure):
                    policy.require_approved_dispatch(e,self.event())
    def test_evil_event_rejected(self):
        for key,value in [('GITHUB_EVENT_NAME','pull_request_target'),('GITHUB_EVENT_NAME','pull_request'),('GITHUB_EVENT_NAME','push'),('GITHUB_REF','refs/heads/evil'),('GITHUB_ACTOR','other'),('GITHUB_REPOSITORY','lxsdd/other'),('RUNNER_ENVIRONMENT','self-hosted'),('GITHUB_WORKFLOW_REF','modified'),('GITHUB_ACTIONS','false'),('GITHUB_SERVER_URL','https://evil.invalid')]:
            with self.subTest(key=key,value=value):
                e=self.valid();e[key]=value
                with self.assertRaises(policy.PolicyFailure):policy.require_approved_dispatch(e,self.event())
    def test_source_sha_invalid(self):
        for x in ('', 'A'*40, 'a'*39,'Z'*40,'b'*41):
            e=self.valid()
            e['GITHUB_SHA']=x
            with self.assertRaises(policy.PolicyFailure):policy.require_approved_dispatch(e,self.event())
    def test_pr_context_denied(self):
        for name in ('GITHUB_BASE_REF','GITHUB_HEAD_REF'):
            e=self.valid();e[name]='branch'
            with self.assertRaises(policy.PolicyFailure):policy.require_approved_dispatch(e,self.event())
    def test_event_identity_denied(self):
        for obj in ({'repository':{'full_name':policy.SOURCE_REPO,'private':True}}, {'repository':{'full_name':'someone/else','private':False}}, {'ref':'refs/heads/main'}, {'repository':{'full_name':policy.SOURCE_REPO,'private':False}, 'ref':'refs/heads/insecure'}):
            with self.assertRaises(policy.PolicyFailure):policy.require_approved_dispatch(self.valid(),obj)
    def test_event_file_required_without_event(self):
        with self.assertRaises(policy.PolicyFailure):policy.require_approved_dispatch(self.valid())

    def test_readonly_workflow_rejects_nonmain_and_foreign_owner(self):
        ref = policy.SOURCE_REPO + '/' + policy.READONLY_WORKFLOW_FILE + '@refs/heads/main'
        env = self.valid()
        env['GITHUB_WORKFLOW_REF'] = ref
        self.assertEqual(policy.require_approved_dispatch(env, self.event())['source_sha'], 'a'*40)
        for wrong in (ref.replace('@refs/heads/main', '@refs/heads/test'),
                      ref.replace(policy.SOURCE_REPO, 'attacker/source', 1),
                      ref.replace('/classic-private-readonly-parity.yml', '/classic-private-readonly-parity.yml.bak')):
            with self.subTest(ref=wrong), self.assertRaises(policy.PolicyFailure):
                env['GITHUB_WORKFLOW_REF'] = wrong
                policy.require_approved_dispatch(env, self.event())
        for key, wrong in [('GITHUB_ACTOR', 'attacker'), ('GITHUB_REF', 'refs/heads/test'),
                           ('GITHUB_EVENT_NAME', 'pull_request')]:
            env = self.valid()
            env['GITHUB_WORKFLOW_REF'] = ref
            env[key] = wrong
            with self.subTest(key=key), self.assertRaises(policy.PolicyFailure):
                policy.require_approved_dispatch(env, self.event())

    def test_runtime_probe_requires_exact_approved_manual_main_and_is_not_release(self):
        ref = policy.SOURCE_REPO + '/' + policy.PROBE_WORKFLOW_FILE + '@refs/heads/main'
        self.assertIn(ref, policy.ALLOWED_WORKFLOW_REFS)
        env = self.valid()
        env['GITHUB_WORKFLOW_REF'] = ref
        self.assertEqual(
            policy.require_approved_dispatch(env, self.event())['source_sha'], 'a'*40)
        for wrong in (
                ref.replace('@refs/heads/main', '@refs/heads/test'),
                ref.replace('@refs/heads/main', '@refs/pull/98/merge'),
                ref.replace(policy.SOURCE_REPO, 'attacker/yeelight-loft-dj', 1),
                ref.replace('.yml', '.yml.bak')):
            with self.subTest(ref=wrong), self.assertRaises(policy.PolicyFailure):
                env['GITHUB_WORKFLOW_REF'] = wrong
                policy.require_approved_dispatch(env, self.event())
        for key, value in (
                ('GITHUB_ACTOR', 'not-owner'),
                ('GITHUB_EVENT_NAME', 'pull_request'),
                ('GITHUB_REF', 'refs/heads/feature'),
                ('RUNNER_ENVIRONMENT', 'self-hosted'),
                ('GITHUB_HEAD_REF', 'untrusted')):
            env = self.valid()
            env['GITHUB_WORKFLOW_REF'] = ref
            env[key] = value
            with self.subTest(key=key), self.assertRaises(policy.PolicyFailure):
                policy.require_approved_dispatch(env, self.event())

if __name__=='__main__':unittest.main()
