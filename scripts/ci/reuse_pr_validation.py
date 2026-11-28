"""Conservative proof of a successful PR validation for the exact deployed tree.

Only GitHub API metadata is consumed. Never download PR artifacts, execute PR
code here or grant production runner access. Any missing or ambiguous evidence
means run the normal Maven lifecycle. Manual dispatch always tests.
"""
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request

REPOSITORY = "BraFurries/BraFurries-API"
WORKFLOW = ".github/workflows/pr-validation.yml"
REQUIRED_JOBS = {"secret-scan", "test", "core-tests", "database-contracts", "Creator Studio on disposable MariaDB"}
REQUIRED_TEST_STEPS = {
    "Check production workflow safety invariants",
    "Test PR validation reuse policy",
    "Validate UserBan mapping (no Docker dependency)",
    "Run tests",
    "Package without rerunning tests",
    "Verify layered runtime image and dependency reuse",
    "Compare validated checkout with PR head tree",
}

REQUIRED_CONTRACT_STEPS = {
    "Verify baseline source and run opt-in MariaDB Testcontainers compatibility",
    "Verify actual Coddy to Spring loopback HTTP contract on disposable MariaDB",
    "Verify recruitment drafts JDBC with canonical baseline plus additive migration",
}


def is_sha(value):
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{40}", value) is not None


def success(entry):
    return entry.get("status") == "completed" and entry.get("conclusion") == "success"


def same_repo(entry):
    return isinstance(entry, dict) and entry.get("full_name") == REPOSITORY


def unique_success(entries, name):
    matches = [entry for entry in entries if entry.get("name") == name]
    return len(matches) == 1 and success(matches[0])


def prove(get, sha, tree):
    """Return (reuse, reason). GET callback uses repository-relative API paths."""
    if not is_sha(sha) or not is_sha(tree):
        return False, "invalid_revision"

    associated = get(f"/commits/{sha}/pulls?per_page=100")
    candidates = [
        pr for pr in associated
        if pr.get("merged_at") and pr.get("merge_commit_sha") == sha
        and pr.get("base", {}).get("ref") == "main"
        and same_repo(pr.get("base", {}).get("repo"))
        and same_repo(pr.get("head", {}).get("repo"))
    ]
    if len(candidates) != 1:
        return False, "no_unique_same_repository_merged_pr"
    pr = candidates[0]
    number, head = pr.get("number"), pr.get("head", {}).get("sha")
    if type(number) is not int or number <= 0 or not is_sha(head):
        return False, "invalid_pr_identity"
    # Confirm the full Git tree, including tests, workflow files and build inputs.
    head_commit = get(f"/commits/{head}")
    if head_commit.get("sha") != head or head_commit.get("commit", {}).get("tree", {}).get("sha") != tree:
        return False, "different_tree"

    params = urllib.parse.urlencode({"event": "pull_request", "head_sha": head, "per_page": 100})
    response = get(f"/actions/runs?{params}")
    # If pagination hides possible newer runs, conservatively refuse reuse.
    runs = response["workflow_runs"]
    if response.get("total_count") != len(runs):
        return False, "ambiguous_workflow_history"
    matches = [
        run for run in runs
        if run.get("path") == WORKFLOW
        and run.get("event") == "pull_request"
        and run.get("head_sha") == head
        and run.get("head_branch") == pr["head"].get("ref")
        and same_repo(run.get("repository"))
        and same_repo(run.get("head_repository"))
    ]
    if not matches:
        return False, "no_matching_validation"
    # Do not fall back to an older successful run if the latest run failed.
    run = max(matches, key=lambda item: (item["created_at"], item["id"]))
    run_id = run.get("id")
    if not success(run) or run.get("run_attempt") != 1 or type(run_id) is not int or run_id <= 0:
        return False, "validation_not_successful_first_attempt"
    result = get(f"/actions/runs/{run_id}/attempts/1/jobs?per_page=100")
    jobs = result["jobs"]
    if result.get("total_count") != len(jobs):
        return False, "incomplete_job_metadata"
    if not all(unique_success(jobs, name) for name in REQUIRED_JOBS):
        return False, "required_jobs_not_successful"
    core = next(job for job in jobs if job["name"] == "core-tests")
    contracts = next(job for job in jobs if job["name"] == "database-contracts")
    aggregate = next(job for job in jobs if job["name"] == "test")
    if not all(unique_success(contracts["steps"], name) for name in REQUIRED_CONTRACT_STEPS):
        return False, "missing_contract_test_evidence"
    if not unique_success(aggregate["steps"], "Require both independent test groups"):
        return False, "missing_aggregate_evidence"
    steps = core["steps"]
    marker = f"Reusable validation for PR #{number}"
    if not all(unique_success(steps, name) for name in REQUIRED_TEST_STEPS | {marker}):
        return False, "missing_exact_tree_or_test_evidence"
    return True, f"pr_{number}_run_{run_id}"


def main():
    reuse, reason = False, "manual_or_untrusted_context"
    try:
        if (os.environ.get("GITHUB_EVENT_NAME") == "push"
                and os.environ.get("GITHUB_REF") == "refs/heads/main"
                and os.environ.get("GITHUB_REPOSITORY") == REPOSITORY):
            sha = os.environ["GITHUB_SHA"]
            if subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == sha:
                tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip()
                token = os.environ["GH_TOKEN"]

                def get(path):
                    request = urllib.request.Request(
                        "https://api.github.com/repos/" + REPOSITORY + path,
                        headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                                 "X-GitHub-Api-Version": "2022-11-28"},
                    )
                    with urllib.request.urlopen(request, timeout=8) as response:
                        return json.load(response)

                reuse, reason = prove(get, sha, tree)
    except Exception:
        # Do not print HTTP response bodies, exception text or token values.
        reuse, reason = False, "verification_unavailable"
    print(f"PR_VALIDATION_REUSE reuse={str(reuse).lower()} reason={reason}")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"reuse={str(reuse).lower()}\n")


if __name__ == "__main__":
    main()
