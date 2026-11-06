"""Public checkout inventory barrier; checks only the Classic-specific source subtree.

This is an accidental disclosure gate, not a malware scanner, and cannot protect
files committed outside the scanned paths. No Vault input or token is required.
"""
from __future__ import annotations
import os
from pathlib import Path
import subprocess
import sys

RESTRICTED = ('.apk', '.aab', '.dex', '.smali', '.so', '.keystore', '.jks', '.p12', '.pfx', '.key', '.pem', '.db', '.sqlite', '.pcap')
IN_SCOPE = ('classic_source/', 'github_hosted/', '.github/workflows/classic-')

class BoundaryViolation(RuntimeError):
    pass

def check_paths(paths: list[str], directory: Path):
    checked = []
    for raw in paths:
        if not raw.startswith(IN_SCOPE):
            continue
        p = Path(raw)
        if p.is_absolute() or '..' in p.parts or '\\' in raw:
            raise BoundaryViolation('INVALID_PUBLIC_PATH')
        if raw.lower().endswith(RESTRICTED):
            raise BoundaryViolation('PROPRIETARY_OR_SENSITIVE_FILE_IN_PUBLIC_TREE')
        full = directory/p
        if full.is_symlink() or (full.exists() and not full.is_file()):
            raise BoundaryViolation('NONREGULAR_PUBLIC_FILE')
        if not full.is_file():
            raise BoundaryViolation('MISSING_PUBLIC_SOURCE')
        checked.append(raw)
    if not checked:
        raise BoundaryViolation('NO_CLASSIC_SOURCE_PRESENT')
    return len(checked)

def main():
    root = Path(__file__).resolve().parents[1]
    if not (root/'.git').exists():
        raise BoundaryViolation('GIT_CHECKOUT_REQUIRED')
    result = subprocess.run(['git','ls-files','-z'],cwd=root,capture_output=True,check=True)
    paths = result.stdout.decode('utf-8','strict').rstrip('\0').split('\0')
    n=check_paths(paths,root)
    print(f'PUBLIC_CLASSIC_SOURCE_INVENTORY_PASS checked={n}')

if __name__ == '__main__':
    try: main()
    except (BoundaryViolation, OSError, subprocess.SubprocessError, UnicodeError) as exc:
        print('BLOCKED: '+str(exc),file=sys.stderr)
        sys.exit(2)
