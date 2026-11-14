#!/usr/bin/env python3
"""Trusted-main, owner-dispatched, source-only preflight for Classic PR APK QA.

Run this file from the immutable protected main checkout *before* checking out
a candidate SHA and before exposing CLASSIC_VAULT_TOKEN or the private OEM APK.
This is NOT a security grant to arbitrary PR source or an automatic trigger.
Only fixed public validation labels and a pinned hex commit are emitted.
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO = "lxsdd/yeelight-loft-dj"
OWNER = "lxsdd"
SHA = re.compile(r"[a-f0-9]{40}\Z")
MAX_PR = 1000000
MAX_RUNS = 100
MAX_MAIN_ADVANCE = 8
MAX_FILES = 100
MAX_BYTES = 4_000_000
EXPECTED = {
    "Classic source-only CI": "source-verify",
    "Android CI": "verify-and-build",
    "Classic PUBLIC Android17 emulator cold boot (no OEM)": "android17-no-oem-boot",
}


class CandidateBlocked(ValueError):
    """Only fixed public status codes, never GitHub payloads or secrets."""


class CompareDiffTimeout(CandidateBlocked):
    """Exact SHA-pinned GitHub Comparison/diff/not_available timeout."""


def require(ok, code):
    if not ok:
        raise CandidateBlocked(code)


def parse_inputs(pr_input, sha_input, workflow_sha):
    """No implicit PR HEAD lookup: user must pin both PR identity and SHA."""
    require(isinstance(workflow_sha, str) and SHA.fullmatch(workflow_sha),
            "TRUSTED_MAIN_SHA_INVALID")
    require(isinstance(pr_input, str) and isinstance(sha_input, str),
            "CANDIDATE_INPUT_INVALID")
    if pr_input == "0" and sha_input == "":
        return (0, workflow_sha)
    require(re.fullmatch(r"[1-9][0-9]{0,5}", pr_input) is not None and
            int(pr_input) <= MAX_PR, "CANDIDATE_PR_INVALID")
    require(SHA.fullmatch(sha_input) is not None and sha_input != workflow_sha,
            "CANDIDATE_SHA_INVALID")
    return (int(pr_input), sha_input)


def _api(path, token):
    """Only fixed github.com REST resources derived from validated tokens."""
    require(isinstance(path, str) and path.startswith("/repos/" + REPO + "/"),
            "GITHUB_API_PATH_DENIED")
    require(isinstance(token, str) and len(token) > 10,
            "PUBLIC_API_READ_TOKEN_REQUIRED")
    request = urllib.request.Request(
        "https://api.github.com" + path,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + token,
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "Yeelight-Classic-Owner-Only-Candidate-Gate",
        })
    try:
        with urllib.request.urlopen(request, timeout=18) as response:
            data = response.read(MAX_BYTES + 1)
            require(len(data) <= MAX_BYTES, "PUBLIC_API_RESPONSE_TOO_LARGE")
            return json.loads(data)
    except urllib.error.HTTPError as error:
        # Only this exact server-side GitHub comparison failure can trigger
        # the independent immutable-object proof. Never accept arbitrary 422s.
        try:
            detail = json.loads(error.read(16385))
            recoverable = (
                error.code == 422 and
                re.fullmatch(r"/repos/" + re.escape(REPO) +
                             r"/compare/[a-f0-9]{40}\.\.\.[a-f0-9]{40}",
                             path) is not None and
                isinstance(detail, dict) and
                detail.get("message") ==
                    "Server Error: Sorry, this diff is taking too long to generate." and
                isinstance(detail.get("errors"), list) and
                any(isinstance(row, dict) and
                    row.get("resource") == "Comparison" and
                    row.get("field") == "diff" and
                    row.get("code") == "not_available"
                    for row in detail["errors"])
            )
        except (ValueError, UnicodeError, OSError):
            recoverable = False
        if recoverable:
            raise CompareDiffTimeout("GITHUB_COMPARE_DIFF_TIMEOUT") from None
        raise CandidateBlocked("PUBLIC_GITHUB_EVIDENCE_UNAVAILABLE") from None
    except (urllib.error.URLError, OSError, ValueError,
            UnicodeError, TimeoutError):
        raise CandidateBlocked("PUBLIC_GITHUB_EVIDENCE_UNAVAILABLE") from None



MAX_PROOF_COMMITS = 512
MAX_PROOF_TREE_ENTRIES = 20000


def _git_comparison_from_objects(main_sha, candidate_sha, api, token):
    """Reconstruct the existing compare contract, without relaxing validation.

    Request each commit and full tree by immutable SHA, enforce linear ancestry
    and exact owner provenance, and independently compute every changed path.
    Merge ambiguity, incomplete trees, chmod, symlinks and unknown paths deny.
    """
    cache = {}

    def get_commit(value):
        require(isinstance(value, str) and SHA.fullmatch(value),
                "PR_GIT_OBJECT_UNVERIFIED")
        if value not in cache:
            obj = api("/repos/" + REPO + "/commits/" + value, token)
            require(isinstance(obj, dict) and obj.get("sha") == value and
                    isinstance(obj.get("parents"), list) and
                    isinstance(obj.get("commit"), dict) and
                    isinstance(obj["commit"].get("tree"), dict) and
                    isinstance(obj["commit"]["tree"].get("sha"), str) and
                    SHA.fullmatch(obj["commit"]["tree"]["sha"]),
                    "PR_GIT_OBJECT_UNVERIFIED")
            require(len(obj["parents"]) <= 1 and all(
                isinstance(p, dict) and isinstance(p.get("sha"), str) and
                SHA.fullmatch(p["sha"]) for p in obj["parents"]),
                "PR_GIT_ANCESTRY_AMBIGUOUS")
            cache[value] = obj
        return cache[value]

    main_path = [main_sha]
    for _ in range(MAX_MAIN_ADVANCE):
        parents = get_commit(main_path[-1])["parents"]
        if not parents:
            break
        value = parents[0]["sha"]
        require(value not in main_path, "PR_GIT_ANCESTRY_AMBIGUOUS")
        main_path.append(value)
    main_positions = {value: i for i, value in enumerate(main_path)}
    commits = []
    seen = set()
    value = candidate_sha
    while value not in main_positions:
        require(value not in seen and len(commits) < MAX_PROOF_COMMITS,
                "PR_GIT_ANCESTRY_AMBIGUOUS")
        seen.add(value)
        obj = get_commit(value)
        require(len(obj["parents"]) == 1, "PR_GIT_ANCESTRY_UNQUALIFIED")
        require(isinstance(obj.get("author"), dict) and
                obj["author"].get("login") == OWNER and
                isinstance(obj.get("committer"), dict) and
                obj["committer"].get("login") == OWNER,
                "PR_COMMIT_PROVENANCE_DENIED")
        commits.append({"sha": value, "author": {"login": OWNER},
                        "committer": {"login": OWNER}})
        value = obj["parents"][0]["sha"]
    require(bool(commits), "PR_GIT_ANCESTRY_UNQUALIFIED")
    common = value
    behind = main_positions[common]
    ahead = len(commits)

    def leaves(revision):
        tree_sha = get_commit(revision)["commit"]["tree"]["sha"]
        obj = api("/repos/" + REPO + "/git/trees/" + tree_sha +
                  "?recursive=1", token)
        require(isinstance(obj, dict) and obj.get("sha") == tree_sha and
                obj.get("truncated") is False and
                isinstance(obj.get("tree"), list) and
                len(obj["tree"]) <= MAX_PROOF_TREE_ENTRIES,
                "PR_GIT_TREE_INCOMPLETE")
        result = {}
        seen_paths = set()
        for row in obj["tree"]:
            require(isinstance(row, dict) and
                    isinstance(row.get("path"), str) and row["path"] and
                    not row["path"].startswith("/") and
                    not row["path"].endswith("/") and
                    "\\" not in row["path"] and
                    all(p not in ("", ".", "..") for p in
                        row["path"].split("/")) and
                    row["path"] not in seen_paths and
                    isinstance(row.get("sha"), str) and
                    SHA.fullmatch(row["sha"]),
                    "PR_GIT_TREE_INCOMPLETE")
            path = row["path"]
            seen_paths.add(path)
            if row.get("type") == "tree":
                require(row.get("mode") in ("040000", "40000"),
                        "PR_GIT_TREE_INCOMPLETE")
                continue
            require(row.get("type") == "blob" and
                    row.get("mode") in ("100644", "100755", "120000"),
                    "PR_GIT_TREE_INCOMPLETE")
            result[path] = (row["sha"], row["mode"])
        return result

    old = leaves(common)
    new = leaves(candidate_sha)
    files = []
    for path in sorted(old.keys() | new.keys()):
        before, after = old.get(path), new.get(path)
        if before == after:
            continue
        require((before is None or before[1] == "100644") and
                (after is None or after[1] == "100644") and
                (before is None or after is None or before[1] == after[1]),
                "PR_UNTRUSTED_FILE_SCOPE_DENIED")
        files.append({"filename": path, "status":
                      "added" if before is None else
                      "removed" if after is None else "modified"})
    require(1 <= len(files) <= MAX_FILES, "PR_COMPLETE_DIFF_UNAVAILABLE")
    pinned = {"status": "ahead", "ahead_by": ahead, "behind_by": 0,
              "base_commit": {"sha": common},
              "merge_base_commit": {"sha": common},
              "commits": list(reversed(commits)), "files": files}
    comparison = (pinned if behind == 0 else {
        "status": "diverged", "ahead_by": ahead, "behind_by": behind,
        "base_commit": {"sha": main_sha},
        "merge_base_commit": {"sha": common}})
    return comparison, pinned


def _ci_runs_validate(runs, jobs_by_run, artifacts_by_run, *, pr, sha, branch, allowed_bases):
    require(isinstance(runs, dict) and type(runs.get("total_count")) is int and
            0 <= runs["total_count"] <= MAX_RUNS and
            isinstance(runs.get("workflow_runs"), list) and
            len(runs["workflow_runs"]) <= MAX_RUNS, "CI_RUNS_INCOMPLETE")
    by_workflow = {}
    for run in runs["workflow_runs"]:
        if not isinstance(run, dict):
            raise CandidateBlocked("CI_RUN_INVALID")
        name = run.get("name")
        if name not in EXPECTED:
            continue
        if name in by_workflow:
            # The first run in the API's newest-first list is authoritative;
            # earlier success never overrides a newer failure or cancellation.
            continue
        by_workflow[name] = run
    require(set(by_workflow) == set(EXPECTED), "CI_REQUIRED_WORKFLOW_MISSING")
    for name, job in EXPECTED.items():
        run = by_workflow[name]
        run_id = run.get("id")
        prs = run.get("pull_requests")
        require(type(run_id) is int and run_id > 0 and
                run.get("head_sha") == sha and
                run.get("head_branch") == branch and
                run.get("event") == "pull_request" and
                run.get("status") == "completed" and
                run.get("conclusion") == "success" and
                isinstance(prs, list) and
                any(isinstance(row, dict) and row.get("number") == pr and
                    isinstance(row.get("head"), dict) and
                    row["head"].get("sha") == sha and
                    isinstance(row.get("base"), dict) and
                    row["base"].get("sha") in allowed_bases for row in prs),
                "CI_SHA_OR_PR_UNQUALIFIED")
        jobs = jobs_by_run.get(run_id)
        require(isinstance(jobs, dict) and isinstance(jobs.get("jobs"), list)
                and jobs.get("total_count") == len(jobs["jobs"])
                and 0 < len(jobs["jobs"]) <= 100,
                "CI_REQUIRED_JOB_INCOMPLETE")
        required = [x for x in jobs["jobs"] if x.get("name") == job]
        require(len(required) == 1 and
                required[0].get("status") == "completed" and
                required[0].get("conclusion") == "success",
                "CI_REQUIRED_JOB_UNQUALIFIED")
        artifacts = artifacts_by_run.get(run_id)
        require(isinstance(artifacts, dict) and
                artifacts.get("total_count") == 0 and
                artifacts.get("artifacts") == [], "CI_PUBLIC_ARTIFACT_PRESENT")
    return True


def validate_candidate(main_sha, pr_number, candidate_sha, *, main, pull,
                       comparison, runs, jobs_by_run, artifacts_by_run,
                       pinned_delta=None):
    """Strict, no-network metadata validator, used by synthetic mutations."""
    require(isinstance(main, dict) and
            main.get("commit", {}).get("sha") == main_sha,
            "TRUSTED_MAIN_MOVED")
    if pr_number == 0:
        require(candidate_sha == main_sha, "MAIN_MODE_CANDIDATE_MISMATCH")
        return {"mode": "OWNER_MAIN", "source_sha": main_sha}
    require(type(pr_number) is int and 1 <= pr_number <= MAX_PR and
            isinstance(candidate_sha, str) and SHA.fullmatch(candidate_sha) and
            isinstance(pull, dict) and isinstance(comparison, dict),
            "CANDIDATE_METADATA_INVALID")
    head, base = pull.get("head"), pull.get("base")
    require(pull.get("number") == pr_number and pull.get("state") == "open" and
            pull.get("merged_at") is None and
            pull.get("user", {}).get("login") == OWNER and
            isinstance(head, dict) and isinstance(base, dict),
            "PR_OWNER_OR_STATE_DENIED")
    # GitHub may retain the PR's original base SHA after main advances,
    # although its base *ref* remains main. Validate that historical SHA
    # against the immutable compare merge-base; never require an
    # unnecessary rebase of the older, exact-CI-qualified candidate.
    common = comparison.get("merge_base_commit", {}).get("sha")
    branch = head.get("ref")
    require(isinstance(branch, str) and
            re.fullmatch(r"chat/classic-[a-z0-9/-]{8,100}", branch) and
            head.get("repo", {}).get("full_name") == REPO and
            head.get("sha") == candidate_sha and
            base.get("ref") == "main" and
            isinstance(common, str) and SHA.fullmatch(common) and
            base.get("sha") in (main_sha, common),
            "PR_BRANCH_HEAD_OR_BASE_DRIFT")
    # main can have advanced since the PR was created (e.g. by merging this
    # QA workflow itself). Require a bounded, genuine common ancestor.
    # Candidate-only files MUST be taken from an immutable SHA comparison
    # with that merge-base, never a moving PR/files listing.
    behind, ahead = comparison.get("behind_by"), comparison.get("ahead_by")
    require(isinstance(common, str) and SHA.fullmatch(common) and
            comparison.get("base_commit", {}).get("sha") == main_sha and
            type(behind) is int and 0 <= behind <= MAX_MAIN_ADVANCE and
            type(ahead) is int and ahead >= 1 and
            ((comparison.get("status") == "ahead" and behind == 0 and
              common == main_sha) or
             (comparison.get("status") == "diverged" and behind > 0 and
              common != main_sha)),
            "PR_ANCESTRY_UNQUALIFIED")
    delta = comparison if common == main_sha else pinned_delta
    require(isinstance(delta, dict) and delta.get("status") == "ahead" and
            delta.get("behind_by") == 0 and delta.get("ahead_by") == ahead and
            delta.get("merge_base_commit", {}).get("sha") == common and
            delta.get("base_commit", {}).get("sha") == common,
            "PR_PINNED_DELTA_UNVERIFIED")
    commits = delta.get("commits")
    files = delta.get("files")
    require(isinstance(commits, list) and
            len(commits) == comparison["ahead_by"] and
            isinstance(files, list) and
            1 <= len(files) <= MAX_FILES and
            pull.get("changed_files") == len(files),
            "PR_COMPLETE_DIFF_UNAVAILABLE")
    for item in commits:
        require(item.get("author", {}).get("login") == OWNER and
                item.get("committer", {}).get("login") == OWNER and
                isinstance(item.get("sha"), str) and SHA.fullmatch(item["sha"]),
                "PR_COMMIT_PROVENANCE_DENIED")
    # No arbitrary commit-count limit: every GitHub compare commit must be
    # present, unique, owner-authored and pinned to the requested source SHA.
    # GitHub compare pagination/truncation must never count as approval: the
    # exact complete list length above remains mandatory even for long PRs.
    require(len({item["sha"] for item in commits}) == len(commits),
            "PR_DUPLICATE_COMMIT_IN_COMPARISON")
    require(commits[-1]["sha"] == candidate_sha,
            "PR_COMMIT_SHA_DRIFT")
    for f in files:
        path = f.get("filename")
        require(isinstance(path, str) and
                path.startswith("classic_source/") and
                re.fullmatch(r"classic_source/[a-zA-Z0-9_./-]+", path) and
                ".." not in path.split("/") and
                f.get("status") in ("added", "modified"),
                "PR_UNTRUSTED_FILE_SCOPE_DENIED")
    _ci_runs_validate(runs, jobs_by_run, artifacts_by_run,
                      pr=pr_number, sha=candidate_sha, branch=branch,
                      allowed_bases=frozenset((main_sha, common)))
    return {"mode": "OWNER_PINNED_PR", "source_sha": candidate_sha}


def verify_owner_environment(env):
    from policy import require_approved_dispatch, PolicyFailure
    try:
        result = require_approved_dispatch(env)
    except (PolicyFailure, OSError, ValueError):
        raise CandidateBlocked("OWNER_MAIN_DISPATCH_DENIED") from None
    return result["source_sha"]


def execute_gate(env, api=_api):
    trusted = verify_owner_environment(env)
    number, sha = parse_inputs(env.get("CLASSIC_CANDIDATE_PR", "0"),
                               env.get("CLASSIC_CANDIDATE_SHA", ""), trusted)
    token = env.get("GH_PUBLIC_READ_TOKEN", "")
    # This is the trusted default-branch SHA captured by workflow_dispatch,
    # never a PR-supplied branch or GitHub Actions pull merge ref.
    main = api("/repos/" + REPO + "/branches/main", token)
    if number == 0:
        return validate_candidate(trusted, number, sha, main=main,
                                  pull=None, comparison=None, runs=None,
                                  jobs_by_run=None, artifacts_by_run=None)
    pull = api("/repos/" + REPO + "/pulls/" + str(number), token)
    try:
        comparison = api("/repos/" + REPO + "/compare/" + trusted + "..." + sha, token)
    except CompareDiffTimeout:
        comparison, pinned_delta = _git_comparison_from_objects(
            trusted, sha, api, token)
    else:
        common = comparison.get("merge_base_commit", {}).get("sha")
        require(isinstance(common, str) and SHA.fullmatch(common),
                "PR_ANCESTRY_UNQUALIFIED")
        try:
            pinned_delta = (comparison if common == trusted else
                            api("/repos/" + REPO + "/compare/" + common + "..." + sha, token))
        except CompareDiffTimeout:
            reconstructed, pinned_delta = _git_comparison_from_objects(
                trusted, sha, api, token)
            require(reconstructed["merge_base_commit"]["sha"] == common and
                    reconstructed["ahead_by"] == comparison["ahead_by"] and
                    reconstructed["behind_by"] == comparison["behind_by"],
                    "PR_GIT_COMPARISON_MISMATCH")
    runs = api("/repos/" + REPO +
               "/actions/runs?head_sha=" + sha +
               "&event=pull_request&per_page=100", token)
    run_ids = []
    for entry in runs.get("workflow_runs", []) if isinstance(runs, dict) else []:
        if (isinstance(entry, dict) and
                entry.get("name") in EXPECTED and
                entry.get("id") not in run_ids):
            run_ids.append(entry.get("id"))
    require(len(run_ids) <= MAX_RUNS and
            all(type(x) is int and x > 0 for x in run_ids),
            "CI_RUN_IDS_INVALID")
    jobs, artifacts = {}, {}
    for run_id in run_ids:
        jobs[run_id] = api("/repos/" + REPO +
                           "/actions/runs/" + str(run_id) + "/jobs?per_page=100", token)
        artifacts[run_id] = api("/repos/" + REPO +
                                "/actions/runs/" + str(run_id) + "/artifacts?per_page=100", token)
    return validate_candidate(trusted, number, sha, main=main, pull=pull,
                              comparison=comparison, runs=runs,
                              jobs_by_run=jobs, artifacts_by_run=artifacts,
                              pinned_delta=pinned_delta)


def main():
    try:
        result = execute_gate(dict(os.environ))
        output = os.environ.get("GITHUB_OUTPUT", "")
        temp = os.environ.get("RUNNER_TEMP", "")
        from pathlib import Path
        path = Path(output)
        require(bool(temp) and path.parent == Path(temp) / "_runner_file_commands" and
                re.fullmatch(r"set_output_[a-f0-9-]{16,80}", path.name) is not None,
                "RUNNER_OUTPUT_PATH_UNVERIFIED")
        # The runner's output path must be within RUNNER_TEMP. No secret or
        # path values are printed, only fixed status and validated SHA.
        with open(output, "a", encoding="ascii") as stream:
            stream.write("source_sha=" + result["source_sha"] + "\n")
        print(json.dumps({"status": "CLASSIC_CANDIDATE_GATE_PASS",
                          "mode": result["mode"],
                          "source_sha": result["source_sha"],
                          "private_apk": "NOT_FETCHED"}))
        return 0
    except CandidateBlocked as error:
        print(json.dumps({"status": "CLASSIC_CANDIDATE_GATE_DENIED",
                          "code": str(error)}))
    except Exception:
        print(json.dumps({"status": "CLASSIC_CANDIDATE_GATE_DENIED",
                          "code": "UNEXPECTED_PUBLIC_PREFLIGHT_ERROR"}))
    return 2


if __name__ == "__main__":
    sys.exit(main())
