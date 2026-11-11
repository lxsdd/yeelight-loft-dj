#!/usr/bin/env python3
"""Privacy-safe launcher selection for pinned OEM stock in a disposable emulator."""
import argparse
from pathlib import Path
import re
import sys

PACKAGE = "com.yeelight.cherry"
COMPONENT = re.compile(r"com\.yeelight\.cherry/(?:\.[A-Za-z_$][A-Za-z0-9_.$]*|com\.yeelight\.[A-Za-z_$][A-Za-z0-9_.$]*)\Z")
BADGING_PACKAGE = re.compile(r"(?m)^package: name='([^']+)'(?:\s|$)")
REASONS = frozenset(("PRIVATE_INPUT_INVALID", "BADGING_PACKAGE_MISMATCH",
                     "NO_INSTALLED_LAUNCHER", "INSTALLED_LAUNCHER_AMBIGUOUS",
                     "INSTALLED_LAUNCHER_UNTRUSTED", "RESOLVED_LAUNCHER_MISSING",
                     "RESOLVED_LAUNCHER_MISMATCH"))


class LauncherBlocked(ValueError):
    pass


def verify_badging(raw):
    if not isinstance(raw, str) or len(raw) > 65536:
        raise LauncherBlocked("PRIVATE_INPUT_INVALID")
    if BADGING_PACKAGE.findall(raw) != [PACKAGE]:
        raise LauncherBlocked("BADGING_PACKAGE_MISMATCH")
    return True


def canonical(component):
    if not COMPONENT.fullmatch(component):
        raise LauncherBlocked("INSTALLED_LAUNCHER_UNTRUSTED")
    name = component[len(PACKAGE) + 1:]
    return PACKAGE + name if name.startswith(".") else name


def select_installed_launcher(queried, resolved):
    """Cross-check installed MAIN/LAUNCHER query with explicit OS resolution."""
    if any(not isinstance(v, str) or len(v) > 65536 for v in (queried, resolved)):
        raise LauncherBlocked("PRIVATE_INPUT_INVALID")
    lines = [v.strip() for v in queried.splitlines() if v.strip()]
    if not lines or lines == ["No activities found"]:
        raise LauncherBlocked("NO_INSTALLED_LAUNCHER")
    if len(lines) > 32:
        raise LauncherBlocked("INSTALLED_LAUNCHER_AMBIGUOUS")
    candidates = [canonical(v) for v in lines]
    if len(set(candidates)) != len(candidates):
        raise LauncherBlocked("INSTALLED_LAUNCHER_AMBIGUOUS")
    chosen = [v.strip() for v in resolved.splitlines() if v.strip()]
    if len(chosen) != 1 or chosen == ["No activity found"]:
        raise LauncherBlocked("RESOLVED_LAUNCHER_MISSING")
    selected = canonical(chosen[0])
    if selected not in candidates:
        raise LauncherBlocked("RESOLVED_LAUNCHER_MISMATCH")
    return selected


def read_private_file(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 65536:
        raise LauncherBlocked("PRIVATE_INPUT_INVALID")
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
    parsed = parser.parse_args(args)
    try:
        verify_badging(read_private_file(parsed.badging))
        if parsed.stage == "preinstall":
            if parsed.query is not None or parsed.resolved is not None:
                raise LauncherBlocked("PRIVATE_INPUT_INVALID")
        else:
            if parsed.query is None or parsed.resolved is None:
                raise LauncherBlocked("PRIVATE_INPUT_INVALID")
            print(select_installed_launcher(read_private_file(parsed.query),
                                            read_private_file(parsed.resolved)))
    except (OSError, UnicodeError, LauncherBlocked) as exc:
        reason = str(exc) if isinstance(exc, LauncherBlocked) else "PRIVATE_INPUT_INVALID"
        print("STOCK_LAUNCHER_DIAGNOSTIC_" + (reason if reason in REASONS else "PRIVATE_INPUT_INVALID"),
              file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
