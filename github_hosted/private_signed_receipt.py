#!/usr/bin/env python3
"""Offline, strictly checked private draft APK receipt. All GitHub I/O belongs to vault shell."""
import hashlib
import json
from pathlib import Path
import re
import sys


def file_sha(path: Path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1048576),b''):
            h.update(block)
    return 'sha256:'+h.hexdigest()


def verify(pages, tag, source_sha, apk, report):
    """Return 0 exact existing release, 3 absent, 2 untrusted/mismatched release."""
    if not re.fullmatch(r'[a-f0-9]{40}',source_sha) or not re.fullmatch(r'classic-test-signed-run-[1-9][0-9]{0,11}-[a-f0-9]{12}',tag) or not tag.endswith(source_sha[:12]):
        return 2
    if not isinstance(pages,list) or any(not isinstance(page,list) for page in pages):
        return 2
    if any(not isinstance(item,dict) for page in pages for item in page):
        return 2
    rows=[item for page in pages for item in page if item.get('tag_name')==tag]
    if not rows: return 3
    if len(rows)!=1: return 2
    x=rows[0]
    if x.get('draft') is not True or x.get('target_commitish')!='main' or x.get('body')!=f'Source SHA: {source_sha}. PRIVATE TEST SIGNED with EPHEMERAL key. Not hardware approved.': return 2
    assets=x.get('assets')
    expected={apk.name:apk,report.name:report}
    if len(expected)!=2 or not isinstance(assets,list) or len(assets)!=2 or any(not isinstance(a,dict) for a in assets): return 2
    if set(a.get('name') for a in assets)!=set(expected): return 2
    for a in assets:
        path=expected[a['name']]
        if a.get('state')!='uploaded' or a.get('size')!=path.stat().st_size or a.get('digest')!=file_sha(path): return 2
    return 0


def main():
    if len(sys.argv)!=6: return 2
    try:
        return verify(json.loads(Path(sys.argv[1]).read_text(encoding='utf8')),
                      sys.argv[2],sys.argv[3],Path(sys.argv[4]),Path(sys.argv[5]))
    except (OSError,ValueError,TypeError): return 2

if __name__=='__main__': sys.exit(main())
