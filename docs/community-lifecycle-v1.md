# Community lifecycle V1

Community availability is derived from an active external-network integration. In V1 the operational integration is exactly one Discord guild per Community. Inactive integrations retain their Community-scoped data, but ordinary `/user` authorization and listing paths fail closed until an integration is active again. Platform administrative/debug paths under `/admin` remain separate.

Coddy reports live guild state to authenticated `/internal/community-networks/discord/{guildId}` endpoints. The API owns all mutations of `communities`, `community_discord`, `user_community_status`, `community_audit_log`, and `community_network_sync_runs`. User-facing Community creation and public ownership claim do not exist.

`communities.owner_user_id` follows the User resolved from the live Discord guild-owner snowflake. A changed observation transfers ownership. An unresolved owner clears the previous User authority immediately while preserving the observed Discord owner on `community_discord.discord_admin_id`. Repeated observations are idempotent. Ownership changes create durable sync-run evidence and Community audit entries whenever an affected/resolved User can satisfy the existing non-null audit actor contract.

Member snapshots are tenant-bound by the authenticated guild path. Presence reconciliation requires an explicit `complete=true`; incomplete snapshots are rejected and cannot mark anyone absent. When a snapshot references a sync run, that run must already be `RUNNING`. Network-scoped current membership lives in `community_network_member_status`, while `user_community_status` remains the Community-level aggregate/history. Incremental network events are the primary synchronization path after a trusted baseline; complete scans are for bootstrap, recovery, manual reconciliation, and detected drift. The runtime queue contract is also API-owned: user/admin requests reuse an existing `RUNNING` or `QUEUED` run for the Discord guild instead of duplicating work, and Coddy claims queued work through `POST /internal/community-networks/discord/sync-runs/claim`; a claim atomically moves the oldest pending Discord run to `RUNNING` and returns 204 when no work is pending. Network membership rows capture Portaria applicability only from explicit evidence. The schema migration never guesses legacy `approval_required`, and a trusted snapshot does not retroactively infer that old members were subject to Portaria merely because Portaria is enabled now. Existing explicit legacy approval evidence is preserved during first network bootstrap. Guild display names are written only to `user_community_status.display_name`; Discord global identity remains in `user_discord`, and external synchronization never mutates `users.display_name`.

`portaria_base_config.portaria_enabled` is the explicit admission lifecycle flag. It is independent from `formulario_portaria_ativo`. No configuration row means disabled. The Database migration preserves an existing Portaria as enabled only when that guild already has a persisted Portaria flow.

The canonical membership state precedence is:

1. banned;
2. left;
3. unknown presence/legacy data;
4. active when Portaria is disabled or approval was not required;
5. pending/active from approval only when Portaria is enabled and approval was required.

## Future network split rule

When multiple network types are supported, an ownership change affecting only one network must detach that network into a new Community. The original Community keeps only networks that still belong to its original owner. Community-scoped data must not be copied between the resulting tenants. V1 deliberately does not emulate multiple Discord integrations per Community and does not implement the split yet.

## Rollout

The lifecycle foundation from Database PR #24 must already be present. For the event-driven membership model, apply `BraFurries-Database/flyway/sql/versioned/V20261002_002__add_network_member_state_and_events.sql` (Database PR #25) **before** deploying API PR #136. That migration adds network-scoped membership state/history plus synchronization-health fields on `community_discord`; Hibernate is configured with `ddl-auto=none`, so deploying API #136 first would fail ordinary guild reads with missing-column/table errors.

After Database #25 is applied, deploy API #136, then deploy the Coddy runtime that consumes the new contracts. Existing Community-level rows are not copied into a network during migration; the first authoritative network reconciliation establishes that network baseline. The migration is additive and preserves prior Community history.
