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
    def test_evil_event_rejected(self):
        for key,value in [('GITHUB_EVENT_NAME','pull_request_target'),('GITHUB_EVENT_NAME','pull_request'),('GITHUB_EVENT_NAME','push'),('GITHUB_REF','refs/heads/evil'),('GITHUB_ACTOR','other'),('GITHUB_REPOSITORY','lxsdd/other'),('RUNNER_ENVIRONMENT','self-hosted'),('GITHUB_WORKFLOW_REF','modified'),('GITHUB_ACTIONS','false'),('GITHUB_SERVER_URL','https://evil.invalid')]:
            with self.subTest(key=key,value=value):
                e=self.valid();e[key]=value
                with self.assertRaises(policy.PolicyFailure):policy.require_approved_dispatch(e,self.event())
    def test_source_sha_invalid(self):
        for x in ('', 'A'*40, 'a'*39,'Z'*40,'b'*41):
            e=self.valid();e['GITHUB_SHA']=x
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

if __name__=='__main__':unittest.main()
