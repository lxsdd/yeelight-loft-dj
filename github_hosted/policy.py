"""Fail-closed policy for an owner-triggered public-runner/private-output workflow.

Source-only. Never handles passwords, APK bytes or GitHub network calls.
"""
from __future__ import annotations
import json
import os
import re
from pathlib import Path

SOURCE_REPO = 'lxsdd/yeelight-loft-dj'
VAULT_REPO = 'lxsdd/yeelight-classic-vault'
WORKFLOW_FILE = '.github/workflows/classic-protected-assembly.yml'
SIGNED_WORKFLOW_FILE = '.github/workflows/classic-private-test-signed.yml'
# Exact allowlist. Both workflows also enforce owner-only workflow_dispatch on protected main.
ALLOWED_WORKFLOW_REFS = frozenset(
    SOURCE_REPO + '/' + path + '@refs/heads/main'
    for path in (WORKFLOW_FILE, SIGNED_WORKFLOW_FILE)
)
INPUT_RELEASE = 'classic-original-3.5.4-v1'
INPUT_APK = 'com.yeelight.cherry_3.5.4.apk'
INPUT_SHA256 = 'abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2'
APKTOOL_SHA256 = 'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'

class PolicyFailure(Exception):
    pass

def require_approved_dispatch(env: dict[str,str] | None = None, event: dict | None = None) -> dict:
    e = dict(os.environ if env is None else env)
    required = {
        'GITHUB_ACTIONS': 'true',
        'GITHUB_REPOSITORY': SOURCE_REPO,
        'GITHUB_EVENT_NAME': 'workflow_dispatch',
        'GITHUB_REF': 'refs/heads/main',
        'GITHUB_ACTOR': 'lxsdd',
        'RUNNER_ENVIRONMENT': 'github-hosted',
    }
    for key, value in required.items():
        if e.get(key) != value:
            raise PolicyFailure('DISPATCH_DENIED_'+key)
    sha = e.get('GITHUB_SHA', '')
    if re.fullmatch(r'[a-f0-9]{40}', sha) is None:
        raise PolicyFailure('DISPATCH_DENIED_SOURCE_SHA')
    ref = e.get('GITHUB_WORKFLOW_REF', '')
    if ref not in ALLOWED_WORKFLOW_REFS:
        raise PolicyFailure('DISPATCH_DENIED_WORKFLOW_REF')
    if e.get('GITHUB_HEAD_REF') or e.get('GITHUB_BASE_REF'):
        raise PolicyFailure('DISPATCH_DENIED_PR_CONTEXT')
    if event is None:
        event_file=e.get('GITHUB_EVENT_PATH', '')
        if not event_file or not Path(event_file).is_file():
            raise PolicyFailure('DISPATCH_EVENT_FILE_MISSING')
        try:
            event=json.loads(Path(event_file).read_text('utf-8'))
        except (json.JSONDecodeError, UnicodeError, OSError) as ex:
            raise PolicyFailure('DISPATCH_EVENT_FILE_INVALID') from ex
    repo = event.get('repository')
    if not isinstance(repo, dict) or repo.get('full_name') != SOURCE_REPO or repo.get('private') is not False:
        raise PolicyFailure('DISPATCH_REPOSITORY_IDENTITY_MISMATCH')
    if e.get('GITHUB_SERVER_URL') != 'https://github.com':
        raise PolicyFailure('DISPATCH_UNEXPECTED_SERVER')
    if event.get('ref') not in ('refs/heads/main',None):
        raise PolicyFailure('DISPATCH_EVENT_REF_MISMATCH')
    return {'status': 'GATED_OWNER_DISPATCH_SOURCE_ONLY', 'source_sha': sha}

if __name__ == '__main__':
    try:
        print(json.dumps(require_approved_dispatch()))
    except PolicyFailure as exc:
        # Never echo environment values, event data or secret contents.
        print('BLOCKED: '+str(exc))
        raise SystemExit(2)
