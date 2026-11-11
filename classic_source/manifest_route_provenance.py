"""Bounded, read-only manifest route and original classpath context.

The complete OEM class/intent/alias/permission names exist only in temporary
memory on a protected runner. Only fixed enum keys and nonnegative counts leave
the function. A manifest declaration never proves an Android ClassLoader has a
class available; no missing class is waived or a build marked installable.
"""
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile

from apk_readonly_audit import parse_binary_xml, u16, u32
from activity_provenance import ANDROID, _descriptor


class RouteBlocked(ValueError):
    """Fixed, non-proprietary failure code only."""


BUCKETS = ("explicit_true", "explicit_false", "inherited", "unresolved_resource")
LIBRARY_BUCKETS = ("required_true", "required_false", "inherited", "unresolved_resource")


def _need(ok):
    if not ok:
        raise RouteBlocked("PRIVATE_MANIFEST_ROUTE_UNVERIFIED")


def _flag(value):
    if value is None:
        return "inherited"
    if value == "true":
        return "explicit_true"
    if value == "false":
        return "explicit_false"
    return "unresolved_resource"


def _library_flag(value):
    category = _flag(value)
    return ("required_true" if category == "explicit_true" else
            "required_false" if category == "explicit_false" else category)


def hierarchy_from_binary(data):
    """Reconstruct start/end nesting from pinned AXML, rejecting malformed tags.

    The existing parser interprets AXML attributes; this strictly checks START
    and END element token nesting without inventing any proprietary tags.
    """
    _need(type(data) is bytes and 8 <= len(data) <= 2_000_000)
    try:
        parsed = parse_binary_xml(data)
        elements = parsed["elements"]
        _need(type(elements) is list and 0 < len(elements) <= 10000)
        stack = []
        rows = []
        off = 8
        while off < len(data):
            _need(off + 8 <= len(data))
            kind = u16(data, off)
            length = u32(data, off + 4)
            header = u16(data, off + 2)
            _need(header >= 8 and length >= header and off + length <= len(data))
            if kind in (0x0102, 0x0103):
                _need(length >= 24 and header >= 16)
                token = u32(data, off + 20)
                if kind == 0x0102:
                    _need(len(rows) < len(elements) and len(stack) < 64)
                    node = elements[len(rows)]
                    _need(type(node) is dict and type(node.get("tag")) is str and
                          type(node.get("attributes")) is dict)
                    parent = stack[-1][1] if stack else None
                    rows.append({"tag": node["tag"], "attrs": node["attributes"],
                                 "parent": parent})
                    stack.append((token, len(rows) - 1))
                else:
                    _need(bool(stack) and stack[-1][0] == token)
                    stack.pop()
            off += length
        _need(off == len(data) and not stack and len(rows) == len(elements))
        return rows
    except RouteBlocked:
        raise
    except Exception:
        raise RouteBlocked("PRIVATE_MANIFEST_ROUTE_UNVERIFIED") from None


