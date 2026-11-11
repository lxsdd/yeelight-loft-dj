#!/usr/bin/env python3
"""Safe fixed-code diagnosis for disposable Android emulator boot failures.

Never prints the emulator's raw log, host information, paths, class names,
account details, package names, error text or stack traces.
"""
import argparse
import re
from pathlib import Path

MAX_BYTES = 1_500_000
PATTERNS = (
    ("KVM_PERMISSION_DENIED", re.compile(
        r"(?:/dev/kvm.{0,110}(?:permission denied|access denied)|"
        r"(?:permission denied|access denied).{0,110}/dev/kvm)", re.I)),
    ("KVM_DEVICE_UNAVAILABLE", re.compile(
        r"(?:/dev/kvm.{0,110}(?:no such file|not found)|"
        r"(?:no such file|not found).{0,110}/dev/kvm)", re.I)),
    ("ACCELERATION_UNAVAILABLE", re.compile(
        r"(?:requires hardware acceleration|failed to initialize kvm|"
        r"hypervisor.{0,75}(?:not available|not found)|"
        r"accel.{0,75}not (?:available|supported))", re.I)),
    ("GRAPHICS_BACKEND_FAILURE", re.compile(
        r"(?:failed to (?:start|initialize) renderer|could not start renderer|"
        r"failed to get egl display|vkcreateinstance.{0,90}fail|"
        r"libglesv2\.so.{0,90}(?:error|failed))", re.I)),
    ("EMULATOR_FATAL_SIGNAL", re.compile(
        r"(?:segmentation fault|sigsegv|signal 11|signal 6|sigabrt)", re.I)),
    ("AVD_CONFIGURATION_FAILURE", re.compile(
        r"(?:unknown avd name|avd .{0,80}not found|cannot find avd)", re.I)),
    ("DISK_OR_MEMORY_EXHAUSTED", re.compile(
        r"(?:no space left on device|out of memory|cannot allocate memory|"
        r"unable to allocate memory)", re.I)),
)


def diagnose(raw: str) -> str:
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_BYTES:
        return "UNCLASSIFIED"
    for name, pattern in PATTERNS:
        if pattern.search(raw):
            return name
    return "UNCLASSIFIED"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("emulator_log", type=Path)
    args = parser.parse_args(argv)
    try:
        log = args.emulator_log
        if not log.is_file() or log.is_symlink() or log.stat().st_size > MAX_BYTES:
            return 2
        text = log.read_text(encoding="utf-8", errors="replace")
    except (OSError, UnicodeError):
        return 2
    print("ANDROID17_EMULATOR_BOOT_DIAGNOSIS_" + diagnose(text))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
