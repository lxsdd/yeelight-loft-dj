"""Bounded, private-memory DEX xrefs for the pinned original Classic archive.

Only aggregate counters may leave the protected runner. A DEX callsite does NOT
prove executed code, a missing class is safe, a manifest component is dead, or
a runtime ClassLoader has a particular external/shared/split classpath.
"""
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile

from class_source_inventory import DEX_RE

MAX_DEX_BYTES = 48 * 1024 * 1024
MAX_CODE_UNITS = 8_000_000
MAX_ITEMS = 1_000_000


class InvokeProvenanceBlocked(ValueError):
    """Fixed diagnostic failure; never include an OEM symbol or path."""


def need(condition):
    if not condition:
        raise InvokeProvenanceBlocked("PRIVATE_DEX_INVOKE_PROVENANCE_UNVERIFIED")


def number(buf, offset, width):
    need(type(offset) is int and 0 <= offset and width in (1, 2, 4) and
         offset + width <= len(buf))
    return int.from_bytes(buf[offset:offset + width], "little")


def section(buf, count_off, data_off, item_size, *, limit=MAX_ITEMS):
    count = number(buf, count_off, 4)
    offset = number(buf, data_off, 4)
    need(count <= limit and (count == 0 or offset >= 112) and
         offset + count * item_size <= len(buf))
    return count, offset


def uleb(buf, offset):
    value = 0
    for j in range(5):
        byte = number(buf, offset + j, 1)
        value |= (byte & 0x7f) << (7 * j)
        if not byte & 0x80:
            need(j < 4 or byte <= 15)
            return value, offset + j + 1
    raise InvokeProvenanceBlocked("PRIVATE_DEX_INVOKE_PROVENANCE_UNVERIFIED")


def dex_types(buf):
    """Read only type descriptors; never return private strings to JSON."""
    need(type(buf) is bytes and 112 <= len(buf) <= MAX_DEX_BYTES and
         buf[:4] == b"dex\n" and buf[7] == 0 and
         number(buf, 32, 4) == len(buf))
    strings, so = section(buf, 56, 60, 4)
    types, to = section(buf, 64, 68, 4)
    result = []
    for i in range(types):
        si = number(buf, to + i * 4, 4)
        need(si < strings)
        position = number(buf, so + si * 4, 4)
        _, start = uleb(buf, position)
        end = buf.find(b"\0", start, min(len(buf), start + 1025))
        need(end > start)
        try:
            desc = buf[start:end].decode("ascii")
        except UnicodeError:
            raise InvokeProvenanceBlocked("PRIVATE_DEX_INVOKE_PROVENANCE_UNVERIFIED") from None
        need((desc.startswith("L") and desc.endswith(";")) or
             (desc.startswith("[") and len(desc) <= 1024) or
             desc in ("V", "Z", "B", "S", "C", "I", "J", "F", "D"))
        result.append(desc)
    return result


WIDTH = [0] * 256
for op in range(0x00, 0x3e):
    WIDTH[op] = 1
for op in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a,
           0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29):
    WIDTH[op] = 2
for op in (0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25,
           0x26, 0x2a, 0x2b, 0x2c):
    WIDTH[op] = 3
WIDTH[0x18] = 5
for lo, hi, width in (
    (0x2d, 0x3d, 2), (0x44, 0x6d, 2), (0x6e, 0x72, 3),
    (0x74, 0x78, 3), (0x7b, 0x8f, 1), (0x90, 0xaf, 2),
    (0xb0, 0xcf, 1), (0xd0, 0xe2, 2),
):
    for op in range(lo, hi + 1):
        WIDTH[op] = width
for op, width in ((0xfa, 4), (0xfb, 4), (0xfc, 3), (0xfd, 3),
                  (0xfe, 2), (0xff, 2)):
    WIDTH[op] = width
TYPE_OPS = frozenset((0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25))
METHOD_OPS = frozenset((*range(0x6e, 0x73), *range(0x74, 0x79), 0xfa, 0xfb))
FIELD_OPS = frozenset(range(0x52, 0x6e))


