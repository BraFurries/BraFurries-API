# API production deploy cutover — private archive to public repository

## Scope and current situation (2026-10-09)

- **Public source:** `BraFurries/BraFurries-API`, protected `main`. Code content matches the private archive for the 497 unchanged blobs; differences concern docs / Maven wrapper line endings and the old deployment files.
- **Current production publisher:** `BraFurries/Archive-BraFurries-API` (private) workflow `.github/workflows/prod-deploy.yaml` on pushes to `main`. **Do not delete the archived repo or its history.**
- **Current production runtime:** Docker container on `127.0.0.1:18080`, reached via Apache, with GHCR `ghcr.io/brafurries/brafurries-api@sha256:...`. PM2 is already retired for the API.
- **Root-owned wrappers known on VM:** `/usr/local/sbin/deploy-brafurries-api` and `/usr/local/sbin/update-brafurries-api-env`. This change does not modify them.
- **Migration target:** public repository controls build, manual and optionally automatic deploy, with an isolated runner and explicit permissions. Front End Firebase deployment is a **later, separate** migration.

This PR **only prepares** the public workflow. It does not migrate an existing runner, grant access to a GHCR package, create a production environment, disable archived workflows, change DNS/Apache, run SQL or deploy.

## GitHub preconditions (operator check)

1. Confirm the public `main` still requires reviewed PRs, signed commits and the CI job `test`. Add `secret-scan` as a required check when the organization supports it.
2. Configure the public repository's **Environment `Produção`** with secret `PROD_ENV_FILE`; restrict deployments to `main`. Keep the secret outside Git and avoid printing its contents. Determine whether required reviewers will intentionally pause an automatic deployment.
3. Configure the existing `ghcr.io/brafurries/brafurries-api` **package's Actions access** so `BraFurries/BraFurries-API` can push using its `GITHUB_TOKEN`. Copying the Dockerfile does not migrate GHCR package ACLs. Check that the VM can pull by digest.
4. Create **dedicated runner group `api-production`**, accessible only to `BraFurries/BraFurries-API` and only the workflow `.github/workflows/prod-deploy.yaml@refs/heads/main`. Assign the runner labels `self-hosted` and `BRFAPI`. The workflow intentionally does not use the old archive runner as a fallback.
5. The VM runs **sudo 1.9.9**; do not use sudoers argument regex or permissive glob-based digest matching. Provision a distinct unprivileged runner `brfapi-public-runner`. Install the reviewed root-owned no-argument gateway `/usr/local/sbin/deploy-brafurries-api-public` from `BraFurries-Infrastructure` (root:root 0755) and its separate exact-command sudoers (root:root 0440). Allow only `update-brafurries-api-env stage/status/discard`, `deploy-brafurries-api status` and the gateway with **no CLI arguments** (sudoers `""`). The gateway reads the immutable image and GHCR username from bounded stdin lines and forwards only the remaining token data to the existing deploy wrapper. Never grant general root or Docker or install from an unreviewed commit.
6. The initial public workflow is **manual-only**. It contains no `push` trigger. Do not introduce automatic production deployment until after the cutover in a separate reviewed PR.

## Sequenced switchover (distinct approvals)

1. Read-only inventory: deployed image/status, public health, old workflow and runner, pending staging state, current production `main` SHA. **Do not read secret values or run tests against shared MariaDB**.
2. Merge the public **manual-only** workflow. The merge into `main` cannot trigger its production pipeline because that workflow has no `push` event.
3. Configure package permissions, `Produção` environment and the restricted new runner. Verify the no-argument gateway is installed byte-for-byte from the approved Infrastructure commit, that `bash -n` and `visudo -cf` succeed on the VM and `sudo -l -U brfapi-public-runner` lists only the intended commands. Negative checks must never execute `stage` or `deploy` just for a permission test. Verify it is online and eligible. Do not run a deployment if any precondition is unverified.
4. **Freeze the archive's automatic `push` deployment trigger** (via reviewed archive change removing `push`; keep `workflow_dispatch` temporarily for rollback) before the new manual deployment. Ensure no archive workflow is running, queued or capable of re-deploying an older image. Never rely solely on a repository rename to remove workflow authority.
5. Run the **public** workflow via `workflow_dispatch` on protected `main`. GitHub-hosted jobs scan secrets, run isolated tests, build and push an immutable GHCR digest; the restricted runner stages the production env and calls only root-owned wrappers.
6. Verify local readiness, `/meta/locales`, local Preview CORS and public Cloudflare CORS, as well as Docker image digest, runner state and environment cleanup. A failed **post-deploy** smoke test does not necessarily roll back the container; investigate and use an explicitly approved manual recovery if needed.
7. Stop/deregister the retired archive production runner only after the new route is validated and the archive workflow is frozen. Preserve historic config backups, branches and reviews.
8. After the public manual deployment is fully validated, enable automatic deployment in **a separate protected-main PR** by adding a `push` trigger with appropriate guards. Do not rely on a repository variable alone; verify all migration gates before enabling. Validate the next controlled main push.
9. Leave `BraFurries/BraFurries-Infrastructure` host script import / hardening for a dedicated follow-up with a separate rollout; merging Infrastructure never changes VM files.

## Recovery and operational constraints

- Until a later approved PR introduces automatic triggers, this workflow is manual-only. After enabling the automatic trigger, pause future deployments using the trigger's reviewed opt-in guard or a controlled revert; disabling future triggers does **not** cancel in-flight jobs. Inspect and safely settle existing runs, including staged environments.
- Prefer rolling back through the production Docker wrapper to a known previous immutable digest. Wrapper rollback applies only to errors during the wrapper's deployment phase; database changes and external API contract changes are not automatically reverted.
- The API **never owns MariaDB schema**. Apply compatible Flyway changes from `BraFurries-Database` before introducing a dependent API build. Preserve Community tenant isolation, authorization and auditing.
- Deploy secrets should be passed only to the restricted runner and root-owned wrappers, never to PR jobs or repository files.
- If package access, runner group restrictions, `PROD_ENV_FILE`, readiness or archive freeze cannot be verified, **stop before production deployment**.
