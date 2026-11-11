"""Read-only inventory of code-bearing nested assets in one SHA-pinned OEM APK.

Never extracts assets to disk, executes payloads, logs names, or assumes a code
candidate is installed or visible to Android's application ClassLoader.
Only aggregate counts can be released by the protected summary validator.
"""
from collections import Counter
from io import BytesIO
from pathlib import Path
import re
from zipfile import ZipFile

MAX_ITEMS = 5000
MAX_NESTED_ITEMS = 1200
MAX_CODE_SIZE = 32 * 1024 * 1024
MAX_ARCHIVE_SIZE = 32 * 1024 * 1024
PRIMARY = re.compile(r"classes(?:[2-9][0-9]*)?\.dex\Z")


class EmbeddedInventoryBlocked(ValueError):
    """Constant error only, without private ZIP or descriptor names."""


def need(ok):
    if not ok:
        raise EmbeddedInventoryBlocked("PRIVATE_EMBEDDED_CODE_UNVERIFIED")


def read_bounded(archive, info, limit):
    need(0 <= info.file_size <= limit)
    with archive.open(info, "r") as stream:
        data = stream.read(limit + 1)
    need(len(data) == info.file_size and len(data) <= limit)
    return data


def classify_embedded_code(archive, base_definitions, missing_activities, missing_parents, dex_reader):
    """Count only independently parsed candidate definitions, not loadability.

    Every nested candidate is a possible APK-internal byte source and requires
    separate proof of runtime loader selection. This method does not inspect
    arbitrary cloud/JVM/installed system classpaths or overwrite DEX files.
    """
    need(isinstance(archive, ZipFile) and type(base_definitions) is dict and
         type(missing_activities) is set and type(missing_parents) is set and
         callable(dex_reader))
    items = archive.infolist()
    need(len(items) <= MAX_ITEMS and len(set(i.filename for i in items)) == len(items))
    counters = Counter({
        "embedded_dex_containers": 0,
        "embedded_nested_zip_containers": 0,
        "embedded_nested_dex_containers": 0,
        "embedded_native_elf_containers": 0,
        "embedded_other_entries_scanned": 0,
        "uninspected_nested_archives": 0,
    })
    candidate_activities = set()
    candidate_parents = set()
    duplicates = set()

    def accept_dex(raw):
        need(len(raw) <= MAX_CODE_SIZE and raw.startswith(b"dex\n"))
        try:
            classes = dex_reader(raw)
            need(type(classes) is dict and
                 all(type(x) is str for x in classes))
        except EmbeddedInventoryBlocked:
            raise
        except Exception:
            raise EmbeddedInventoryBlocked("PRIVATE_EMBEDDED_CODE_UNVERIFIED") from None
        candidate_activities.update(classes.keys() & missing_activities)
        candidate_parents.update(classes.keys() & missing_parents)
        duplicates.update(classes.keys() & base_definitions.keys())

    for item in items:
        if item.is_dir():
            continue
        with archive.open(item, "r") as stream:
            magic = stream.read(8)
        if PRIMARY.fullmatch(item.filename):
            continue  # already verified, indexed and counted in baseline DEX
        if magic.startswith(b"dex\n"):
            counters["embedded_dex_containers"] += 1
            accept_dex(read_bounded(archive, item, MAX_CODE_SIZE))
        elif magic.startswith(b"PK\x03\x04"):
            counters["embedded_nested_zip_containers"] += 1
            if item.file_size > MAX_ARCHIVE_SIZE:
                counters["uninspected_nested_archives"] += 1
                continue
            try:
                with ZipFile(BytesIO(read_bounded(archive, item, MAX_ARCHIVE_SIZE))) as nested:
                    sub = nested.infolist()
                    need(len(sub) <= MAX_NESTED_ITEMS and
                         len(set(i.filename for i in sub)) == len(sub))
                    for entry in sub:
                        if entry.is_dir():
                            continue
                        with nested.open(entry, "r") as nstream:
                            first = nstream.read(8)
                        if first.startswith(b"dex\n"):
                            counters["embedded_nested_dex_containers"] += 1
                            accept_dex(read_bounded(nested, entry, MAX_CODE_SIZE))
            except EmbeddedInventoryBlocked:
                raise
            except Exception:
                # Any malformed code-like archive makes its inspection unqualified.
                counters["uninspected_nested_archives"] += 1
        elif magic.startswith(b"\x7fELF"):
            counters["embedded_native_elf_containers"] += 1
        else:
            counters["embedded_other_entries_scanned"] += 1
    return {
        **dict(sorted(counters.items())),
        "embedded_missing_activity_candidate_count": len(candidate_activities),
        "embedded_missing_parent_candidate_count": len(candidate_parents),
        "embedded_duplicate_original_definition_count": len(duplicates),
        "code_candidates_prove_runtime_loader": False,
        "embedded_inventory_status": "CANDIDATE_INVENTORY_ONLY_RUNTIME_UNVERIFIED",
    }


def inspect_pinned_embedded(path):
    """Caller already validated the exact original archive SHA-256."""
    try:
        from private_startup_audit import inspect_apk, dex_index, SYSTEM
        baseline = inspect_apk(path)
        declared = {desc for (role, desc), count in baseline["manifest_references"].items()
                    if role == "activity" and count > 0}
        defs = baseline["definitions"]
        missing = declared - defs.keys()
        parents = {parent for source, inherited in defs.items() for parent in inherited
                   if parent not in defs and not parent.startswith(SYSTEM)}
        with ZipFile(Path(path)) as archive:
            outcome = classify_embedded_code(archive, defs, missing, parents, dex_index)
        return outcome
    except EmbeddedInventoryBlocked:
        raise
    except Exception:
        raise EmbeddedInventoryBlocked("PRIVATE_EMBEDDED_CODE_UNVERIFIED") from None
