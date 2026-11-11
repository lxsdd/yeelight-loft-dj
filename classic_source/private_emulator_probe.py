#!/usr/bin/env python3
"""Strictly private, owner-dispatched *runtime diagnostic* of the unqualified
OEM Classic baseline. Never reports classpath resolution or release acceptance.

The normal private original-only and original/candidate audit gates stay
UNCHANGED and blocking. This module merely allows a byte-preserving candidate
with EXACTLY the already-observed original gaps to reach a quarantined,
offline emulator. It does not authorize distribution or a successful product.
"""
from __future__ import annotations
import argparse
import json
from collections import Counter
from pathlib import Path
import sys

import private_startup_audit as audit

# These immutable observed counts are not waivers. Any difference aborts the
# isolated probe until independent new original evidence is reviewed.
PINNED_MISSING_ACTIVITIES = 3
PINNED_UNRESOLVED_PARENT_EDGES = 6
PINNED_SHARED_LIBRARY_DECLARATIONS = 1


class ProbeBlocked(ValueError):
    pass


def require(condition: bool, reason: str):
    if not condition:
        raise ProbeBlocked(reason)


def baseline_contract(o: dict) -> dict:
    """Only an original which exactly matches the known broken OEM baseline.

    No class is considered loadable because the unchanged stock app launched.
    """
    require(o.get('package') == audit.OLD and o.get('dex_count') == 2,
            'ORIGINAL_PACKAGE_OR_DEX_DRIFT')
    activities = [name for role, name in o['components'] if role == 'activity']
    providers = [name for role, name in o['components'] if role == 'provider']
    require(len(activities) == 185 and len(providers) == 7 and
            set(audit.REMOVED).issubset(providers), 'ORIGINAL_COMPONENT_DRIFT')
    require(o.get('declared_shared_libraries') == PINNED_SHARED_LIBRARY_DECLARATIONS,
            'ORIGINAL_LIBRARY_DECLARATION_DRIFT')
    roles = audit.classify_manifest_references(o, exempt_providers=audit.REMOVED)
    require(roles['undeclared_alias_targets'] == 0 and
            roles['missing_by_role'] == {'activity': PINNED_MISSING_ACTIVITIES} and
            roles['missing_required'] == PINNED_MISSING_ACTIVITIES and
            roles['missing_supporting'] == 0 and
            roles['exempted_removed_providers'] == len(audit.REMOVED),
            'ORIGINAL_REFERENCE_GAPS_DRIFT')
    missing = audit.unresolved_edges(o['definitions'])
    require(len(missing) == PINNED_UNRESOLVED_PARENT_EDGES,
            'ORIGINAL_PARENT_GAPS_DRIFT')
    # Only present startup classes can be probed; missing activity declarations
    # remain unresolved and must NOT be silently treated as executable.
    roots = roles['resolved_startup_roots']
    startup_missing = set()
    try:
        audit.reachable(dict(o, entrypoints=roots), missing_parents=startup_missing)
    except audit.AuditBlocked as exc:
        raise ProbeBlocked("ORIGINAL_STARTUP_GRAPH_UNVERIFIED") from exc
    require(not startup_missing, 'ORIGINAL_STARTUP_PARENT_GAP')
    return {
        'status': 'ORIGINAL_STATIC_GAPS_UNRESOLVED_RUNTIME_PROBE_ONLY',
        'missing_activity_count': PINNED_MISSING_ACTIVITIES,
        'missing_parent_edge_count': PINNED_UNRESOLVED_PARENT_EDGES,
        'missing_classes_waived': 0,
        'runtime': 'NOT_TESTED',
        'classloader': 'UNVERIFIED',
        'publishable': False,
    }


