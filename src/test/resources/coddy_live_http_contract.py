"""Real, guarded Coddy-to-Spring HTTP contract test.

Executed exclusively by the API Testcontainers CI job, from a pinned Coddy
checkout. No bot.run, real Discord, production secrets or shared database.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import os
from types import SimpleNamespace

from core.community_lifecycle_api import (
    CommunityLifecycleApiClient,
    CommunityLifecycleApiError,
)
from core.community_reconciliation import reconcile_guild


class SyntheticGuild:
    def __init__(self, guild_id: int, members: list, owner_id: int):
        self.id = guild_id
        self.name = f"CI-only guild {guild_id}"
        self.owner_id = owner_id
        self.owner = None
        self.member_count = len(members)
        self._members = members

    async def fetch_members(self, limit=None):
        assert limit is None
        for member in self._members:
            yield member


def synthetic_member(user_id: int, guild_id: int):
    return SimpleNamespace(
        id=user_id,
        name=f"ci-user-{user_id}",
        global_name="Synthetic shared member",
        display_name="Synthetic shared member",
        joined_at=datetime(2026, 10, 20, 12, 0, tzinfo=timezone.utc),
        guild=SimpleNamespace(id=guild_id),
        roles=[],
    )


async def verify(base_url: str, token: str) -> None:
    guild_a = SyntheticGuild(991001, [synthetic_member(771001, 991001)], 700001)
    guild_b = SyntheticGuild(991002, [synthetic_member(771001, 991002)], 700002)

    bad = CommunityLifecycleApiClient(base_url=base_url, token="invalid-synthetic-token")
    try:
        await bad.observe_network(guild_a, active=True)
    except CommunityLifecycleApiError as exc:
        assert exc.status == 401 and not exc.retryable, (exc.status, exc.retryable)
    else:
        raise AssertionError("invalid internal Bearer token accepted")

    client = CommunityLifecycleApiClient(base_url=base_url, token=token)
    a = await reconcile_guild(
        guild_a, client, trigger="STARTUP", approved_resolver=lambda _member: True
    )
    b = await reconcile_guild(
        guild_b, client, trigger="MANUAL", approved_resolver=lambda _member: False
    )
    assert a["communityId"] != b["communityId"], (a, b)
    assert (a["guildId"], a["observedMembers"]) == ("991001", 1), a
    assert (b["guildId"], b["observedMembers"]) == ("991002", 1), b

    # A sync run from guild B must never be usable to finalize guild A.
    other_run = await client.create_sync_run(guild_b.id, "MANUAL")
    run_id = other_run["runId"]
    try:
        await client.finalize_member_snapshot(
            guild_a.id, run_id=run_id, observed_members=1
        )
    except CommunityLifecycleApiError as exc:
        assert exc.status == 404 and not exc.retryable, exc.status
    else:
        raise AssertionError("cross-guild run was accepted")

    await client.finish_sync_run(
        guild_b.id, run_id, success=False, error_code="CI_CROSS_GUILD_NEGATIVE_TEST"
    )
    # The failed membership run correctly degrades guild B only. Reconcile
    # again to prove recovery reaches HEALTHY without mutating guild A.
    recovered = await reconcile_guild(
        guild_b, client, trigger="RECOVERY", approved_resolver=lambda _member: False
    )
    assert recovered["communityId"] == b["communityId"], recovered
    assert recovered["guildId"] == "991002", recovered
    assert recovered["observedMembers"] == 1, recovered
    print("CODDY_LOOPBACK_HTTP_CONTRACT_PASSED")


def main() -> None:
    if os.environ.get("BRF_CODDY_LIVE_HTTP_INTEGRATION") != "true":
        raise SystemExit("opt-in integration flag required")
    port = os.environ.get("BRF_LOOPBACK_TEST_PORT", "")
    token = os.environ.get("BRF_LOOPBACK_TEST_TOKEN", "")
    if not port.isascii() or not port.isdecimal() or not (1 <= int(port) <= 65535):
        raise SystemExit("invalid loopback test port")
    if token != "brf-loopback-test-only-token":
        raise SystemExit("refusing non-synthetic credentials")
    asyncio.run(verify(f"http://127.0.0.1:{port}", token))


if __name__ == "__main__":
    main()