def instructions(buf, offset, size):
    """Walk exact DEX code-unit widths, including all three payload formats.

    Unknown/reserved instructions FAIL CLOSED, never treated as no callsites.
    """
    need(size <= MAX_CODE_UNITS and 0 <= offset and offset + size * 2 <= len(buf))
    words = [number(buf, offset + i * 2, 2) for i in range(size)]
    pos = 0
    while pos < size:
        op = words[pos] & 255
        width = WIDTH[op]
        if op == 0 and words[pos] >> 8:
            tag = words[pos] >> 8
            need(tag in (1, 2, 3) and pos + 4 <= size)
            count = words[pos + 1]
            if tag == 1:
                width = 4 + 2 * count
            elif tag == 2:
                width = 2 + 4 * count
            else:
                bytes_per_element = words[pos + 1]
                elements = words[pos + 2] | (words[pos + 3] << 16)
                need(0 < bytes_per_element <= 8 and
                     bytes_per_element * elements <= MAX_CODE_UNITS * 2)
                width = 4 + (bytes_per_element * elements + 1) // 2
        need(width > 0 and pos + width <= size)
        yield op, words[pos + 1] if width >= 2 else None
        pos += width


def scan_dex(raw, missing_activities, missing_parents, definitions):
    """Validate all indices and scan actual code_item bodies for typed operands."""
    from private_startup_audit import dex_index
    try:
        dex_index(raw)  # second-reader, DEX SHA1 and Adler32 integrity gate
    except Exception:
        raise InvokeProvenanceBlocked("PRIVATE_DEX_INVOKE_PROVENANCE_UNVERIFIED") from None
    types = dex_types(raw)
    fields, fo = section(raw, 80, 84, 8)
    methods, mo = section(raw, 88, 92, 8)
    protos, _ = section(raw, 72, 76, 12)
    strings, _ = section(raw, 56, 60, 4)
    classes, co = section(raw, 96, 100, 32)
    field_owners = []
    method_owners = []
    for i in range(fields):
        off = fo + i * 8
        owner = number(raw, off, 2)
        field_type = number(raw, off + 2, 2)
        name = number(raw, off + 4, 4)
        need(owner < len(types) and field_type < len(types) and name < strings)
        field_owners.append(types[owner])
    for i in range(methods):
        off = mo + i * 8
        owner = number(raw, off, 2)
        proto = number(raw, off + 2, 2)
        name = number(raw, off + 4, 4)
        need(owner < len(types) and proto < protos and name < strings)
        method_owners.append(types[owner])
    hits = {
        key: set() for key in (
            "activity_type_ids", "parent_type_ids",
            "activity_method_id_owners", "parent_method_id_owners",
            "activity_field_id_owners", "parent_field_id_owners",
            "activity_invoke_targets", "parent_invoke_targets",
            "activity_type_instruction_targets", "parent_type_instruction_targets",
            "activity_field_access_owners", "parent_field_access_owners",
        )
    }
    hits["activity_type_ids"].update(set(types) & missing_activities)
    hits["parent_type_ids"].update(set(types) & missing_parents)
    hits["activity_method_id_owners"].update(set(method_owners) & missing_activities)
    hits["parent_method_id_owners"].update(set(method_owners) & missing_parents)
    hits["activity_field_id_owners"].update(set(field_owners) & missing_activities)
    hits["parent_field_id_owners"].update(set(field_owners) & missing_parents)
    calls = defaultdict(set)
    counts = Counter({"code_items": 0, "instructions": 0, "dynamic_invoke_sites": 0,
                      "missing_activity_invoke_sites": 0, "missing_parent_invoke_sites": 0})
    for i in range(classes):
        off = co + i * 32
        type_index = number(raw, off, 4)
        need(type_index < len(types))
        owner = types[type_index]
        class_data = number(raw, off + 24, 4)
        if not class_data:
            continue
        pos = class_data
        field_static, pos = uleb(raw, pos)
        field_instance, pos = uleb(raw, pos)
        method_direct, pos = uleb(raw, pos)
        method_virtual, pos = uleb(raw, pos)
        need(field_static + field_instance <= fields and
             method_direct + method_virtual <= methods)
        for count, total in ((field_static, fields), (field_instance, fields)):
            index = 0
            for _ in range(count):
                delta, pos = uleb(raw, pos)
                index += delta
                _, pos = uleb(raw, pos)  # access flags
                need(index < total)
        for count in (method_direct, method_virtual):
            index = 0
            for _ in range(count):
                delta, pos = uleb(raw, pos)
                index += delta
                _, pos = uleb(raw, pos)  # access flags
                code_off, pos = uleb(raw, pos)
                need(index < methods and method_owners[index] == owner)
                if not code_off:
                    continue
                need(code_off % 4 == 0 and code_off + 16 <= len(raw))
                units = number(raw, code_off + 12, 4)
                counts["code_items"] += 1
                need(counts["code_items"] <= MAX_ITEMS)
                for opcode, ref in instructions(raw, code_off + 16, units):
                    counts["instructions"] += 1
                    need(counts["instructions"] <= MAX_CODE_UNITS)
                    if opcode in TYPE_OPS:
                        need(ref < len(types))
                        target = types[ref]
                        if target in missing_activities:
                            hits["activity_type_instruction_targets"].add(target)
                        if target in missing_parents:
                            hits["parent_type_instruction_targets"].add(target)
                    elif opcode in METHOD_OPS:
                        need(ref < methods)
                        target = method_owners[ref]
                        if target in definitions:
                            calls[owner].add(target)
                        if target in missing_activities:
                            hits["activity_invoke_targets"].add(target)
                            counts["missing_activity_invoke_sites"] += 1
                        if target in missing_parents:
                            hits["parent_invoke_targets"].add(target)
                            counts["missing_parent_invoke_sites"] += 1
                    elif opcode in FIELD_OPS:
                        need(ref < fields)
                        target = field_owners[ref]
                        if target in missing_activities:
                            hits["activity_field_access_owners"].add(target)
                        if target in missing_parents:
                            hits["parent_field_access_owners"].add(target)
                    elif opcode in (0xfc, 0xfd):
                        counts["dynamic_invoke_sites"] += 1
    return hits, calls, counts


