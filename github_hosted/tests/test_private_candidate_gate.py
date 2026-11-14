"""Synthetic PR SHA/CI gate tests; absolutely no vault, APK or network access."""
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / "private_candidate_gate.py"
spec = importlib.util.spec_from_file_location("classic_candidate_gate_tests", MODULE)
gate = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(gate)

BASE = "a" * 40
HEAD = "b" * 40
RUNS = (1001, 1002, 1003)
BRANCH = "chat/classic-candidate-issue96-20261112"


def fixture():
    main = {"commit": {"sha": BASE}}
    pull = {
        "number": 104, "state": "open", "merged_at": None, "draft": True,
        "user": {"login": "lxsdd"},
        "head": {"ref": BRANCH, "sha": HEAD,
                 "repo": {"full_name": gate.REPO}},
        "base": {"ref": "main", "sha": BASE},
        "changed_files": 2,
    }
    compare = {
        "status": "ahead", "ahead_by": 1, "behind_by": 0,
        "merge_base_commit": {"sha": BASE},
        "base_commit": {"sha": BASE},
        "commits": [{"sha": HEAD, "author": {"login": "lxsdd"},
                     "committer": {"login": "lxsdd"}}],
        "files": [
            {"filename": "classic_source/private_ci/classloader_linkage_forensics.py",
             "status": "modified"},
            {"filename": "classic_source/tests/test_classloader_linkage_forensics.py",
             "status": "modified"},
        ],
    }
    runs = {"total_count": 3, "workflow_runs": []}
    jobs, artifacts = {}, {}
    for index, (name, job) in enumerate(gate.EXPECTED.items()):
        run_id = RUNS[index]
        runs["workflow_runs"].append({
            "id": run_id, "name": name, "event": "pull_request",
            "head_sha": HEAD, "head_branch": BRANCH,
            "status": "completed", "conclusion": "success",
            "pull_requests": [
                {"number": 104, "head": {"sha": HEAD}, "base": {"sha": BASE}}
            ],
        })
        jobs[run_id] = {"total_count": 1, "jobs": [
            {"name": job, "status": "completed", "conclusion": "success"}]}
        artifacts[run_id] = {"total_count": 0, "artifacts": []}
    return main, pull, compare, runs, jobs, artifacts


def check(data):
    return gate.validate_candidate(BASE, 104, HEAD, main=data[0], pull=data[1],
                                   comparison=data[2], runs=data[3],
                                   jobs_by_run=data[4], artifacts_by_run=data[5])


class CandidateParserTests(unittest.TestCase):
    def test_main_compatibility_and_exact_pinned_candidate(self):
        self.assertEqual(gate.parse_inputs("0", "", BASE), (0, BASE))
        self.assertEqual(gate.parse_inputs("104", HEAD, BASE), (104, HEAD))
        self.assertEqual(gate.validate_candidate(
            BASE, 0, BASE, main={"commit": {"sha": BASE}}, pull=None,
            comparison=None, runs=None, jobs_by_run=None,
            artifacts_by_run=None)["mode"], "OWNER_MAIN")

    def test_reject_implicit_sha_selection_and_all_malformed_inputs(self):
        for pr, sha in (("104", ""), ("104", BASE), ("0", HEAD),
                        ("", ""), ("+104", HEAD), ("00104", HEAD),
                        ("-1", HEAD), ("1000001", HEAD), ("104", "X" * 40),
                        ("104", "b" * 39), ("abc", HEAD), ("104", HEAD + "\n")):
            with self.subTest(pr=pr, sha_length=len(sha)), self.assertRaises(gate.CandidateBlocked):
                gate.parse_inputs(pr, sha, BASE)

    def test_main_cannot_point_to_any_other_commit(self):
        with self.assertRaisesRegex(gate.CandidateBlocked, "MAIN_MODE_CANDIDATE_MISMATCH"):
            gate.validate_candidate(
                BASE, 0, HEAD, main={"commit": {"sha": BASE}}, pull=None,
                comparison=None, runs=None, jobs_by_run=None,
                artifacts_by_run=None)


