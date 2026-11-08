#!/usr/bin/env python3
"""Run real-OEM tests only with pinned APK; treat any skip as a FAILED gate."""
import os
from pathlib import Path
import sys
import unittest

from classic_first import ORIGINAL_SHA256, digest_file

def run_tests():
    location=os.environ.get("CLASSIC_ORIGINAL_APK")
    if not location:
        raise RuntimeError("PINNED_REAL_OEM_PATH_NOT_SET")
    path=Path(location)
    if not path.is_file() or path.is_symlink():
        raise RuntimeError("PINNED_REAL_OEM_FILE_MISSING")
    if digest_file(path)!=ORIGINAL_SHA256:
        raise RuntimeError("PINNED_REAL_OEM_SHA256_MISMATCH")
    suite=unittest.defaultTestLoader.discover(
        start_dir=str(Path(__file__).resolve().parent/"tests"),
        pattern="test_real_classic_apk.py")
    stream=open(os.devnull,"w")
    try:
        report=unittest.TextTestRunner(stream=stream,verbosity=0).run(suite)
    finally:
        stream.close()
    if report.testsRun<4 or report.skipped or not report.wasSuccessful():
        raise RuntimeError("PINNED_REAL_OEM_TESTS_FAILED_OR_SKIPPED")
    print("PINNED_REAL_OEM_TESTS_PASS_COUNT="+str(report.testsRun))
    return report.testsRun

if __name__=="__main__":
    try:run_tests()
    except (RuntimeError,OSError) as exc:
        # Error codes only; no proprietary APK content or paths in public log.
        print("PRIVATE_REAL_OEM_GATE_BLOCKED_"+str(exc),file=sys.stderr)
        raise SystemExit(2)
