# API production deploy cutover — private archive to public repository

## Scope and current situation (2026-10-09)

- **Public source:** `BraFurries/BraFurries-API`, protected `main`. Code content matches the private archive for the 497 unchanged blobs; differences concern docs / Maven wrapper line endings and the old deployment files.
- **Archived publisher frozen:** `BraFurries/Archive-BraFurries-API` (private) production workflow no longer accepts `push` after [PR #167](https://github.com/BraFurries/Archive-BraFurries-API/pull/167). Manual `workflow_dispatch` remains available as a recovery path. **Do not delete the archive or its history.**
- **Current production runtime:** Docker container on `127.0.0.1:18080`, reached via Apache, with GHCR `ghcr.io/brafurries/brafurries-api@sha256:...`. PM2 is already retired for the API.
- **Root-owned wrappers known on VM:** `/usr/local/sbin/deploy-brafurries-api` and `/usr/local/sbin/update-brafurries-api-env`. This change does not modify them.
- **Migration target:** public repository controls build, manual and optionally automatic deploy, with an isolated runner and explicit permissions. Front End Firebase deployment is a **later, separate** migration.

This PR **only prepares** the public workflow. It does not migrate an existing runner, grant access to a GHCR package, create a production environment, disable archived workflows, change DNS/Apache, run SQL or deploy.

## GitHub preconditions (operator check)

1. Confirm the public `main` still requires reviewed PRs, signed commits and the CI job `test`. Add `secret-scan` as a required check when the organization supports it.
2. Configure the public repository's **Environment `Produção`** with secret `PROD_ENV_FILE`; restrict deployments to `main`. Keep the secret outside Git and avoid printing its contents. Determine whether required reviewers will intentionally pause an automatic deployment.
3. Configure the existing `ghcr.io/brafurries/brafurries-api` **package's Actions access** so `BraFurries/BraFurries-API` can push using its `GITHUB_TOKEN`. Copying the Dockerfile does not migrate GHCR package ACLs. Check that the VM can pull by digest.
4. Create **dedicated runner group `api-production`**, accessible only to `BraFurries/BraFurries-API` and only the workflow `.github/workflows/prod-deploy.yaml@refs/heads/main`. Assign the runner labels `self-hosted` and `BRFAPI`. The workflow intentionally does not use the old archive runner as a fallback.
5. The VM runs **sudo 1.9.9**; do not use sudoers argument regex or permissive glob-based digest matching. Provision a distinct unprivileged runner `brfapi-runner`. Install the reviewed root-owned no-argument gateway `/usr/local/sbin/deploy-brafurries-api-public` from `BraFurries-Infrastructure` (root:root 0755) and its separate exact-command sudoers (root:root 0440). Allow only `update-brafurries-api-env stage/status/discard`, `deploy-brafurries-api status` and the gateway with **no CLI arguments** (sudoers `""`). The gateway reads the immutable image and GHCR username from bounded stdin lines and forwards only the remaining token data to the existing deploy wrapper. Never grant general root or Docker or install from an unreviewed commit.
6. The initial public workflow on `main` was **manual-only**. This follow-up change adds an **opt-in** `push` trigger guarded by the repository variable `BRF_API_AUTO_DEPLOY_ENABLED == 'true'`. The first public manual deployment succeeded in [run #37983891631](https://github.com/BraFurries/BraFurries-API/actions/runs/37983891631), including image publishing, health checks and CORS. Keep the repository variable set to `false` for this merge; switch to `true` only with separate authorization and a controlled follow-up push.

## Sequenced switchover (distinct approvals)

1. Read-only inventory: deployed image/status, public health, old workflow and runner, pending staging state, current production `main` SHA. **Do not read secret values or run tests against shared MariaDB**.
2. The public **manual-only** workflow was merged. Its initial merge could not trigger the production pipeline because it had no `push` event.
3. Configure package permissions, `Produção` environment and the restricted new runner. Verify the no-argument gateway is installed byte-for-byte from the approved Infrastructure commit, that `bash -n` and `visudo -cf` succeed on the VM and `sudo -l -U brfapi-runner` lists only the intended commands. Negative checks must never execute `stage` or `deploy` just for a permission test. Verify it is online and eligible. Do not run a deployment if any precondition is unverified.
4. The archive automatic `push` production trigger was frozen via [PR #167](https://github.com/BraFurries/Archive-BraFurries-API/pull/167) while retaining `workflow_dispatch` for authorized rollback. Before public manual deployment re-confirm no archive workflow is running/queued/waiting; never rely solely on a repository rename to remove workflow authority.
5. Run the **public** workflow via `workflow_dispatch` on protected `main`. GitHub-hosted jobs scan secrets, run isolated tests, build and push an immutable GHCR digest; the restricted runner stages the production env and calls only root-owned wrappers.
6. Verify local readiness, `/meta/locales`, local Preview CORS and public Cloudflare CORS, as well as Docker image digest, runner state and environment cleanup. A failed **post-deploy** smoke test does not necessarily roll back the container; investigate and use an explicitly approved manual recovery if needed.
7. Stop/deregister the retired archive production runner only after the new route is validated and the archive workflow is frozen. Preserve historic config backups, branches and reviews.
8. After the public manual deployment is fully validated, merge this **separate protected-main opt-in PR** (while the repository variable stays `false`). Once the PR itself passes required checks, confirm production jobs were skipped for its `push` merge. Then, with fresh rollout authorization, change repository variable `BRF_API_AUTO_DEPLOY_ENABLED` to `true` and validate a subsequent controlled `main` push. Setting the variable does not retroactively start deploys.
9. Leave `BraFurries/BraFurries-Infrastructure` host script import / hardening for a dedicated follow-up with a separate rollout; merging Infrastructure never changes VM files.

## Recovery and operational constraints

- The automatic trigger is opted out unless **repository variable** `BRF_API_AUTO_DEPLOY_ENABLED` is exactly `true`; manual `workflow_dispatch` works regardless of its value. For emergency pause, set the variable to `false` (does **not** cancel in-flight jobs); inspect, cancel/settle existing runs if necessary, and verify staged environments. Roll back code through reviewed PRs, never loosen runner group security.
- Prefer rolling back through the production Docker wrapper to a known previous immutable digest. Wrapper rollback applies only to errors during the wrapper's deployment phase; database changes and external API contract changes are not automatically reverted.
- The API **never owns MariaDB schema**. Apply compatible Flyway changes from `BraFurries-Database` before introducing a dependent API build. Preserve Community tenant isolation, authorization and auditing.
- Deploy secrets should be passed only to the restricted runner and root-owned wrappers, never to PR jobs or repository files.
- If package access, runner group restrictions, `PROD_ENV_FILE`, readiness or archive freeze cannot be verified, **stop before production deployment**.
