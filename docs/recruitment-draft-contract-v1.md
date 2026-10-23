# BraFurries recruitment — draft management contract (first API slice)

## Delivery boundary
This changes API source but is **disabled by default** and **does not publish
selection processes or allow candidate applications**. It requires the five
tables from [Database PR #12](https://github.com/BraFurries/BraFurries-Database/pull/12),
migration `V20261021_001__create_recruitment_foundation.sql`.
Those tables do not yet exist in production.

This is a testable first administrative API slice, **not** completion of
BraFurries-API#6 or BraFurries-Front-End#11.

## Fail-closed rollout and tenancy
- `RECRUITMENT_ENABLED=false` (default): every draft endpoint returns 404
  without touching the recruitment tables.
- `RECRUITMENT_COMMUNITY_ID=0` (default): also blocks all access.
- An operator must explicitly configure **both** to enable the authorized
  BraFurries Community, after checking its canonical ID and deployed schema.
- Unlike a community name comparison, the explicit numeric deployment setting
  is an exact Community identifier, not embedded in source code.
- Access check: authenticated user + community membership/owner resolution +
  `RECRUITMENT_MANAGE_PROCESSES` granted by an internal Team role or direct
  Community grant. Role *hierarchy* never automatically widens this permission.
- Every SQL lookup and mutation is bound to `community_id`; cross-community
  identifiers return 404. The database composite FKs provide independent guards.
- UI visibility and the flag do **not** replace server authorization.

## Administrative routes (draft-only)
Base: `/user/me/communities/{communityId}/recruitment/processes`

| Verb | Path | Behavior |
| --- | --- | --- |
| GET | `/` | List this Community's process summaries, including draft version number |
| GET | `/{processId}` | Process summary and **draft** stages |
| POST | `/` | Create process (DRAFT/PRIVATE/CLOSED) and draft version 1 |
| PUT | `/{processId}` | Change title/description only while process DRAFT |
| POST | `/{processId}/stages` | Append a stage at the next ordered position in the current draft |

Example POST body: `{"title":"Seleção de colaboradores","description":"Processo interno"}`

Example stage body:
`{"internalName":"Triagem pela Staff","publicName":"Em análise","stageType":"ANALYSIS"}`

Accepted stage types match Database PR #12: APPLICATION, ANALYSIS,
HUMAN_EVALUATION, AUTO_EVALUATION, INTERVIEW, DECISION, CUSTOM.

- Published version mutation is rejected (409); unknown process is 404.
- Stage positions are allocated in a transaction with a DB lock on the draft
  process row to avoid duplicate concurrent position allocation.
- Creation and updates record Community audit events. No raw candidate data,
  answers, private judgments or gabaritos are involved in this API slice.
- Candidate/staff forms remain separate nullable columns but **are not**
  writable through these endpoints yet; typed contracts and validation come
  before publication to avoid leaking secret answer keys.

## Deferred in separate small PRs
- Stage editing/reordering/removal, form definitions and validation.
- Publication gate (requires meaningful stages, eligibility, correct typed
  forms, version freeze, legal terms, quotas and rollout audit).
- Candidate-facing eligibility/inscription/own-status contracts.
- Attempts, automatic grading, collective voting, appeals and notifications.
- Process-specific grants and per-stage delegation; this first slice grants
  `RECRUITMENT_MANAGE_PROCESSES` at the Community scope only.

## Tests and rollout
- Focused JUnit tests cover disabled and missing configuration, wrong tenant,
  capability rejection before database access, scoped draft creation, audit,
  and non-draft mutation conflicts.
- New SQL integrity tests live in Database PR #12 (MariaDB 10.6 disposable).
- The API's existing CI intentionally continues to use the pinned **107-table**
  baseline for legacy JPA and Coddy HTTP regression tests. JDBC recruitment
  endpoints remain disabled, so those tests cannot accidentally depend on the
  unmerged database migration.
- Do not merge/deploy or enable this API solely because its Java CI is green.
  Before activation: merge/review Database + apply its canonical migration
  via a separately approved, fingerprint-checked production procedure, verify
  schema compatibility and only then explicitly enable the BraFurries tenant.