def classify_routes(rows, package, definitions, *, system_prefixes):
    """Aggregate *missing* Activity intent/alias exposure and graph-owner roles.

    No raw class names, attributes, filter actions or library names are
    returned. Counts are NOT permission to weaken original_prebuild_report.
    """
    _need(type(rows) is list and 0 < len(rows) <= 10000 and
          type(package) is str and bool(package) and type(definitions) is dict and
          type(system_prefixes) is tuple and len(system_prefixes) > 0)
    for i, node in enumerate(rows):
        _need(type(node) is dict and set(node) == {"tag", "attrs", "parent"} and
              type(node["tag"]) is str and type(node["attrs"]) is dict and
              (node["parent"] is None if i == 0 else
               type(node["parent"]) is int and 0 <= node["parent"] < i))
    _need(rows[0]["tag"] == "manifest" and rows[0]["parent"] is None)
    apps = [i for i, node in enumerate(rows) if node["tag"] == "application" and node["parent"] == 0]
    _need(len(apps) == 1)
    app = apps[0]
    app_flags = rows[app]["attrs"]
    activities = {}
    aliases = []
    libraries = []
    manifest_descriptors = set()
    for i, node in enumerate(rows):
        tag = node["tag"]
        if tag == "activity":
            _need(node["parent"] == app)
            try:
                desc = _descriptor(node["attrs"].get(ANDROID + "name"), package)
            except Exception:
                raise RouteBlocked("PRIVATE_MANIFEST_ROUTE_UNVERIFIED") from None
            _need(desc not in activities)
            activities[desc] = i
            manifest_descriptors.add(desc)
        elif tag in ("service", "receiver", "provider") and node["parent"] == app:
            try:
                manifest_descriptors.add(_descriptor(node["attrs"].get(ANDROID + "name"), package))
            except Exception:
                raise RouteBlocked("PRIVATE_MANIFEST_ROUTE_UNVERIFIED") from None
        elif tag == "activity-alias":
            _need(node["parent"] == app)
            aliases.append(i)
        elif tag in ("uses-library", "uses-static-library", "uses-native-library"):
            _need(node["parent"] == app)
            libraries.append(i)
    _need(bool(activities))
    missing = {name for name in activities if name not in definitions}
    filter_rows = defaultdict(list)
    for i, node in enumerate(rows):
        if node["tag"] == "intent-filter" and node["parent"] in activities.values():
            filter_rows[node["parent"]].append(i)
    child_rows = defaultdict(list)
    for i, node in enumerate(rows):
        if node["parent"] is not None:
            child_rows[node["parent"]].append(i)
    exported = Counter({k: 0 for k in BUCKETS})
    filter_activity_count = 0
    filters_total = 0
    main_launcher = 0
    data_filters = 0
    permission_count = 0
    implicit_export_with_filter = 0
    for descriptor in missing:
        i = activities[descriptor]
        attrs = rows[i]["attrs"]
        value = _flag(attrs.get(ANDROID + "exported"))
        exported[value] += 1
        filters = filter_rows[i]
        filter_activity_count += int(bool(filters))
        filters_total += len(filters)
        permission_count += int(ANDROID + "permission" in attrs)
        implicit_export_with_filter += int(bool(filters) and value == "inherited")
        for fi in filters:
            children = child_rows[fi]
            actions = {rows[n]["attrs"].get(ANDROID + "name") for n in children
                       if rows[n]["tag"] == "action"}
            categories = {rows[n]["attrs"].get(ANDROID + "name") for n in children
                          if rows[n]["tag"] == "category"}
            main_launcher += int("android.intent.action.MAIN" in actions and
                                 "android.intent.category.LAUNCHER" in categories)
            data_filters += int(any(rows[n]["tag"] == "data" for n in children))
    aliases_to_missing = 0
    for i in aliases:
        target = rows[i]["attrs"].get(ANDROID + "targetActivity")
        try:
            descriptor = _descriptor(target, package)
        except Exception:
            raise RouteBlocked("PRIVATE_MANIFEST_ROUTE_UNVERIFIED") from None
        _need(descriptor in activities)
        aliases_to_missing += int(descriptor in missing)
    library_flags = Counter({k: 0 for k in LIBRARY_BUCKETS})
    for i in libraries:
        library_flags[_library_flag(rows[i]["attrs"].get(ANDROID + "required"))] += 1
    gaps = set()
    missing_parent_types = set()
    owners = set()
    for klass, parents in definitions.items():
        _need(type(klass) is str and isinstance(parents, (list, tuple)))
        for parent in parents:
            _need(type(parent) is str)
            if parent not in definitions and not parent.startswith(system_prefixes):
                gaps.add((klass, parent))
                owners.add(klass)
                missing_parent_types.add(parent)
    missing_owner_declared = sum(1 for owner in owners if owner in manifest_descriptors)
    return {
        "missing_activity_count": len(missing),
        "missing_exported_buckets": dict(sorted(exported.items())),
        "missing_with_intent_filter": filter_activity_count,
        "missing_intent_filter_total": filters_total,
        "missing_main_launcher_filters": main_launcher,
        "missing_data_filters": data_filters,
        "missing_explicit_permission": permission_count,
        "missing_implicit_exported_with_filter": implicit_export_with_filter,
        "alias_targets_missing_activity": aliases_to_missing,
        "application_enabled_bucket": _flag(app_flags.get(ANDROID + "enabled")),
        "application_has_code_bucket": _flag(app_flags.get(ANDROID + "hasCode")),
        "declared_library_count": len(libraries),
        "library_required_buckets": dict(sorted(library_flags.items())),
        "unresolved_parent_edge_count": len(gaps),
        "unresolved_parent_distinct_types": len(missing_parent_types),
        "unresolved_owner_distinct_types": len(owners),
        "unresolved_owner_in_manifest": missing_owner_declared,
        "runtime_verified": False,
        "missing_classes_waived": 0,
    }


def inspect_original_routes(path):
    """Privately reparse one already SHA-verified original; NEVER print identifiers."""
    try:
        # Recheck original inside the protected runner; no user path is output.
        from private_startup_audit import inspect_apk, SYSTEM
        pinned = inspect_apk(path)
        with ZipFile(path) as archive:
            meta = archive.getinfo("AndroidManifest.xml")
            _need(0 < meta.file_size <= 2_000_000)
            contents = archive.read(meta)
        rows = hierarchy_from_binary(contents)
        result = classify_routes(rows, pinned["package"], pinned["definitions"],
                                 system_prefixes=SYSTEM)
        return result
    except RouteBlocked:
        raise
    except Exception:
        raise RouteBlocked("PRIVATE_MANIFEST_ROUTE_UNVERIFIED") from None
