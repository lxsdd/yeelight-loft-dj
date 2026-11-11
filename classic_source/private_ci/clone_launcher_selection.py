#!/usr/bin/env python3
"""Resolve only the installed PRIVATE Classic clone's OEM-owned MAIN/LAUNCHER.

This source-only module does not read an APK unless invoked by the separately
owner-gated private runner. Never print raw OEM components on failures.
"""
import argparse
from pathlib import Path
import re
import sys

PACKAGE = "io.lxsdd.yeelightdj"
ALLOWED_OEM_ACTIVITY = re.compile(r"com\.yeelight\.[A-Za-z_$][A-Za-z0-9_.$]*\Z")
BADGING_PACKAGE = re.compile(r"(?m)^package: name='([^']+)'(?:\s|$)")
REASONS = frozenset((
    "PRIVATE_INPUT_INVALID", "BADGING_PACKAGE_MISMATCH",
    "NO_INSTALLED_LAUNCHER", "INSTALLED_LAUNCHER_AMBIGUOUS",
    "INSTALLED_LAUNCHER_UNTRUSTED", "RESOLVED_LAUNCHER_MISSING",
    "RESOLVED_LAUNCHER_MISMATCH",
))


class CloneLauncherBlocked(ValueError):
    pass


def verify_badging(raw):
    if type(raw) is not str or len(raw) > 65536:
        raise CloneLauncherBlocked("PRIVATE_INPUT_INVALID")
    if BADGING_PACKAGE.findall(raw) != [PACKAGE]:
        raise CloneLauncherBlocked("BADGING_PACKAGE_MISMATCH")


def canonical(value):
    if type(value) is not str or len(value) > 256:
        raise CloneLauncherBlocked("INSTALLED_LAUNCHER_UNTRUSTED")
    prefix = PACKAGE + "/"
    if not value.startswith(prefix):
        raise CloneLauncherBlocked("INSTALLED_LAUNCHER_UNTRUSTED")
    activity = value[len(prefix):]
    if not ALLOWED_OEM_ACTIVITY.fullmatch(activity):
        # Do not mistake a synthetic clone or native DJ Activity for Classic's
        # original OEM launcher. A relative ".Main" resolves into our clone.
        raise CloneLauncherBlocked("INSTALLED_LAUNCHER_UNTRUSTED")
    return activity


def select_installed_launcher(queried, resolved):
    if any(type(x) is not str or len(x) > 65536 for x in (queried, resolved)):
        raise CloneLauncherBlocked("PRIVATE_INPUT_INVALID")
    candidates = [s.strip() for s in queried.splitlines() if s.strip()]
    if not candidates or candidates == ["No activities found"]:
        raise CloneLauncherBlocked("NO_INSTALLED_LAUNCHER")
    if len(candidates) > 32:
        raise CloneLauncherBlocked("INSTALLED_LAUNCHER_AMBIGUOUS")
    chosen = [canonical(s) for s in candidates]
    if len(set(chosen)) != len(chosen):
        raise CloneLauncherBlocked("INSTALLED_LAUNCHER_AMBIGUOUS")
    result = [s.strip() for s in resolved.splitlines() if s.strip()]
    if len(result) != 1 or result == ["No activity found"]:
        raise CloneLauncherBlocked("RESOLVED_LAUNCHER_MISSING")
    selected = canonical(result[0])
    if selected not in chosen:
        raise CloneLauncherBlocked("RESOLVED_LAUNCHER_MISMATCH")
    return selected


def _read(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 65536:
        raise CloneLauncherBlocked("PRIVATE_INPUT_INVALID")
    return path.read_text(encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("preinstall", "installed"))
    parser.add_argument("--badging", type=Path, required=True)
    parser.add_argument("--query", type=Path)
    parser.add_argument("--resolved", type=Path)
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] in ("--preinstall", "--installed"):
        args[0] = args[0][2:]
    opts = parser.parse_args(args)
    try:
        verify_badging(_read(opts.badging))
        if opts.stage == "preinstall":
            if opts.query is not None or opts.resolved is not None:
                raise CloneLauncherBlocked("PRIVATE_INPUT_INVALID")
        else:
            if opts.query is None or opts.resolved is None:
                raise CloneLauncherBlocked("PRIVATE_INPUT_INVALID")
            print(select_installed_launcher(_read(opts.query), _read(opts.resolved)))
    except (OSError, UnicodeError, CloneLauncherBlocked) as error:
        code = str(error) if isinstance(error, CloneLauncherBlocked) else "PRIVATE_INPUT_INVALID"
        print("CLONE_LAUNCHER_DIAGNOSTIC_" +
              (code if code in REASONS else "PRIVATE_INPUT_INVALID"), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