def inspect_original_invokes(path):
    """Runs only AFTER the protected Vault+receipt diagnostic has passed."""
    try:
        from private_startup_audit import inspect_apk, SYSTEM
        source = inspect_apk(Path(path))
        definitions = source["definitions"]
        declared = {desc for (role, desc), count in source["manifest_references"].items()
                    if role == "activity" and count > 0}
        missing_activities = declared - set(definitions)
        gaps = {(owner, parent) for owner, parents in definitions.items()
                for parent in parents if parent not in definitions
                and not parent.startswith(SYSTEM)}
        missing_parents = {parent for _, parent in gaps}
        manifest_roots = {desc for (role, desc), count in source["manifest_references"].items()
                          if role not in ("activity-alias", "targetActivity")
                          and count > 0 and desc in definitions}
        counts = Counter()
        all_hits = defaultdict(set)
        graph = defaultdict(set)
        with ZipFile(Path(path)) as archive:
            members = archive.infolist()
            names = [m.filename for m in members]
            need(len(names) == len(set(names)))
            dexes = [m for m in members if DEX_RE.fullmatch(m.filename)]
            need(len(dexes) == source["dex_count"] and 0 < len(dexes) <= 24)
            for item in dexes:
                need(0 < item.file_size <= MAX_DEX_BYTES)
                with archive.open(item, "r") as reader:
                    raw = reader.read(MAX_DEX_BYTES + 1)
                need(len(raw) == item.file_size)
                hits, calls, observed = scan_dex(raw, missing_activities,
                                                  missing_parents, definitions)
                for label, values in hits.items():
                    all_hits[label].update(values)
                for owner, targets in calls.items():
                    graph[owner].update(targets)
                counts.update(observed)
        reached = manifest_roots.copy()
        frontier = list(reached)
        while frontier:
            klass = frontier.pop()
            for target in graph.get(klass, ()):
                if target not in reached:
                    reached.add(target)
                    frontier.append(target)
            need(len(reached) <= len(definitions))
        parent_owners = {owner for owner, _ in gaps}
        return {
            **{name + "_count": len(values) for name, values in all_hits.items()},
            "unresolved_parent_gap_owner_manifest_call_reached_count":
                len(parent_owners & reached),
            "code_items_scanned": counts["code_items"],
            "instructions_scanned": counts["instructions"],
            "dynamic_invoke_sites": counts["dynamic_invoke_sites"],
            "missing_activity_invoke_sites": counts["missing_activity_invoke_sites"],
            "missing_parent_invoke_sites": counts["missing_parent_invoke_sites"],
            "dex_invoke_inventory_complete": True,
            "dynamic_and_reflective_reachability_proven": False,
            "absent_callsites_prove_dead_code": False,
            "actual_classloader_verified": False,
            "missing_classes_waived": 0,
        }
    except InvokeProvenanceBlocked:
        raise
    except Exception:
        raise InvokeProvenanceBlocked("PRIVATE_DEX_INVOKE_PROVENANCE_UNVERIFIED") from None
