# Recruitment — candidate receipts and read-only Staff triage

This is an incremental API-only delivery, stacked on API PR #31 (candidate
intake) and Database PR #14 (answer storage). It does not require any new
migration. It is **not** the human assessment/voting/result-publication engine.

## Authority and isolation
- `RecruitmentFeatureGate` remains disabled by default and restricted to
  the explicitly configured canonical BraFurries Community ID.
- Own-status endpoints use the resolved authenticated Community user ID
  **and** require `activeMember`; application IDs supplied by clients can
  never select records belonging to someone else.
- A distinct Community capability, `RECRUITMENT_REVIEW_APPLICATIONS`,
  can be granted through a Community Team role or directly to a user.
  `RECRUITMENT_MANAGE_PROCESSES` and
  `RECRUITMENT_PUBLISH_PROCESSES` do not imply reviewer access.
- All SQL includes `community_id` and joins the process/application
  version within the same tenant. Results are sorted by immutable IDs.
- Reviewer queue excludes the reviewer's own candidature; the privileged
  detail endpoint additionally refuses it with 403.
- Privileged reads of submitted answers create an audit event
  `RECRUITMENT_APPLICATION_VIEWED`, including actor, tenant and
  application ID without storing private answer content in the audit log.

## Endpoints

Base: `/user/me/communities/{communityId}/recruitment/applications`

| Method | Route | Authorized response |
| --- | --- | --- |
| GET | `/mine?beforeId=...` | Candidate's own application receipt list |
| GET | `/mine/{applicationId}` | Candidate's own receipt; other identities return 404 |
| GET | `/review?processId=N&beforeId=...` | Reviewer-scoped queue, max 100 per page |
| GET | `/review/{applicationId}` | Privileged staff view of candidate's submitted form and stage statuses |

- Each list uses cursor pagination `id < beforeId` with a maximum
  of 100 records, no unbounded all-candidates query.
- **Candidate receipts deliberately expose `publicStatus=RECEIVED`
  only**, regardless of private statuses (e.g., AWAITING_PUBLICATION,
  APPROVED or REJECTED). Individual stages, grade, staff evaluation,
  vote, result and gabarito are excluded from candidate DTOs.
- The reviewer read-only detail contains an applicant ID, process,
  internal application/stage statuses and only the stored
  `recruitment_application_answers.answers_json` for the
  first APPLICATION stage. It does **not** return `staff_form_json`,
  `evaluation_config_json`, grades or correct answers.
- Displaying candidate answers does not constitute evaluation,
  approval, voting, stage advancement or result publication.
- Responses are obtained from the persisted application even after
  enrollment closes; no dependency on `PUBLIC/OPEN` read filters.

## Verification
- Unit tests: tenant gate, inactive candidate, self-only lookup, separate
  reviewer capability, self-review denial, audit and redaction.
- MariaDB 10.6 / Testcontainers with SHA-pinned canonical baseline and
  three recruitment migrations: self-only receipts, private-status
  non-disclosure, tenant isolation, actual submitted JSON reads,
  self-exclusion and cursor behavior.
- The usual legacy Java tests, Coddy↔API HTTP contract, package and
  secret scan run in GitHub Actions, without a shared datasource.
- No migration, DML or production activation has been executed.
- Front UI for tracking/triage, staff assignments, evaluations, voting,
  deadlines, appeal and results will be separate PRs after review.
