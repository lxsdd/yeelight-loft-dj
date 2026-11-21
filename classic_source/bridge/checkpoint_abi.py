#!/usr/bin/env python3
"""Public-source-only Kotlin ABI gate for the Yeelight Classic DJ bridge.

The original Classic APK can supply older Kotlin stdlib classes. Kotlin's
inline runCatching emits a reference to kotlin.Result.Companion; that field
need not be present in the OEM copy. Inspect only our own compiled sidecar
class, not any OEM binary. Output fixed non-sensitive status codes only.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import struct
import sys
import zipfile

TARGET = "io/github/lxsdd/yeelightclassicdj/runtime/CheckpointRuntime.class"
MAX_CLASS_SIZE = 2_000_000
MAX_JAR_SIZE = 100_000_000


class AbiGateError(ValueError):
    pass


def _require(ok: bool, code: str) -> None:
    if not ok:
        raise AbiGateError(code)


def inspect_class(data: bytes) -> dict:
    """Read only the classfile constant pool's symbolic field references."""
    _require(type(data) is bytes and 10 <= len(data) <= MAX_CLASS_SIZE,
             "CLASSIC_CHECKPOINT_CLASS_BYTES_INVALID")
    _require(data[:4] == b"\xca\xfe\xba\xbe",
             "CLASSIC_CHECKPOINT_CLASS_MAGIC_INVALID")
    count = struct.unpack_from(">H", data, 8)[0]
    _require(1 < count < 25000, "CLASSIC_CHECKPOINT_CP_COUNT_INVALID")
    cp: list[tuple | None] = [None] * count
    cursor = 10

    def read(n: int) -> bytes:
        nonlocal cursor
        _require(0 <= n <= len(data) - cursor, "CLASSIC_CHECKPOINT_CP_TRUNCATED")
        result = data[cursor:cursor + n]
        cursor += n
        return result

    index = 1
    while index < count:
        tag = read(1)[0]
        if tag == 1:
            length = int.from_bytes(read(2), "big")
            cp[index] = (tag, read(length))
        elif tag in (3, 4):
            read(4); cp[index] = (tag,)
        elif tag in (5, 6):
            read(8); cp[index] = (tag,)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[index] = (tag, int.from_bytes(read(2), "big"))
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[index] = (tag, int.from_bytes(read(2), "big"),
                         int.from_bytes(read(2), "big"))
        elif tag == 15:
            cp[index] = (tag, read(1)[0], int.from_bytes(read(2), "big"))
        else:
            raise AbiGateError("CLASSIC_CHECKPOINT_CP_TAG_INVALID")
        index += 1

    def name(idx: int) -> bytes:
        _require(0 < idx < count and cp[idx] is not None and cp[idx][0] == 1,
                 "CLASSIC_CHECKPOINT_CP_REFERENCE_INVALID")
        return cp[idx][1]

    def class_name(idx: int) -> bytes:
        _require(0 < idx < count and cp[idx] is not None and cp[idx][0] == 7,
                 "CLASSIC_CHECKPOINT_CP_REFERENCE_INVALID")
        return name(cp[idx][1])

    _require(any(entry is not None and entry[0] == 1 and entry[1] == b"configurePulse"
                 for entry in cp), "CLASSIC_CHECKPOINT_CONFIGURE_METHOD_NOT_FOUND")
    for entry in cp:
        if entry is None or entry[0] != 9:
            continue
        owner = class_name(entry[1])
        name_and_type = entry[2]
        _require(0 < name_and_type < count and cp[name_and_type] is not None
                 and cp[name_and_type][0] == 12,
                 "CLASSIC_CHECKPOINT_CP_REFERENCE_INVALID")
        field = name(cp[name_and_type][1])
        if owner == b"kotlin/Result" and field == b"Companion":
            raise AbiGateError(
                "CLASSIC_CHECKPOINT_KOTLIN_RESULT_COMPANION_ABI_BLOCKED")
    return {"status": "CLASSIC_CHECKPOINT_RESULT_FIELD_ABI_CLEAR_STATIC_ONLY",
            "field_ref_restriction": "KOTLIN_RESULT_COMPANION_ABSENT",
            "runtime_qualified": False}


def verify_bridge_jar(path: Path) -> dict:
    _require(isinstance(path, Path) and path.is_file() and not path.is_symlink()
             and path.stat().st_size <= MAX_JAR_SIZE,
             "CLASSIC_CHECKPOINT_JAR_INVALID")
    try:
        with zipfile.ZipFile(path) as jar:
            members = jar.namelist()
            _require(len(members) == len(set(members)),
                     "CLASSIC_CHECKPOINT_JAR_DUPLICATE_ENTRY")
            _require(members.count(TARGET) == 1,
                     "CLASSIC_CHECKPOINT_CLASS_NOT_FOUND")
            info = jar.getinfo(TARGET)
            _require(info.file_size <= MAX_CLASS_SIZE,
                     "CLASSIC_CHECKPOINT_CLASS_SIZE_EXCEEDED")
            return inspect_class(jar.read(info))
    except (OSError, zipfile.BadZipFile, ValueError, RuntimeError) as error:
        if isinstance(error, AbiGateError):
            raise
        raise AbiGateError("CLASSIC_CHECKPOINT_JAR_READ_FAILED") from None


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Static, source-owned ABI gate without OEM APK data")
    parser.add_argument("bridge_jar", type=Path)
    args = parser.parse_args(argv)
    try:
        verify_bridge_jar(args.bridge_jar)
    except AbiGateError as failure:
        print(str(failure), file=sys.stderr)
        return 2
    print("CLASSIC_CHECKPOINT_RESULT_FIELD_ABI=PASS_STATIC_ONLY")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
