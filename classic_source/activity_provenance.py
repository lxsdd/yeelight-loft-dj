"""Safe, read-only diagnostic of unverified OEM Activity declarations.

This module never logs class names, descriptor hashes, process or split names.
A disabled/split-labelled class stays unverified until separately proven by a
private classpath or isolated Android runtime test. No class waivers.
"""
from collections import Counter

ANDROID = "{http://schemas.android.com/apk/res/android}"
ENABLED_BUCKETS = ("explicit_true", "explicit_false", "inherited", "unresolved_resource")

class ProvenanceError(ValueError):
    """Errors contain fixed codes, never dynamic OEM strings."""

def _descriptor(name, package):
    if not isinstance(name, str) or not name or "$" + "{" in name:
        raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_NAME")
    full = package + name if name.startswith(".") else (
        package + "." + name if "." not in name else name)
    return "L" + full.replace(".", "/") + ";"

def _enabled_bucket(value):
    if value is None:
        return "inherited"
    if value == "true":
        return "explicit_true"
    if value == "false":
        return "explicit_false"
    return "unresolved_resource"

def index_activity_metadata(parsed, package):
    """Temporary in-memory descriptor lookup, never serialized."""
    if not isinstance(parsed, dict) or not isinstance(parsed.get("elements"), list):
        raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_DOCUMENT")
    if not isinstance(package, str) or not package:
        raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_PACKAGE")
    metadata = {}
    for node in parsed["elements"]:
        if not isinstance(node, dict):
            raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_ELEMENT")
        if node.get("tag") != "activity":
            continue
        attrs = node.get("attributes")
        if not isinstance(attrs, dict):
            raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_ATTRIBUTES")
        desc = _descriptor(attrs.get(ANDROID + "name"), package)
        if desc in metadata:
            raise ProvenanceError("ACTIVITY_PROVENANCE_DUPLICATE")
        raw_split = attrs.get(ANDROID + "splitName")
        metadata[desc] = {
            "enabled": _enabled_bucket(attrs.get(ANDROID + "enabled")),
            "split_labeled": raw_split is not None and raw_split != "",
            "process_labeled": attrs.get(ANDROID + "process") is not None,
        }
    return metadata

def summarize_missing_activities(index, definitions, declared_activities):
    """Produce sanitized aggregate counters without accepting any missing type."""
    if not isinstance(index, dict) or not isinstance(definitions, dict):
        raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_INPUT")
    if not isinstance(declared_activities, set) or set(index) != declared_activities:
        raise ProvenanceError("ACTIVITY_PROVENANCE_MANIFEST_DRIFT")
    buckets = Counter({k: 0 for k in ENABLED_BUCKETS})
    split_labeled = 0
    process_labeled = 0
    for desc in declared_activities:
        if desc in definitions:
            continue
        meta = index[desc]
        if not isinstance(meta, dict) or meta.get("enabled") not in ENABLED_BUCKETS:
            raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_METADATA")
        if type(meta.get("split_labeled")) is not bool or type(meta.get("process_labeled")) is not bool:
            raise ProvenanceError("ACTIVITY_PROVENANCE_BAD_METADATA")
        buckets[meta["enabled"]] += 1
        split_labeled += int(meta["split_labeled"])
        process_labeled += int(meta["process_labeled"])
    return {
        "missing_count": sum(buckets.values()),
        "declared_enabled_by_missing": dict(sorted(buckets.items())),
        "split_labeled_missing_count": split_labeled,
        "process_labeled_missing_count": process_labeled,
        "runtime": "NOT_TESTED",
        "missing_classes_waived": 0,
    }
