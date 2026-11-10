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


# Supplemental classpath evidence. This is a classification only, not a waiver.
KINDS = ("split_apk", "shared_library", "platform_classpath")
STATUS = "ORIGINAL_CLASSPATH_PROVENANCE_UNVERIFIED"
RUNTIME = "NOT_TESTED"


class ClasspathProvenanceError(ValueError):
    """Raises only fixed diagnostic codes; no private descriptor leaks."""


def _check_desc(s):
    return (isinstance(s, str) and len(s) >= 3 and s[0] == "L"
            and s[-1] == ";" and "\n" not in s and " " not in s)


def _check_graph(graph):
    if not isinstance(graph, dict):
        raise ClasspathProvenanceError("CLASSPATH_INVALID_GRAPH")
    for owner, parents in graph.items():
        if (not _check_desc(owner) or not isinstance(parents, (list, tuple))
                or any(not _check_desc(parent) for parent in parents)):
            raise ClasspathProvenanceError("CLASSPATH_INVALID_GRAPH")


def _check_set(items, code):
    if not isinstance(items, set) or any(not _check_desc(x) for x in items):
        raise ClasspathProvenanceError(code)


def classify_original_classpath(base_definitions, declared_activity_descriptors,
                                *, system_prefixes, supplemental_sources=()):
    """Return aggregate evidence only; never authorize the OEM build gate.

    supplemental_sources items: {kind: one of KINDS, definitions: graph}.
    They are *inventoried candidates*, even if their APK/hash was verified.
    Valid loader provenance, install-time split selection, and ART execution
    require separately attested evidence. No absence of a manifest splitName
    or presence of uses-library can count as a found class.
    """
    _check_graph(base_definitions)
    _check_set(declared_activity_descriptors, "CLASSPATH_INVALID_ACTIVITIES")
    if (not isinstance(system_prefixes, tuple) or not system_prefixes
            or any(not isinstance(s, str) or not s.startswith("L")
                   or not s.endswith("/") or len(s) < 6 for s in system_prefixes)):
        raise ClasspathProvenanceError("CLASSPATH_INVALID_SYSTEM_PREFIXES")
    if (not isinstance(supplemental_sources, (tuple, list))
            or len(supplemental_sources) > 128):
        raise ClasspathProvenanceError("CLASSPATH_INVALID_SOURCES")

    base_keys = set(base_definitions)
    missing_activities = declared_activity_descriptors - base_keys
    # An original APK can have private class references that are not defined
    # locally. Count *all* direct superclass/interface gaps, including dormant
    # classes, rather than only the manifest's reachable startup roots.
    missing_edges = {(owner, parent)
                     for owner, parents in base_definitions.items()
                     for parent in parents
                     if parent not in base_keys and
                     not parent.startswith(system_prefixes)}

    seen = set(base_keys)
    owners = {}
    source_counts = Counter({kind: 0 for kind in KINDS})
    for source in supplemental_sources:
        if (not isinstance(source, dict) or set(source) != {"kind", "definitions"}
                or source.get("kind") not in KINDS):
            raise ClasspathProvenanceError("CLASSPATH_INVALID_SOURCE_RECORD")
        graph = source["definitions"]
        _check_graph(graph)
        if seen.intersection(graph):
            raise ClasspathProvenanceError("CLASSPATH_DUPLICATE_DEFINITION")
        source_counts[source["kind"]] += 1
        seen.update(graph)
        for desc in graph:
            owners[desc] = source["kind"]

    # Source class presence is only candidate evidence; a split might not be
    # installed, a library might not be loaded or a platform type might be
    # absent on the target Android runtime. Keep the existing hard gate.
    activity_counts = Counter({kind: 0 for kind in KINDS})
    edge_counts = Counter({kind: 0 for kind in KINDS})
    activity_unknown = 0
    edge_unknown = 0
    for desc in missing_activities:
        kind = owners.get(desc)
        if kind is None:
            activity_unknown += 1
        else:
            activity_counts[kind] += 1
    for _, parent in missing_edges:
        kind = owners.get(parent)
        if kind is None:
            edge_unknown += 1
        else:
            edge_counts[kind] += 1

    # Also ensure every candidate's own direct graph is locally closed or
    # transparently counted. This catches split/library stubs which appear to
    # resolve a manifest class but immediately depend on absent types.
    candidate_edge_gaps = sum(
        parent not in seen and not parent.startswith(system_prefixes)
        for source in supplemental_sources
        for parents in source["definitions"].values()
        for parent in parents
    )
    return {
        "status": STATUS,
        "runtime": RUNTIME,
        "candidate_sources_by_kind": dict(sorted(source_counts.items())),
        "original_missing_activity_count": len(missing_activities),
        "original_unresolved_direct_parent_edges": len(missing_edges),
        "activity_candidates_by_kind": dict(sorted(activity_counts.items())),
        "parent_edge_candidates_by_kind": dict(sorted(edge_counts.items())),
        "activities_without_candidate": activity_unknown,
        "parent_edges_without_candidate": edge_unknown,
        "candidate_graph_unresolved_parent_edges": candidate_edge_gaps,
        "installed_split_set_verified": False,
        "actual_classloader_verified": False,
        "missing_classes_waived": 0,
    }


def validate_diagnostic_report(report, *, expected_missing_activities,
                               expected_unresolved_edges):
    """Reject corrupted/relabelled candidate evidence; NEVER grant build PASS.

    A pinned candidate can reduce the 'without candidate' counters but cannot
    lower the two original manifest/graph gap counts. Validation does not
    adjudicate whether a split or library is installed, loadable or licensed.
    """
    if (not isinstance(report, dict) or
            type(report.get("original_missing_activity_count")) is not int or
            type(report.get("original_unresolved_direct_parent_edges")) is not int or
            report.get("status") != STATUS or
            report.get("runtime") != RUNTIME or
            type(expected_missing_activities) is not int or
            type(expected_unresolved_edges) is not int or
            expected_missing_activities < 0 or expected_unresolved_edges < 0 or
            report.get("original_missing_activity_count") != expected_missing_activities or
            report.get("original_unresolved_direct_parent_edges") != expected_unresolved_edges or
            report.get("installed_split_set_verified") is not False or
            report.get("actual_classloader_verified") is not False or
            report.get("missing_classes_waived") != 0):
        raise ClasspathProvenanceError("CLASSPATH_REPORT_INVALID")
    sources = report.get("candidate_sources_by_kind")
    activity = report.get("activity_candidates_by_kind")
    parents = report.get("parent_edge_candidates_by_kind")
    if any(not isinstance(m, dict) or set(m) != set(KINDS) or
           any(type(x) is not int or x < 0 for x in m.values())
           for m in (sources, activity, parents)):
        raise ClasspathProvenanceError("CLASSPATH_REPORT_INVALID")
    without_activity = report.get("activities_without_candidate")
    without_parents = report.get("parent_edges_without_candidate")
    orphaned_candidate_edges = report.get("candidate_graph_unresolved_parent_edges")
    if (any(type(x) is not int or x < 0 for x in
            (without_activity, without_parents, orphaned_candidate_edges)) or
            sum(activity.values()) + without_activity != expected_missing_activities or
            sum(parents.values()) + without_parents != expected_unresolved_edges or
            (sum(sources.values()) == 0 and
             (sum(activity.values()) != 0 or sum(parents.values()) != 0 or
              orphaned_candidate_edges != 0))):
        raise ClasspathProvenanceError("CLASSPATH_REPORT_INVALID")
    return True
