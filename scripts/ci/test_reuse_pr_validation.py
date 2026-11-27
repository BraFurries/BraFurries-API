"""Offline tests for the deploy validation reuse gate."""
import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from reuse_pr_validation import REPOSITORY, REQUIRED_JOBS, REQUIRED_TEST_STEPS, WORKFLOW, main, prove

SHA = "a" * 40
HEAD = "b" * 40
TREE = "c" * 40
NUMBER = 159


def succeeded(name):
    return {"name": name, "status": "completed", "conclusion": "success"}


class ReusePolicyTest(unittest.TestCase):
    def setUp(self):
        repo = {"full_name": REPOSITORY}
        self.pr = {
            "number": NUMBER, "merged_at": "2026-11-27T23:42:17Z", "merge_commit_sha": SHA,
            "base": {"ref": "main", "repo": repo.copy()},
            "head": {"ref": "feature", "sha": HEAD, "repo": repo.copy()},
        }
        self.commit = {"sha": HEAD, "commit": {"tree": {"sha": TREE}}}
        self.run = {
            "id": 123, "event": "pull_request", "path": WORKFLOW, "head_sha": HEAD,
            "head_branch": "feature", "repository": repo.copy(), "head_repository": repo.copy(),
            "created_at": "2026-11-27T20:00:00Z", "run_attempt": 1,
            "status": "completed", "conclusion": "success",
        }
        self.runs = [self.run]
        self.jobs = [succeeded(name) for name in sorted(REQUIRED_JOBS)]
        self.test = next(j for j in self.jobs if j["name"] == "test")
        self.test["steps"] = [succeeded(name) for name in sorted(REQUIRED_TEST_STEPS)]
        self.test["steps"].append(succeeded(f"Reusable validation for PR #{NUMBER}"))
        self.associated = [self.pr]

    def get(self, path):
        if path == f"/commits/{SHA}/pulls?per_page=100":
            return self.associated
        if path == f"/commits/{HEAD}":
            return self.commit
        if path.startswith("/actions/runs?"):
            return {"total_count": len(self.runs), "workflow_runs": self.runs}
        if path == "/actions/runs/123/attempts/1/jobs?per_page=100":
            return {"total_count": len(self.jobs), "jobs": self.jobs}
        raise AssertionError(path)

    def assert_denied(self):
        self.assertFalse(prove(self.get, SHA, TREE)[0])

    def test_exact_tree_and_successful_pr(self):
        self.assertEqual((True, "pr_159_run_123"), prove(self.get, SHA, TREE))

    def test_direct_push_or_unmerged_pr(self):
        self.associated.clear()
        self.assert_denied()

    def test_different_tree(self):
        self.commit["commit"]["tree"]["sha"] = "d" * 40
        self.assert_denied()

    def test_fork(self):
        self.pr["head"]["repo"]["full_name"] = "someone/fork"
        self.assert_denied()

    def test_other_target_branch(self):
        self.pr["base"]["ref"] = "feature"
        self.assert_denied()

    def test_not_the_actual_merge(self):
        self.pr["merge_commit_sha"] = HEAD
        self.assert_denied()

    def test_wrong_workflow(self):
        self.run["path"] = ".github/workflows/other.yml"
        self.assert_denied()

    def test_wrong_run_repo(self):
        self.run["head_repository"]["full_name"] = "someone/fork"
        self.assert_denied()

    def test_wrong_head(self):
        self.run["head_sha"] = SHA
        self.assert_denied()

    def test_latest_failure_cannot_reuse_older_success(self):
        later = copy.deepcopy(self.run)
        later.update(id=124, created_at="2026-11-27T21:00:00Z", conclusion="failure")
        self.runs.append(later)
        self.assert_denied()

    def test_rerun_requires_full_fallback(self):
        self.run["run_attempt"] = 2
        self.assert_denied()

    def test_missing_required_job(self):
        self.jobs.remove(next(job for job in self.jobs if job["name"] == "secret-scan"))
        self.assert_denied()

    def test_missing_marker(self):
        self.test["steps"].pop()
        self.assert_denied()

    def test_skipped_marker(self):
        self.test["steps"][-1]["conclusion"] = "skipped"
        self.assert_denied()

    def test_marker_for_wrong_pr(self):
        self.test["steps"][-1]["name"] = "Reusable validation for PR #160"
        self.assert_denied()

    def test_required_tests_skipped(self):
        next(s for s in self.test["steps"] if s["name"] == "Run tests")["conclusion"] = "skipped"
        self.assert_denied()

    def test_ambiguous_pr(self):
        self.associated.append(copy.deepcopy(self.pr))
        self.assert_denied()

    def test_incomplete_workflow_history(self):
        def incomplete(path):
            result = self.get(path)
            if path.startswith("/actions/runs?"):
                result["total_count"] += 1
            return result
        self.assertFalse(prove(incomplete, SHA, TREE)[0])

    def test_manual_dispatch_always_runs_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            with patch.dict(os.environ, {
                "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_OUTPUT": str(output),
            }, clear=True), patch("urllib.request.urlopen") as api:
                main()
                api.assert_not_called()
            self.assertEqual("reuse=false\n", output.read_text())

    def test_api_failure_falls_back_to_full_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            with patch.dict(os.environ, {
                "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main",
                "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_SHA": SHA, "GITHUB_OUTPUT": str(output),
                "GH_TOKEN": "fake-test-token",
            }, clear=True), patch("subprocess.check_output", side_effect=[SHA, TREE]), \
                    patch("urllib.request.urlopen", side_effect=TimeoutError):
                main()
            self.assertEqual("reuse=false\n", output.read_text())


if __name__ == "__main__":
    unittest.main()