def pair_contract(o: dict, c: dict) -> dict:
    """Exact OEM inheritance and manifest parity except approved changes.

    A new unresolved OEM/DJ edge, missing startup provider, or changed OEM
    class definition is fatal. This does NOT turn the original gaps into PASS.
    """
    original = baseline_contract(o)
    require(c.get('package') == audit.NEW and c.get('dex_count') == 3,
            'CANDIDATE_PACKAGE_OR_DEX_DRIFT')
    expected = o['components'].copy()
    for name in audit.REMOVED:
        del expected[('provider', name)]
    for item in audit.ADDED:
        expected[item] += 1
    require(expected == c['components'], 'CANDIDATE_COMPONENT_DRIFT')
    removed = {'L' + name.replace('.', '/') + ';' for name in audit.REMOVED}
    added = {'L' + name.replace('.', '/') + ';' for _, name in audit.ADDED}
    require(o['entrypoints'] - c['entrypoints'] == removed and
            c['entrypoints'] - o['entrypoints'] == added,
            'CANDIDATE_ENTRYPOINT_DRIFT')
    expected_roles = o['manifest_references'].copy()
    for name in audit.REMOVED:
        key = ('provider', 'L' + name.replace('.', '/') + ';')
        require(expected_roles.get(key) == 1, 'ORIGINAL_REMOVED_PROVIDER_ROLE_DRIFT')
        del expected_roles[key]
    for role, name in audit.ADDED:
        expected_roles[(role, 'L' + name.replace('.', '/') + ';')] += 1
    require(expected_roles == c['manifest_references'],
            'CANDIDATE_MANIFEST_ROLES_DRIFT')
    require(c.get('declared_shared_libraries') ==
            o.get('declared_shared_libraries'),
            'CANDIDATE_SHARED_LIBRARY_DRIFT')
    for clazz, parents in o['definitions'].items():
        require(c['definitions'].get(clazz) == parents, 'CANDIDATE_OEM_GRAPH_DRIFT')
    new_classes = set(c['definitions']) - set(o['definitions'])
    require(added.issubset(new_classes), 'CANDIDATE_BRIDGE_MISSING')
    require(not audit.unresolved_edges(c['definitions'], new_classes),
            'CANDIDATE_NEW_CLASS_PARENT_UNVERIFIED')
    # The DJ entrypoints must be reachable with a completely closed parent
    # graph. This is separate from the unqualified original non-DJ gaps.
    audit.reachable(dict(c, entrypoints=added))
    old_roles = audit.classify_manifest_references(o, exempt_providers=audit.REMOVED)
    clone_roles = audit.classify_manifest_references(c)
    require(old_roles['missing_by_role'] == clone_roles['missing_by_role'] and
            clone_roles['missing_required'] == PINNED_MISSING_ACTIVITIES and
            clone_roles['missing_supporting'] == 0 and
            clone_roles['undeclared_alias_targets'] == 0,
            'CANDIDATE_EXTRA_REFERENCE_GAPS')
    require(audit.unresolved_edges(c['definitions']) ==
            audit.unresolved_edges(o['definitions']),
            'CANDIDATE_PARENT_EDGE_DRIFT')
    # Pre-startup roots Application/Provider/Service/Receiver may not carry
    # either missing class or missing parent, even if unrelated Activities do.
    startup_roles = {'application', 'provider', 'service', 'receiver', 'appComponentFactory'}
    startup_entries = {desc for (role, desc), count in c['manifest_references'].items()
                       if role in startup_roles and count and
                       not desc.startswith(audit.FRAMEWORK_MANIFEST)}
    require(startup_entries.issubset(c['definitions']),
            'CANDIDATE_EARLY_COMPONENT_CLASS_MISSING')
    audit.reachable(dict(c, entrypoints=startup_entries))
    return {**original,
            'status': 'CANDIDATE_STATIC_PARITY_PROBE_ONLY_NOT_PRODUCT_QUALIFIED',
            'candidate_dex_count': 3,
            'candidate_added_bridge_entries': len(added)}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--original', required=True, type=Path)
    p.add_argument('--candidate', type=Path)
    args = p.parse_args(argv)
    try:
        # SHA-256 pinned real private original; never print proprietary paths,
        # names, class descriptors, DEX or raw APK data.
        audit.inspect_original(args.original)
        o = audit.inspect_apk(args.original)
        result = (pair_contract(o, audit.inspect_apk(args.candidate))
                  if args.candidate else baseline_contract(o))
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ProbeBlocked, audit.AuditBlocked, ValueError, OSError, KeyError,
            TypeError, RuntimeError) as exc:
        reason = str(exc) if isinstance(exc, ProbeBlocked) else 'PRIVATE_PROBE_INPUT_INVALID'
        print('PRIVATE_EMULATOR_PROBE_BLOCKED_' + reason, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
