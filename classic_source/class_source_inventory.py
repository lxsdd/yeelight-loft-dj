"""Bounded read-only inventory of a pinned supplemental APK.

Never serialize OEM class graphs, symbols, paths, payloads or hashes. An APK
candidate is not evidence that a split is installed or its classloader active.
"""
import hashlib
import os
import re
import stat
from pathlib import Path
from zipfile import BadZipFile, ZipFile

KINDS = ("split_apk", "shared_library")
MAX_ARCHIVE_BYTES = 160 * 1024 * 1024
MAX_DEX_BYTES = 48 * 1024 * 1024
MAX_DEX_TOTAL_BYTES = 128 * 1024 * 1024
MAX_DEX_FILES = 24
PIN_RE = re.compile(r"[0-9a-f]{64}\Z")
DEX_RE = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)\.dex\Z")


class SourceInventoryError(ValueError):
    """Fixed errors only, without private metadata."""


def inventory_candidate_apk(path, expected_sha256, *, kind, dex_reader):
    """Parse a pinned APK with the existing private dex_index or a fixture reader."""
    if (kind not in KINDS or type(expected_sha256) is not str or
            PIN_RE.fullmatch(expected_sha256) is None or not callable(dex_reader)):
        raise SourceInventoryError("CANDIDATE_SOURCE_BAD_ARGUMENTS")
    if not isinstance(path, (str, Path)):
        raise SourceInventoryError("CANDIDATE_SOURCE_BAD_ARGUMENTS")
    source = Path(path)
    try:
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(source, flags)
        with os.fdopen(fd, "rb") as inp:
            info = os.fstat(inp.fileno())
            if not stat.S_ISREG(info.st_mode):
                raise SourceInventoryError("CANDIDATE_SOURCE_NOT_REGULAR_FILE")
            if not (0 < info.st_size <= MAX_ARCHIVE_BYTES):
                raise SourceInventoryError("CANDIDATE_SOURCE_SIZE_LIMIT")
            digest = hashlib.sha256()
            while chunk := inp.read(1024 * 1024):
                digest.update(chunk)
            if digest.hexdigest() != expected_sha256:
                raise SourceInventoryError("CANDIDATE_SOURCE_PIN_MISMATCH")
            inp.seek(0)
            with ZipFile(inp, "r") as archive:
                infos = archive.infolist()
                names = [i.filename for i in infos]
                if len(set(names)) != len(names):
                    raise SourceInventoryError("CANDIDATE_SOURCE_DUPLICATE_ZIP_ENTRY")
                malformed = any(re.fullmatch(r"classes\d+\.dex", n) and not DEX_RE.fullmatch(n)
                                for n in names)
                if malformed:
                    raise SourceInventoryError("CANDIDATE_SOURCE_DEX_NAME")
                dex_files = sorted((i for i in infos if DEX_RE.fullmatch(i.filename)),
                                   key=lambda x: (x.filename != "classes.dex", x.filename))
                if not dex_files or dex_files[0].filename != "classes.dex":
                    raise SourceInventoryError("CANDIDATE_SOURCE_NO_PRIMARY_DEX")
                if len(dex_files) > MAX_DEX_FILES:
                    raise SourceInventoryError("CANDIDATE_SOURCE_DEX_FILE_LIMIT")
                if any(i.file_size <= 0 or i.file_size > MAX_DEX_BYTES for i in dex_files):
                    raise SourceInventoryError("CANDIDATE_SOURCE_DEX_SIZE_LIMIT")
                if sum(i.file_size for i in dex_files) > MAX_DEX_TOTAL_BYTES:
                    raise SourceInventoryError("CANDIDATE_SOURCE_DEX_TOTAL_LIMIT")
                graph = {}
                for dex_info in dex_files:
                    with archive.open(dex_info, "r") as stream:
                        payload = stream.read(MAX_DEX_BYTES + 1)
                    if len(payload) != dex_info.file_size:
                        raise SourceInventoryError("CANDIDATE_SOURCE_DEX_SIZE_MISMATCH")
                    decoded = dex_reader(payload)
                    if not isinstance(decoded, dict) or any(k in graph for k in decoded):
                        raise SourceInventoryError("CANDIDATE_SOURCE_DUPLICATE_CLASS")
                    graph.update(decoded)
                if not graph:
                    raise SourceInventoryError("CANDIDATE_SOURCE_EMPTY_CLASSES")
        return {
            "kind": kind,
            "definitions": graph,  # Private memory ONLY. No JSON serialization.
            "pinned_artifact_verified": True,
            "dex_files": len(dex_files),
            "definition_count": len(graph),
            "classloader_verified": False,
            "installation_verified": False,
        }
    except SourceInventoryError:
        raise
    except (OSError, BadZipFile, RuntimeError, ValueError, EOFError, OverflowError):
        raise SourceInventoryError("CANDIDATE_SOURCE_UNREADABLE") from None


def sanitized_candidate(source):
    """Only a graph and source kind cross the classifier boundary."""
    if (not isinstance(source, dict) or source.get("pinned_artifact_verified") is not True
            or source.get("classloader_verified") is not False
            or source.get("installation_verified") is not False
            or source.get("kind") not in KINDS or not isinstance(source.get("definitions"), dict)):
        raise SourceInventoryError("CANDIDATE_SOURCE_UNVERIFIED")
    return {"kind": source["kind"], "definitions": source["definitions"]}


def pinned_candidate_graphs(specs, dex_reader):
    """Private list of (path, SHA-256, kind) triples. Never report any identifier."""
    if not isinstance(specs, (tuple, list)) or len(specs) > 16:
        raise SourceInventoryError("CANDIDATE_SOURCE_LIST_INVALID")
    result = []
    for spec in specs:
        if not isinstance(spec, (tuple, list)) or len(spec) != 3:
            raise SourceInventoryError("CANDIDATE_SOURCE_LIST_INVALID")
        path, pin, kind = spec
        result.append(sanitized_candidate(inventory_candidate_apk(
            path, pin, kind=kind, dex_reader=dex_reader)))
    return result


def classify_pinned_sources(base_definitions, declared_activities, *,
                            system_prefixes, candidate_specs, dex_reader):
    """Never bypass the missing-classes hard gate on a candidate match."""
    from activity_provenance import classify_original_classpath
    return classify_original_classpath(
        base_definitions, declared_activities, system_prefixes=system_prefixes,
        supplemental_sources=pinned_candidate_graphs(candidate_specs, dex_reader))
