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
MAX_COMMITS = 8
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
    except (urllib.error.URLError, OSError, ValueError,
            UnicodeError, TimeoutError):
        raise CandidateBlocked("PUBLIC_GITHUB_EVIDENCE_UNAVAILABLE") from None


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
    branch = head.get("ref")
    require(isinstance(branch, str) and
            re.fullmatch(r"chat/classic-[a-z0-9/-]{8,100}", branch) and
            head.get("repo", {}).get("full_name") == REPO and
            head.get("sha") == candidate_sha and
            base.get("ref") == "main" and
            base.get("sha") == main_sha,
            "PR_BRANCH_HEAD_OR_BASE_DRIFT")
    # main can have advanced since the PR was created (e.g. by merging this
    # QA workflow itself). Require a bounded, genuine common ancestor.
    # Candidate-only files MUST be taken from an immutable SHA comparison
    # with that merge-base, never a moving PR/files listing.
    common = comparison.get("merge_base_commit", {}).get("sha")
    behind, ahead = comparison.get("behind_by"), comparison.get("ahead_by")
    require(isinstance(common, str) and SHA.fullmatch(common) and
            comparison.get("base_commit", {}).get("sha") == main_sha and
            type(behind) is int and 0 <= behind <= MAX_MAIN_ADVANCE and
            type(ahead) is int and 1 <= ahead <= MAX_COMMITS and
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
    comparison = api("/repos/" + REPO + "/compare/" + trusted + "..." + sha, token)
    common = comparison.get("merge_base_commit", {}).get("sha")
    require(isinstance(common, str) and SHA.fullmatch(common),
            "PR_ANCESTRY_UNQUALIFIED")
    pinned_delta = (comparison if common == trusted else
                    api("/repos/" + REPO + "/compare/" + common + "..." + sha, token))
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
