#!/usr/bin/env python3
"""Pick an existing catalogued Android 17 (API 37) x86_64 emulator image.

Read-only source gate: parses a bounded SDK-manager listing, never downloads,
accepts licenses, executes commands, or handles OEM APKs. Exact stable package
allowlist only; no preview/canary, non-root Play Store, arbitrary arch or API36.
"""
import argparse
import re
import sys
from pathlib import Path

MAX_CATALOG_BYTES = 5_000_000
# Android 17 system image uses minor API level + 16KB page tag, not
# the obsolete 'android-37;google_apis' package (owner run 43021597830).
# First choice is the current fixed 37.2 image; older 37.1 is a bounded
# fallback only when actually present in the *stable* SDK manager catalog.
APPROVED_IMAGES = (
    "system-images;android-37.2;google_apis_ps16k;x86_64",
    "system-images;android-37.1;google_apis_ps16k;x86_64",
)
COLUMNS = re.compile(
    r"^\s*(system-images;android-[A-Za-z0-9._-]+;[A-Za-z0-9_-]+;[A-Za-z0-9_-]+)\s*\|"
)
HEADERS = re.compile(r"^\s*(Installed packages|Available Packages|Available Updates)\s*:\s*$", re.I)


def select_stable_android17_image(catalog: str) -> str | None:
    if not isinstance(catalog, str) or len(catalog.encode("utf-8")) > MAX_CATALOG_BYTES:
        return None
    listed = set()
    section = ""
    for line in catalog.splitlines():
        match = HEADERS.fullmatch(line)
        if match:
            section = match.group(1).lower()
            continue
        if section not in ("available packages", "installed packages"):
            continue
        match = COLUMNS.match(line)
        if match:
            listed.add(match.group(1))
    for image in APPROVED_IMAGES:
        if image in listed:
            return image
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog_file", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.catalog_file.is_symlink() or not args.catalog_file.is_file():
            return 2
        if args.catalog_file.stat().st_size > MAX_CATALOG_BYTES:
            return 2
        content = args.catalog_file.read_text(encoding="utf-8")
        selected = select_stable_android17_image(content)
    except (OSError, UnicodeError):
        return 2
    if selected is None:
        return 2
    print(selected)
    return 0


if __name__ == "__main__":
    sys.exit(main())