class StrictPreflightTests(unittest.TestCase):
    def setUp(self):
        self.data = fixture()

    def test_valid_exact_owner_branch_and_three_jobs(self):
        self.assertEqual(check(self.data), {
            "mode": "OWNER_PINNED_PR", "source_sha": HEAD})

    def test_fail_closed_on_new_main_and_pr_drift(self):
        mutations = [
            (0, lambda x: x["commit"].update(sha="c"*40)),
            (1, lambda x: x["head"].update(sha="c"*40)),
            (1, lambda x: x["base"].update(sha="c"*40)),
            (1, lambda x: x["head"]["repo"].update(full_name="attacker/fork")),
            (1, lambda x: x["head"].update(ref="refs/pull/104/merge")),
            (1, lambda x: x["head"].update(ref="chat/color4-other")),
            (1, lambda x: x["user"].update(login="attacker")),
            (1, lambda x: x.update(state="closed")),
            (1, lambda x: x.update(merged_at="2026-11-12T17:00:00Z")),
            (1, lambda x: x.update(number=105)),
        ]
        for index, change in mutations:
            with self.subTest(index=index, change=str(change)):
                values = copy.deepcopy(self.data)
                change(values[index])
                with self.assertRaises(gate.CandidateBlocked):
                    check(values)

    def test_reject_divergence_foreign_commit_and_unknown_file_scope(self):
        mutations = [
            lambda x: x.update(status="diverged"),
            lambda x: x.update(behind_by=1),
            lambda x: x["merge_base_commit"].update(sha="c"*40),
            lambda x: x.update(ahead_by=9),
            lambda x: x["commits"][0]["author"].update(login="attacker"),
            lambda x: x["commits"][0]["committer"].update(login="attacker"),
            lambda x: x["files"][0].update(filename=".github/workflows/classic-private-emulator-probe.yml"),
            lambda x: x["files"][0].update(filename="classic_source/../../github_hosted/vault.sh"),
            lambda x: x["files"][0].update(status="removed"),
        ]
        for mutation in mutations:
            with self.subTest(change=str(mutation)):
                values = copy.deepcopy(self.data)
                mutation(values[2])
                with self.assertRaises(gate.CandidateBlocked):
                    check(values)
        values = copy.deepcopy(self.data)
        values[1]["changed_files"] = 999
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_COMPLETE_DIFF_UNAVAILABLE"):
            check(values)

    def test_no_artificial_eight_commit_limit_with_all_provenance_verified(self):
        # This is the exact class of real regression that blocked Classic PR108:
        # 10 owner-authored commits and three latest exact-head public CI passes.
        # Each commit is verified; only the arbitrary count ceiling goes away.
        for count in (9, 10, 24, 60):
            with self.subTest(commits=count):
                vals = copy.deepcopy(self.data)
                commits = [{
                    "sha": f"{i:040x}", "author": {"login": "lxsdd"},
                    "committer": {"login": "lxsdd"},
                } for i in range(1, count)]
                commits.append({
                    "sha": HEAD, "author": {"login": "lxsdd"},
                    "committer": {"login": "lxsdd"},
                })
                vals[2]["ahead_by"] = count
                vals[2]["commits"] = commits
                self.assertEqual(check(vals)["source_sha"], HEAD)

                incomplete = copy.deepcopy(vals)
                incomplete[2]["commits"].pop(count // 2)
                with self.assertRaisesRegex(
                        gate.CandidateBlocked, "PR_COMPLETE_DIFF_UNAVAILABLE"):
                    check(incomplete)

                foreign = copy.deepcopy(vals)
                foreign[2]["commits"][count // 2]["author"]["login"] = "attacker"
                with self.assertRaisesRegex(
                        gate.CandidateBlocked, "PR_COMMIT_PROVENANCE_DENIED"):
                    check(foreign)

                foreign_committer = copy.deepcopy(vals)
                foreign_committer[2]["commits"][count // 2]["committer"]["login"] = "attacker"
                with self.assertRaisesRegex(
                        gate.CandidateBlocked, "PR_COMMIT_PROVENANCE_DENIED"):
                    check(foreign_committer)

                duplicate = copy.deepcopy(vals)
                duplicate[2]["commits"][1]["sha"] = duplicate[2]["commits"][0]["sha"]
                with self.assertRaisesRegex(
                        gate.CandidateBlocked, "PR_DUPLICATE_COMMIT_IN_COMPARISON"):
                    check(duplicate)

                forbidden = copy.deepcopy(vals)
                forbidden[2]["files"][0]["filename"] = "github_hosted/private_candidate_gate.py"
                with self.assertRaisesRegex(
                        gate.CandidateBlocked, "PR_UNTRUSTED_FILE_SCOPE_DENIED"):
                    check(forbidden)

    def test_commit_count_cannot_override_full_diff_or_exact_head(self):
        vals = copy.deepcopy(self.data)
        vals[2]["ahead_by"] = 11
        vals[2]["commits"] = [
            {"sha": f"{i:040x}", "author": {"login": "lxsdd"},
             "committer": {"login": "lxsdd"}}
            for i in range(1, 11)
        ] + [{"sha": "c" * 40, "author": {"login": "lxsdd"},
              "committer": {"login": "lxsdd"}}]
        # 11 authentic-looking entries do not help if the immutable last
        # commit differs from the owner-pinned candidate.
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_COMMIT_SHA_DRIFT"):
            check(vals)
        vals[2]["commits"][-1]["sha"] = HEAD
        vals[1]["changed_files"] += 1
        with self.assertRaisesRegex(
                gate.CandidateBlocked, "PR_COMPLETE_DIFF_UNAVAILABLE"):
            check(vals)

    def test_missing_red_pending_or_unbound_ci_cannot_use_vault(self):
        for index in range(3):
            for mutate in (
                lambda r: r.update(conclusion="failure"),
                lambda r: r.update(conclusion="skipped"),
                lambda r: r.update(status="in_progress"),
                lambda r: r.update(head_sha="c" * 40),
                lambda r: r.update(event="push"),
                lambda r: r.update(head_branch="chat/classic-foreign"),
                lambda r: r["pull_requests"].clear(),
            ):
                with self.subTest(index=index, change=str(mutate)):
                    vals = copy.deepcopy(self.data)
                    mutate(vals[3]["workflow_runs"][index])
                    with self.assertRaises(gate.CandidateBlocked):
                        check(vals)

    def test_recent_failed_run_overrides_stale_green(self):
        vals = copy.deepcopy(self.data)
        duplicate = copy.deepcopy(vals[3]["workflow_runs"][0])
        duplicate["id"] = 2001
        duplicate["conclusion"] = "failure"
        vals[3]["workflow_runs"].insert(0, duplicate)
        vals[3]["total_count"] += 1
        with self.assertRaisesRegex(gate.CandidateBlocked, "CI_SHA_OR_PR_UNQUALIFIED"):
            check(vals)

    def test_skipped_or_missing_job_rejected(self):
        for status in ("skipped", "failure", None):
            vals = copy.deepcopy(self.data)
            vals[4][RUNS[0]]["jobs"][0]["conclusion"] = status
            with self.assertRaisesRegex(gate.CandidateBlocked, "CI_REQUIRED_JOB_UNQUALIFIED"):
                check(vals)
        vals = copy.deepcopy(self.data)
        vals[4][RUNS[0]]["jobs"].clear()
        with self.assertRaisesRegex(gate.CandidateBlocked, "CI_REQUIRED_JOB_INCOMPLETE"):
            check(vals)

    def test_public_artifact_poisoning_blocked(self):
        vals = copy.deepcopy(self.data)
        vals[5][RUNS[2]] = {"total_count": 1, "artifacts": [{"id": 4}]}
        with self.assertRaisesRegex(gate.CandidateBlocked, "CI_PUBLIC_ARTIFACT_PRESENT"):
            check(vals)

    def test_bounded_main_advance_preserves_immutable_candidate_only_diff(self):
        """A new workflow merged to main must not force a retest PR rebase.

        Compare main...HEAD can be diverged; the PR source-only changes are
        verified via separate immutable merge-base...HEAD, not a mutable
        PR-file-list. Historical PR CI may identify that merge-base as base.
        """
        values = copy.deepcopy(self.data)
        common = "c" * 40
        candidate_diff = copy.deepcopy(values[2])
        candidate_diff["merge_base_commit"]["sha"] = common
        candidate_diff["base_commit"]["sha"] = common
        values[2]["status"] = "diverged"
        values[2]["behind_by"] = 1
        values[2]["merge_base_commit"]["sha"] = common
        # This is the real GitHub behavior after merging QA infrastructure:
        # PR.base.sha still denotes the original shared ancestor.
        values[1]["base"]["sha"] = common
        for run in values[3]["workflow_runs"]:
            run["pull_requests"][0]["base"]["sha"] = common
        def qualified(delta):
            return gate.validate_candidate(
                BASE, 104, HEAD, main=values[0], pull=values[1],
                comparison=values[2], pinned_delta=delta, runs=values[3],
                jobs_by_run=values[4], artifacts_by_run=values[5])
        self.assertEqual(qualified(candidate_diff)["source_sha"], HEAD)
        # Either the historical true merge-base or current main may be
        # reflected by GitHub's PR metadata, but no unrelated old base.
        values[1]["base"]["sha"] = BASE
        self.assertEqual(qualified(candidate_diff)["source_sha"], HEAD)
        values[1]["base"]["sha"] = "d" * 40
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_BRANCH_HEAD_OR_BASE_DRIFT"):
            qualified(candidate_diff)
        values[1]["base"]["sha"] = common
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_PINNED_DELTA_UNVERIFIED"):
            qualified(None)
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_PINNED_DELTA_UNVERIFIED"):
            bad = copy.deepcopy(candidate_diff)
            bad["merge_base_commit"]["sha"] = BASE
            qualified(bad)
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_UNTRUSTED_FILE_SCOPE_DENIED"):
            bad = copy.deepcopy(candidate_diff)
            bad["files"][0]["filename"] = "github_hosted/vault.sh"
            qualified(bad)
        values[2]["behind_by"] = gate.MAX_MAIN_ADVANCE + 1
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_ANCESTRY_UNQUALIFIED"):
            qualified(candidate_diff)

    def test_historical_pr_base_without_real_divergence_is_not_valid(self):
        values = copy.deepcopy(self.data)
        values[1]["base"]["sha"] = "c" * 40
        # The SHA cannot be treated as historical simply because it appears
        # in PR metadata; it must equal GitHub's verified common ancestor.
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_BRANCH_HEAD_OR_BASE_DRIFT"):
            check(values)

    def test_diverged_head_only_api_is_exact_sha_pinned(self):
        values = copy.deepcopy(self.data)
        common = "c" * 40
        candidate_diff = copy.deepcopy(values[2])
        candidate_diff["merge_base_commit"]["sha"] = common
        candidate_diff["base_commit"]["sha"] = common
        values[2]["status"] = "diverged"
        values[2]["behind_by"] = 1
        values[2]["merge_base_commit"]["sha"] = common
        # This is the real GitHub behavior after merging QA infrastructure:
        # PR.base.sha still denotes the original shared ancestor.
        values[1]["base"]["sha"] = common
        for run in values[3]["workflow_runs"]:
            run["pull_requests"][0]["base"]["sha"] = common
        endpoints = {
            "/repos/" + gate.REPO + "/branches/main": values[0],
            "/repos/" + gate.REPO + "/pulls/104": values[1],
            "/repos/" + gate.REPO + "/compare/" + BASE + "..." + HEAD: values[2],
            "/repos/" + gate.REPO + "/compare/" + common + "..." + HEAD: candidate_diff,
            "/repos/" + gate.REPO + "/actions/runs?head_sha=" + HEAD +
            "&event=pull_request&per_page=100": values[3],
        }
        for run_id in RUNS:
            endpoints[f"/repos/{gate.REPO}/actions/runs/{run_id}/jobs?per_page=100"] = values[4][run_id]
            endpoints[f"/repos/{gate.REPO}/actions/runs/{run_id}/artifacts?per_page=100"] = values[5][run_id]
        calls = []
        def api(path, token):
            calls.append(path)
            return copy.deepcopy(endpoints[path])
        env = {"CLASSIC_CANDIDATE_PR": "104", "CLASSIC_CANDIDATE_SHA": HEAD,
               "GH_PUBLIC_READ_TOKEN": "synthetic_public_only_token"}
        with mock.patch.object(gate, "verify_owner_environment", return_value=BASE):
            result = gate.execute_gate(env, api=api)
        self.assertEqual(result["source_sha"], HEAD)
        self.assertIn("/repos/" + gate.REPO + "/compare/" + common + "..." + HEAD, calls)
        self.assertEqual(len(calls), 11)


    def test_no_network_action_or_vault_fetch_in_pure_validator(self):
        with mock.patch.object(gate.urllib.request, "urlopen") as url:
            check(self.data)
            url.assert_not_called()

    def test_execute_gate_never_calls_secret_or_candidate_scripts(self):
        vals = fixture()
        endpoints = {}
        endpoints["/repos/" + gate.REPO + "/branches/main"] = vals[0]
        endpoints["/repos/" + gate.REPO + "/pulls/104"] = vals[1]
        endpoints["/repos/" + gate.REPO + "/compare/" + BASE + "..." + HEAD] = vals[2]
        endpoints["/repos/" + gate.REPO +
                  "/actions/runs?head_sha=" + HEAD + "&event=pull_request&per_page=100"] = vals[3]
        for run_id in RUNS:
            endpoints[f"/repos/{gate.REPO}/actions/runs/{run_id}/jobs?per_page=100"] = vals[4][run_id]
            endpoints[f"/repos/{gate.REPO}/actions/runs/{run_id}/artifacts?per_page=100"] = vals[5][run_id]
        seen = []
        def api(path, token):
            seen.append(path)
            return copy.deepcopy(endpoints[path])
        env = {"CLASSIC_CANDIDATE_PR": "104", "CLASSIC_CANDIDATE_SHA": HEAD,
               "GH_PUBLIC_READ_TOKEN": "synthetic_public_only_token"}
        with mock.patch.object(gate, "verify_owner_environment", return_value=BASE):
            result = gate.execute_gate(env, api=api)
        self.assertEqual(result["source_sha"], HEAD)
        self.assertEqual(len(seen), 10)
        self.assertTrue(all("/repos/" + gate.REPO in path for path in seen))
        self.assertFalse(any("vault" in path.lower() for path in seen))



class ImmutableGit422ProofTests(unittest.TestCase):
    """Synthetic end-to-end 21-commit recovery without HTTP or OEM input."""

    def setUp(self):
        self.main, self.pull, _, self.runs, self.jobs, self.artifacts = fixture()
        self.common = "c" * 40
        self.pull["base"]["sha"] = self.common
        for run in self.runs["workflow_runs"]:
            run["pull_requests"][0]["base"]["sha"] = self.common
        self.rows = {}
        parent = self.common
        for i in range(1, 22):
            value = HEAD if i == 21 else f"{i:040x}"
            self.rows[f"/repos/{gate.REPO}/commits/{value}"] = {
                "sha": value, "parents": [{"sha": parent}],
                "author": {"login": "lxsdd"}, "committer": {"login": "lxsdd"},
                "commit": {"tree": {"sha": "e" * 40 if i == 21 else "f" * 40}}}
            parent = value
        self.rows[f"/repos/{gate.REPO}/commits/{BASE}"] = {
            "sha": BASE, "parents": [{"sha": self.common}],
            "commit": {"tree": {"sha": "d" * 40}}}
        self.rows[f"/repos/{gate.REPO}/commits/{self.common}"] = {
            "sha": self.common, "parents": [],
            "commit": {"tree": {"sha": "d" * 40}}}
        self.tpath = f"/repos/{gate.REPO}/git/trees/{'e'*40}?recursive=1"
        self.rows[f"/repos/{gate.REPO}/git/trees/{'d'*40}?recursive=1"] = {
            "sha": "d" * 40, "truncated": False,
            "tree": [{"path": "classic_source/foo.py", "type": "blob",
                      "mode": "100644", "sha": "1" * 40}]}
        self.rows[self.tpath] = {
            "sha": "e" * 40, "truncated": False,
            "tree": [{"path": "classic_source/foo.py", "type": "blob",
                      "mode": "100644", "sha": "2" * 40},
                     {"path": "classic_source/bar.py", "type": "blob",
                      "mode": "100644", "sha": "3" * 40}]}
        self.rows[f"/repos/{gate.REPO}/branches/main"] = self.main
        self.rows[f"/repos/{gate.REPO}/pulls/104"] = self.pull
        self.rows[f"/repos/{gate.REPO}/actions/runs?head_sha={HEAD}&event=pull_request&per_page=100"] = self.runs
        for rid in RUNS:
            self.rows[f"/repos/{gate.REPO}/actions/runs/{rid}/jobs?per_page=100"] = self.jobs[rid]
            self.rows[f"/repos/{gate.REPO}/actions/runs/{rid}/artifacts?per_page=100"] = self.artifacts[rid]

    def run_gate(self):
        paths = []
        def api(path, token):
            paths.append(path)
            if path == f"/repos/{gate.REPO}/compare/{BASE}...{HEAD}":
                raise gate.CompareDiffTimeout("GITHUB_COMPARE_DIFF_TIMEOUT")
            return copy.deepcopy(self.rows[path])
        env = {"CLASSIC_CANDIDATE_PR": "104",
               "CLASSIC_CANDIDATE_SHA": HEAD,
               "GH_PUBLIC_READ_TOKEN": "synthetic_only_not_secret"}
        with mock.patch.object(gate, "verify_owner_environment", return_value=BASE):
            return gate.execute_gate(env, api=api), paths

    def test_complete_21_commit_exact_ancestor_and_tree_diff_qualifies(self):
        result, paths = self.run_gate()
        self.assertEqual(result["source_sha"], HEAD)
        self.assertIn(self.tpath, paths)
        self.assertFalse(any("vault" in path.lower() for path in paths))

    def test_foreign_truncated_deleted_chmod_and_bad_metadata_denied(self):
        commit = f"/repos/{gate.REPO}/commits/{HEAD}"
        mutations = [
            (commit, lambda v: v["author"].update(login="attacker")),
            (commit, lambda v: v.update(parents=[])),
            (commit, lambda v: v["parents"].append({"sha": BASE})),
            (commit, lambda v: v.update(sha="0" * 40)),
            (self.tpath, lambda v: v.update(truncated=True)),
            (self.tpath, lambda v: v.update(sha="0" * 40)),
            (self.tpath, lambda v: v["tree"].append(v["tree"][0].copy())),
            (self.tpath, lambda v: v["tree"][0].update(path="github_hosted/policy.py")),
            (self.tpath, lambda v: v["tree"][0].update(mode="100755")),
            (self.tpath, lambda v: v["tree"].clear()),
        ]
        for path, mutate in mutations:
            with self.subTest(path=path, mutate=str(mutate)):
                original = copy.deepcopy(self.rows[path])
                mutate(self.rows[path])
                try:
                    with self.assertRaises(gate.CandidateBlocked):
                        self.run_gate()
                finally:
                    self.rows[path] = original
        self.pull["changed_files"] += 1
        with self.assertRaisesRegex(gate.CandidateBlocked, "PR_COMPLETE_DIFF_UNAVAILABLE"):
            self.run_gate()

    def test_only_recognized_http_422_enters_recovery(self):
        import io
        import json
        from urllib.error import HTTPError
        path = f"/repos/{gate.REPO}/compare/{BASE}...{HEAD}"
        body = json.dumps({"message":
            "Server Error: Sorry, this diff is taking too long to generate.",
            "errors": [{"resource": "Comparison", "field": "diff",
                        "code": "not_available"}]}).encode()
        def send(payload, status):
            raise HTTPError("https://api.github.com" + path,
                            status, "synthetic", {}, io.BytesIO(payload))
        with mock.patch.object(gate.urllib.request, "urlopen",
                               side_effect=lambda *a, **kw: send(body, 422)):
            with self.assertRaises(gate.CompareDiffTimeout):
                gate._api(path, "synthetic_token_not_secret")
            with self.assertRaisesRegex(gate.CandidateBlocked,
                                        "PUBLIC_GITHUB_EVIDENCE_UNAVAILABLE"):
                gate._api(f"/repos/{gate.REPO}/pulls/104",
                          "synthetic_token_not_secret")
        for payload, status in ((b"{}", 422), (body, 403), (body, 500)):
            with mock.patch.object(gate.urllib.request, "urlopen",
                                   side_effect=lambda *a, **kw: send(payload, status)):
                with self.assertRaisesRegex(gate.CandidateBlocked,
                                            "PUBLIC_GITHUB_EVIDENCE_UNAVAILABLE"):
                    gate._api(path, "synthetic_token_not_secret")


if __name__ == "__main__":
    unittest.main()
