#!/usr/bin/env python3
"""Verify exactly one preexisting or newly uploaded private draft receipt.

Input is gh api --paginate --slurp output from authenticated PRIVATE repository
release-list endpoint. The by-tag API excludes drafts and must never be used.
No network, secrets, APK installation, or filesystem mutation.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import sys

def verified(pages, apk: Path, report: Path, tag: str, source_sha: str) -> int:
    if (not re.fullmatch(r"classic-unsigned-run-[1-9][0-9]{0,11}-[0-9a-f]{12}", tag)
            or not re.fullmatch(r"[a-f0-9]{40}", source_sha)
            or not tag.endswith(source_sha[:12])):
        return 2
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        return 2
    if any(not isinstance(x, dict) for page in pages for x in page):
        return 2
    matches=[release for page in pages for release in page if release.get('tag_name') == tag]
    if len(matches)==0:
        return 3  # Tag absent: caller may create a new PRIVATE draft only.
    if len(matches)!=1:
        return 2
    d=matches[0]
    if (d.get('draft') is not True
            or d.get('target_commitish')!='main'
            or d.get('body')!=f"Source SHA: {source_sha}. UNSIGNED. Structural preflight only; NOT installed or hardware approved."):
        return 2
    assets=d.get('assets')
    expected={apk.name:apk,report.name:report}
    if not isinstance(assets,list) or len(assets)!=2 or len(expected)!=2:
        return 2
    if any(not isinstance(a,dict) for a in assets):
        return 2
    if set(a.get('name') for a in assets)!=set(expected):
        return 2
    for a in assets:
        path=expected[a['name']]
        if a.get('state')!='uploaded' or a.get('size')!=path.stat().st_size:
            return 2
        h=hashlib.sha256()
        with path.open('rb') as stream:
            for part in iter(lambda:stream.read(1<<20),b''):
                h.update(part)
        if a.get('digest')!='sha256:'+h.hexdigest():
            return 2
    return 0

def main(argv: list[str]) -> int:
    if len(argv)!=6:
        return 2
    try:
        pages=json.loads(Path(argv[1]).read_text(encoding='utf-8'))
        return verified(pages, Path(argv[2]),Path(argv[3]),argv[4],argv[5])
    except (OSError, ValueError, KeyError, TypeError):
        return 2

if __name__=='__main__':
    sys.exit(main(sys.argv))
