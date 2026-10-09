package com.Brafurries.API.internal;

import static com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityNetworkMemberEvent;
import com.Brafurries.API.entity.community.CommunityNetworkMemberStatus;
import com.Brafurries.API.entity.community.CommunityNetworkSyncRun;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityNetworkMemberEventRepository;
import com.Brafurries.API.repository.community.CommunityNetworkMemberStatusRepository;
import com.Brafurries.API.repository.community.CommunityNetworkSyncRunRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.BackupControlPlaneStore;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityMembershipAggregateService;
import com.Brafurries.API.user.CommunityNetworkAvailabilityService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityNetworkLifecycleServiceTest {
    @Mock CommunityRepository communities;
    @Mock CommunityDiscordRepository discord;
    @Mock CommunityNetworkSyncRunRepository runs;
    @Mock CommunityNetworkMemberStatusRepository networkMemberships;
    @Mock CommunityNetworkMemberEventRepository networkEvents;
    @Mock CommunityAuditLogRepository audit;
    @Mock UserRepository users;
    @Mock UserDiscordRepository userDiscord;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityNetworkAvailabilityService availability;
    @Mock CommunityAuthorizationService authorization;
    @Mock BackupControlPlaneStore backupControlPlaneStore;

    private CommunityNetworkLifecycleService service;
    private Community community;
    private CommunityDiscord link;
    private final Map<Integer, CommunityNetworkMemberStatus> networkStateByUser = new HashMap<>();

    @BeforeEach
    void setUp() {
        networkStateByUser.clear();
        CommunityMembershipAggregateService aggregateService =
            new CommunityMembershipAggregateService(networkMemberships, memberships);
        service = new CommunityNetworkLifecycleService(
            communities,
            discord,
            runs,
            networkMemberships,
            networkEvents,
            audit,
            users,
            userDiscord,
            memberships,
            availability,
            authorization,
            aggregateService,
            backupControlPlaneStore
        );
        community = new Community();
        community.setId(10);
        community.setName("Community A");
        link = new CommunityDiscord();
        link.setId(20);
        link.setCommunity(community);
        link.setGuildId(100L);
        link.setName("Guild A");
        link.setActive(true);
        link.setUsersQuantity(1);
        link.setMembershipSyncState(MembershipSyncState.RECONCILIATION_REQUIRED.name());

        lenient().when(discord.findByGuildIdForUpdate(100L)).thenReturn(Optional.of(link));
        lenient().when(availability.isPortariaEnabled(100L)).thenReturn(true);
        lenient().when(discord.save(any(CommunityDiscord.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(runs.save(any(CommunityNetworkSyncRun.class))).thenAnswer(invocation -> {
            CommunityNetworkSyncRun run = invocation.getArgument(0);
            if (run.getId() == null) run.setId(500L);
            return run;
        });
        lenient().when(networkMemberships.save(any(CommunityNetworkMemberStatus.class)))
            .thenAnswer(invocation -> {
                CommunityNetworkMemberStatus state = invocation.getArgument(0);
                if (state.getId() == null) {
                    state.setId((long) state.getUser().getId());
                }
                networkStateByUser.put(state.getUser().getId(), state);
                return state;
            });
        lenient().when(networkMemberships.findByNetworkTypeAndExternalNetworkIdAndUserId(
            anyString(), anyLong(), anyInt()
        )).thenAnswer(invocation ->
            Optional.ofNullable(networkStateByUser.get(invocation.getArgument(2)))
        );
        lenient().when(networkMemberships.findByCommunityIdAndUserIdOrderByIdAsc(
            anyInt(), anyInt()
        )).thenAnswer(invocation -> {
            CommunityNetworkMemberStatus state =
                networkStateByUser.get(invocation.getArgument(1));
            return state == null ? List.of() : List.of(state);
        });
        lenient().when(networkMemberships.findByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(
            anyString(), anyLong()
        )).thenAnswer(invocation ->
            networkStateByUser.values().stream()
                .filter(state -> Boolean.TRUE.equals(state.getIsPresent()))
                .toList()
        );
        lenient().when(networkMemberships.findByNetworkTypeAndExternalNetworkIdAndUserIdIn(
            anyString(), anyLong(), anyCollection()
        )).thenAnswer(invocation -> {
            java.util.Collection<Integer> userIds = invocation.getArgument(2);
            return networkStateByUser.values().stream()
                .filter(state -> userIds.contains(state.getUser().getId()))
                .toList();
        });
        lenient().when(networkMemberships.countByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(
            anyString(), anyLong()
        )).thenAnswer(invocation ->
            networkStateByUser.values().stream()
                .filter(state -> Boolean.TRUE.equals(state.getIsPresent()))
                .count()
        );
        lenient().when(networkMemberships.countObservedInRun(
            anyString(), anyLong(), anyLong()
        )).thenAnswer(invocation -> {
            Long runId = invocation.getArgument(2);
            return networkStateByUser.values().stream()
                .filter(state ->
                    state.getLastSyncRun() != null
                        && runId.equals(state.getLastSyncRun().getId())
                )
                .count();
        });
        lenient().when(networkMemberships.findPresentNotObservedInRun(
            anyString(), anyLong(), anyLong(), any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            Long runId = invocation.getArgument(2);
            LocalDateTime reconciliationStartedAt = invocation.getArgument(3);
            return networkStateByUser.values().stream()
                .filter(state -> Boolean.TRUE.equals(state.getIsPresent()))
                .filter(state -> !state.getLastObservedAt().isAfter(reconciliationStartedAt))
                .filter(state ->
                    state.getLastSyncRun() == null
                        || !runId.equals(state.getLastSyncRun().getId())
                )
                .toList();
        });
        lenient().when(networkEvents.save(any(CommunityNetworkMemberEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void newGuildBootstrapSurvivesBackupPresenceFailure() {
        when(discord.findByGuildIdForUpdate(100L)).thenReturn(Optional.empty());
        when(communities.save(any(Community.class))).thenAnswer(invocation -> {
            Community saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(77);
            }
            return saved;
        });
        doThrow(new IllegalStateException("backup schema unavailable"))
            .when(backupControlPlaneStore)
            .observeGuildBackupPresence(100L, true);

        DiscordNetworkResponse response = assertDoesNotThrow(() ->
            service.observeDiscordNetwork(
                "100",
                new DiscordNetworkObservationRequest("Guild nova", "111", true, 42)
            )
        );

        assertEquals(77, response.communityId());
        assertEquals("100", response.guildId());
        assertTrue(response.active());
        assertEquals("111", response.ownerDiscordUserId());
        assertNull(response.ownerUserId());
        verify(discord, atLeastOnce()).save(argThat(saved ->
            saved.getCommunity() != null
                && Integer.valueOf(77).equals(saved.getCommunity().getId())
                && Long.valueOf(100L).equals(saved.getGuildId())
                && "Guild nova".equals(saved.getName())
                && Boolean.TRUE.equals(saved.getActive())
                && Integer.valueOf(42).equals(saved.getUsersQuantity())
        ));
        verify(backupControlPlaneStore).observeGuildBackupPresence(100L, true);
    }

    @Test
    void inactiveNetworkObservationSchedulesSevenDayBackupGraceInControlPlane() {
        service.observeDiscordNetwork(
            "100",
            new DiscordNetworkObservationRequest("Guild A", "111", false, 0)
        );

        verify(backupControlPlaneStore).observeGuildBackupPresence(100L, false);
    }

    @Test
    void activeNetworkObservationCancelsPendingBackupPurgeWithoutResettingPolicy() {
        service.observeDiscordNetwork(
            "100",
            new DiscordNetworkObservationRequest("Guild A", "111", true, 0)
        );

        verify(backupControlPlaneStore).observeGuildBackupPresence(100L, true);
    }

    @Test
    void liveDiscordOwnerTransferMutatesAuthorityAndIsAudited() {
        User oldOwner = user(1, "Old owner");
        User newOwner = user(2, "New owner");
        community.setOwnerUser(oldOwner);
        link.setDiscordAdminId(111L);
        when(userDiscord.findByDiscordUserId(222L)).thenReturn(Optional.of(identity(newOwner, 222L)));

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("222")
        );

        assertTrue(response.ownershipChanged());
        assertEquals(2, response.ownerUserId());
        assertSame(newOwner, community.getOwnerUser());
        assertEquals(222L, link.getDiscordAdminId());
        verify(audit).save(argThat(entry ->
            "COMMUNITY_OWNER_SYNCHRONIZED".equals(entry.getAction())
                && entry.getMetadata().contains("\"previousOwnerUserId\":1")
                && entry.getMetadata().contains("\"ownerUserId\":2")
        ));
        verify(runs).save(argThat(run -> "OWNERSHIP_CHANGE".equals(run.getTrigger())));
    }

    @Test
    void unresolvedExternalOwnerImmediatelyRemovesPreviousAuthority() {
        User oldOwner = user(1, "Old owner");
        community.setOwnerUser(oldOwner);
        link.setDiscordAdminId(111L);
        when(userDiscord.findByDiscordUserId(999L)).thenReturn(Optional.empty());

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("999")
        );

        assertNull(community.getOwnerUser());
        assertNull(response.ownerUserId());
        assertEquals("999", response.ownerDiscordUserId());
        verify(audit).save(argThat(entry -> "COMMUNITY_OWNER_UNRESOLVED".equals(entry.getAction())));
        verify(runs).save(argThat(run -> "OWNER_USER_UNRESOLVED".equals(run.getErrorCode())));
    }

    @Test
    void repeatedOwnershipObservationIsIdempotent() {
        User owner = user(1, "Owner");
        community.setOwnerUser(owner);
        link.setDiscordAdminId(111L);
        when(userDiscord.findByDiscordUserId(111L)).thenReturn(Optional.of(identity(owner, 111L)));

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("111")
        );

        assertFalse(response.ownershipChanged());
        assertFalse(response.membershipReconciliationRunning());
        verifyNoInteractions(audit, communities);
        verify(runs, never()).save(any());
        verify(discord, never()).save(any());
    }

    @Test
    void networkHealthExposesRunningMembershipReconciliationEvenWhenHealthy() {
        User owner = user(1, "Owner");
        community.setOwnerUser(owner);
        link.setDiscordAdminId(111L);
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        CommunityNetworkSyncRun stale = runningRun(908L, SyncTrigger.MANUAL);
        when(userDiscord.findByDiscordUserId(111L)).thenReturn(Optional.of(identity(owner, 111L)));
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(stale));

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("111")
        );

        assertEquals(MembershipSyncState.HEALTHY, response.membershipSyncState());
        assertTrue(response.membershipReconciliationRunning());
        verify(runs, never()).save(stale);
    }

    @Test
    void networkHealthFindsMembershipRunEvenWhenNonMembershipRunIsOlder() {
        User owner = user(1, "Owner");
        community.setOwnerUser(owner);
        link.setDiscordAdminId(111L);
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        CommunityNetworkSyncRun ownership = runningRun(907L, SyncTrigger.OWNERSHIP_CHANGE);
        CommunityNetworkSyncRun recovery = runningRun(908L, SyncTrigger.RECOVERY);
        when(userDiscord.findByDiscordUserId(111L)).thenReturn(Optional.of(identity(owner, 111L)));
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(ownership, recovery));

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("111")
        );

        assertTrue(response.membershipReconciliationRunning());
    }

    @Test
    void networkHealthSelfHealsRunStartedBeforeCompletedSnapshotWatermark() {
        User owner = user(1, "Owner");
        community.setOwnerUser(owner);
        link.setDiscordAdminId(111L);
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        link.setLastFullReconciliationAt(LocalDateTime.of(2026, 10, 4, 18, 36));
        CommunityNetworkSyncRun stale = runningRun(326L, SyncTrigger.RECOVERY);
        stale.setStartedAt(LocalDateTime.of(2026, 10, 4, 18, 13));
        when(userDiscord.findByDiscordUserId(111L)).thenReturn(Optional.of(identity(owner, 111L)));
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(stale), List.of());

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("111")
        );

        assertFalse(response.membershipReconciliationRunning());
        assertEquals(SyncStatus.FAILED.name(), stale.getStatus());
        assertEquals("SUPERSEDED_BY_COMPLETED_SNAPSHOT", stale.getErrorCode());
        assertNotNull(stale.getCompletedAt());
        verify(runs).save(stale);
    }

    @Test
    void networkHealthSelfHealNeverClosesRunStartedAfterCompletedSnapshotWatermark() {
        User owner = user(1, "Owner");
        community.setOwnerUser(owner);
        link.setDiscordAdminId(111L);
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        link.setLastFullReconciliationAt(LocalDateTime.of(2026, 10, 4, 18, 36));
        CommunityNetworkSyncRun newer = runningRun(327L, SyncTrigger.MANUAL);
        newer.setStartedAt(LocalDateTime.of(2026, 10, 4, 18, 37));
        when(userDiscord.findByDiscordUserId(111L)).thenReturn(Optional.of(identity(owner, 111L)));
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(newer));

        DiscordNetworkResponse response = service.observeDiscordOwnership(
            "100",
            new DiscordOwnershipObservationRequest("111")
        );

        assertTrue(response.membershipReconciliationRunning());
        assertEquals(SyncStatus.RUNNING.name(), newer.getStatus());
        assertNull(newer.getCompletedAt());
        verify(runs, never()).save(newer);
    }

    @Test
    void snapshotIsBoundToGuildTenantAndNeverOverwritesPlatformDisplayName() {
        User member = user(7, "Platform Fox");
        UserDiscord identity = identity(member, 700L);
        identity.setUsername("old-network-name");
        UserCommunityStatus membership = membership(member, community, false);
        when(userDiscord.findByDiscordUserId(700L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(member.getId(), community.getId())).thenReturn(List.of(membership));

        MemberSnapshotResponse response = service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("700", "fox-network", "Fox Global", "Fox Guild", false, null)
            ))
        );

        assertEquals(10, response.communityId());
        assertEquals("Platform Fox", member.getDisplayName());
        assertEquals("Fox Guild", membership.getDisplayName());
        assertFalse(membership.getApprovalRequired());
        assertSame(community, membership.getCommunity());
        verify(memberships, atLeastOnce())
            .findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10);
    }

    @Test
    void memberCreatedWhilePortariaIsDisabledKeepsApprovalNotRequiredLater() {
        when(availability.isPortariaEnabled(100L)).thenReturn(false, true);
        User member = user(8, "Platform Name");
        UserDiscord identity = identity(member, 800L);
        when(userDiscord.findByDiscordUserId(800L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(member.getId(), community.getId())).thenReturn(List.of());
        when(memberships.save(any(UserCommunityStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("800", "network", null, "Guild Name", false, LocalDateTime.now())
            ))
        );

        ArgumentCaptor<UserCommunityStatus> captured = ArgumentCaptor.forClass(UserCommunityStatus.class);
        verify(memberships).save(captured.capture());
        UserCommunityStatus created = captured.getValue();
        assertFalse(created.getApprovalRequired());

        clearInvocations(memberships);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(member.getId(), community.getId())).thenReturn(List.of(created));

        service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("800", "network", null, "Guild Name", false, null)
            ))
        );

        assertFalse(created.getApprovalRequired());
    }

    @Test
    void brandNewMemberInEnabledPortariaStartsPendingEvenBeforeVisitorRoleArrives() {
        when(availability.isPortariaEnabled(100L)).thenReturn(true);
        User member = user(14, null);
        UserDiscord identity = identity(member, 1400L);
        when(userDiscord.findByDiscordUserId(1400L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(14, 10))
            .thenReturn(List.of());
        when(memberships.save(any(UserCommunityStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.observeDiscordMember(
            "100",
            "1400",
            new DiscordMemberObservationRequest(
                "newbie",
                "Newbie",
                "Newbie",
                true,
                LocalDateTime.of(2026, 10, 2, 9, 0)
            )
        );

        ArgumentCaptor<UserCommunityStatus> captured = ArgumentCaptor.forClass(UserCommunityStatus.class);
        verify(memberships).save(captured.capture());
        assertEquals(Boolean.TRUE, captured.getValue().getApprovalRequired());
        assertEquals(Boolean.FALSE, captured.getValue().getApproved());
        assertNull(captured.getValue().getApprovedAt());
    }

    @Test
    void trustedSnapshotDoesNotUndoExplicitPortariaApproval() {
        when(availability.isPortariaEnabled(100L)).thenReturn(true);
        User member = user(15, "Member");
        UserDiscord identity = identity(member, 1500L);
        UserCommunityStatus membership = membership(member, community, true);
        membership.setApprovalRequired(true);
        membership.setApproved(true);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 1, 20, 0);
        membership.setApprovedAt(approvedAt);
        when(userDiscord.findByDiscordUserId(1500L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(15, 10))
            .thenReturn(List.of(membership));

        service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("1500", "member", "Member", "Member", false, null)
            ))
        );

        assertEquals(Boolean.TRUE, membership.getApproved());
        assertEquals(approvedAt, membership.getApprovedAt());
    }

    @Test
    void explicitPortariaApprovalUpdatesOnlyTenantMembership() {
        User member = user(16, "Member");
        UserDiscord identity = identity(member, 1600L);
        UserCommunityStatus membership = membership(member, community, false);
        membership.setApprovalRequired(true);
        membership.setApproved(false);
        membership.setApprovedAt(null);
        when(userDiscord.findByDiscordUserId(1600L)).thenReturn(Optional.of(identity));
        CommunityNetworkMemberStatus networkState =
            networkMembership(member, true, LocalDateTime.of(2026, 10, 1, 10, 0));
        networkState.setApprovalRequired(true);
        networkState.setApproved(false);
        networkState.setApprovedAt(null);
        networkStateByUser.put(member.getId(), networkState);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(16, 10))
            .thenReturn(List.of(membership));
        when(memberships.save(any(UserCommunityStatus.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MemberObservationResponse response = service.observeDiscordMemberApproval(
            "100",
            "1600",
            new DiscordMemberApprovalRequest(true)
        );

        assertTrue(response.identityKnown());
        assertTrue(response.membershipUpdated());
        assertEquals(Boolean.TRUE, membership.getApproved());
        assertNotNull(membership.getApprovedAt());
        verify(memberships).save(membership);
    }

    @Test
    void incrementalMemberObservationUpdatesNetworkAndCommunityNamesWithoutPlatformName() {
        User member = user(12, "Platform Name");
        UserDiscord identity = identity(member, 1200L);
        identity.setUsername("old");
        UserCommunityStatus membership = membership(member, community, false);
        when(userDiscord.findByDiscordUserId(1200L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(12, 10))
            .thenReturn(List.of(membership));

        MemberObservationResponse response = service.observeDiscordMember(
            "100",
            "1200",
            new DiscordMemberObservationRequest(
                "fox",
                "Fox Global",
                "Fox Local",
                true,
                LocalDateTime.of(2026, 10, 2, 8, 0)
            )
        );

        assertTrue(response.membershipUpdated());
        assertEquals("fox", identity.getUsername());
        assertEquals("Fox Global", identity.getDisplayName());
        assertEquals("Fox Local", membership.getDisplayName());
        assertEquals("Platform Name", member.getDisplayName());
        assertTrue(membership.getIsPresent());
    }

    @Test
    void incrementalMemberRemovalMarksOnlyThatCommunityMembershipAbsent() {
        User member = user(13, "Member");
        UserDiscord identity = identity(member, 1300L);
        UserCommunityStatus membership = membership(member, community, false);
        CommunityNetworkMemberStatus networkState =
            networkMembership(member, true, LocalDateTime.of(2026, 9, 1, 10, 0));
        networkStateByUser.put(member.getId(), networkState);
        when(userDiscord.findByDiscordUserId(1300L)).thenReturn(Optional.of(identity));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(13, 10))
            .thenReturn(List.of(membership));
        when(memberships.save(any(UserCommunityStatus.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MemberObservationResponse response = service.removeDiscordMember("100", "1300");

        assertTrue(response.identityKnown());
        assertTrue(response.membershipUpdated());
        assertEquals(Boolean.FALSE, membership.getIsPresent());
        assertNotNull(membership.getLeftAt());
        verify(memberships).save(membership);
    }

    @Test
    void incrementalMemberObservationCreatesMembershipWithoutReconcilingOthers() {
        when(availability.isPortariaEnabled(100L)).thenReturn(false);
        User member = user(12, null);
        UserDiscord identity = identity(member, 1200L);
        when(userDiscord.findByDiscordUserId(1200L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(12, 10))
            .thenReturn(List.of());
        when(memberships.save(any(UserCommunityStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MemberObservationResponse response = service.observeDiscordMember(
            "100",
            "1200",
            new DiscordMemberObservationRequest(
                "member",
                "Global",
                "Guild Nick",
                true,
                LocalDateTime.of(2026, 10, 2, 10, 0)
            )
        );

        assertTrue(response.membershipUpdated());
        verify(memberships).save(any(UserCommunityStatus.class));
        verify(memberships, never()).findAllByCommunityIdWithUser(anyInt());
    }

    @Test
    void incrementalMemberRemovalDoesNotCreateUnknownIdentity() {
        when(userDiscord.findByDiscordUserId(1300L)).thenReturn(Optional.empty());

        MemberObservationResponse response = service.removeDiscordMember("100", "1300");

        assertFalse(response.identityKnown());
        assertFalse(response.membershipUpdated());
        verify(memberships, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test
    void trustedBatchUsesObservedApprovalForPreviouslyUnknownLegacyMember() {
        when(availability.isPortariaEnabled(100L)).thenReturn(true);
        User member = user(20, null);
        UserDiscord identity = identity(member, 2000L);
        when(userDiscord.findByDiscordUserId(2000L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(20, 10))
            .thenReturn(List.of());
        when(memberships.save(any(UserCommunityStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyDiscordMemberBatch(
            "100",
            new DiscordMemberBatchRequest(null, List.of(
                new DiscordMemberSnapshot("2000", "legacy", "Legacy", "Legacy Guild", true, null)
            ))
        );

        ArgumentCaptor<UserCommunityStatus> captured = ArgumentCaptor.forClass(UserCommunityStatus.class);
        verify(memberships).save(captured.capture());
        assertNull(captured.getValue().getApprovalRequired());
        assertEquals(Boolean.TRUE, captured.getValue().getApproved());
        assertNull(captured.getValue().getApprovedAt());
    }

    @Test
    void memberBatchUpsertsObservedMembersWithoutMarkingOthersAbsent() {
        User observed = user(21, "Observed");
        UserDiscord observedIdentity = identity(observed, 2100L);
        UserCommunityStatus observedMembership = membership(observed, community, true);

        User missing = user(22, "Missing");
        UserCommunityStatus missingMembership = membership(missing, community, true);

        when(userDiscord.findByDiscordUserId(2100L)).thenReturn(Optional.of(observedIdentity));
        when(userDiscord.save(observedIdentity)).thenReturn(observedIdentity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(21, 10))
            .thenReturn(List.of(observedMembership));

        MemberSnapshotResponse response = service.applyDiscordMemberBatch(
            "100",
            new DiscordMemberBatchRequest(null, List.of(
                new DiscordMemberSnapshot("2100", "observed", "Observed", "Observed Guild", true, null)
            ))
        );

        assertEquals(1, response.observedMembers());
        assertEquals(Boolean.TRUE, observedMembership.getIsPresent());
        assertEquals(Boolean.TRUE, missingMembership.getIsPresent());
        verify(memberships, never()).findAllByCommunityIdWithUser(anyInt());
    }

    @Test
    void finalizeSnapshotMarksOnlyNetworkMembersMissingFromRunAbsent() {
        User observed = user(23, "Observed");
        UserCommunityStatus observedMembership = membership(observed, community, true);
        User missing = user(24, "Missing");
        UserCommunityStatus missingMembership = membership(missing, community, true);

        CommunityNetworkSyncRun run = runningRun(903L, SyncTrigger.MANUAL);
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            903L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(run));

        CommunityNetworkMemberStatus observedState =
            networkMembership(observed, true, LocalDateTime.of(2024, 1, 1, 10, 0));
        observedState.setLastSyncRun(run);
        networkStateByUser.put(observed.getId(), observedState);

        CommunityNetworkMemberStatus missingState =
            networkMembership(missing, true, LocalDateTime.of(2024, 1, 2, 10, 0));
        networkStateByUser.put(missing.getId(), missingState);

        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(24, 10))
            .thenReturn(List.of(missingMembership));
        when(memberships.save(any(UserCommunityStatus.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MemberSnapshotResponse response = service.finalizeDiscordMemberSnapshot(
            "100",
            new DiscordMemberFinalizeRequest(903L, true, 1, null)
        );

        assertEquals(1, response.observedMembers());
        assertEquals(1, response.updatedMembers());
        assertEquals(Boolean.TRUE, observedState.getIsPresent());
        assertEquals(Boolean.FALSE, missingState.getIsPresent());
        assertNotNull(missingState.getLeftAt());
        assertEquals(Boolean.FALSE, missingMembership.getIsPresent());
        assertEquals(1, link.getUsersQuantity());
        assertEquals(MembershipSyncState.HEALTHY.name(), link.getMembershipSyncState());
        assertNotNull(link.getLastFullReconciliationAt());
        assertEquals(SyncStatus.SUCCESS.name(), run.getStatus());
        assertNotNull(run.getCompletedAt());
        assertEquals(1, run.getObservedMembers());
        verify(runs).save(run);
        verify(networkEvents).save(argThat(event ->
            "LEAVE".equals(event.getEventType())
                && event.getUser().getId().equals(24)
                && event.getSyncRun() == run
        ));
    }

    @Test
    void finalizeSnapshotCompletesRunAtomicallyAndLaterSuccessAckIsIdempotent() {
        CommunityNetworkSyncRun run = runningRun(911L, SyncTrigger.RECOVERY);
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            911L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(run));

        MemberSnapshotResponse finalized = service.finalizeDiscordMemberSnapshot(
            "100",
            new DiscordMemberFinalizeRequest(911L, true, 0, null)
        );

        assertEquals(0, finalized.observedMembers());
        assertEquals(SyncStatus.SUCCESS.name(), run.getStatus());
        assertNotNull(run.getCompletedAt());

        SyncRunResponse acknowledged = service.updateRuntimeRun(
            "100",
            911L,
            new SyncRunUpdateRequest(
                SyncStatus.SUCCESS,
                null,
                run.getCompletedAt(),
                0,
                0,
                null
            )
        );

        assertEquals(SyncStatus.SUCCESS, acknowledged.status());
        assertEquals(0, run.getObservedMembers());
        assertEquals(0, run.getUpdatedMembers());
    }

    @Test
    void finalizeSnapshotEstablishesLegacyDiscordAbsenceWithoutInventingLeaveHistory() {
        User legacyUser = user(28, "Legacy absent");
        UserCommunityStatus legacyMembership = membership(legacyUser, community, true);
        legacyMembership.setMemberSince(LocalDateTime.of(2021, 5, 1, 12, 0));
        legacyMembership.setLastJoinDate(LocalDateTime.of(2024, 8, 1, 9, 0));
        legacyMembership.setIsPresent(true);
        legacyMembership.setLeftAt(null);
        UserDiscord identity = identity(legacyUser, 2800L);

        CommunityNetworkSyncRun run = runningRun(907L, SyncTrigger.STARTUP);
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            907L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(run));
        when(memberships.findAllByCommunityIdWithUser(10))
            .thenReturn(List.of(legacyMembership));
        when(userDiscord.findByUserIdIn(anyCollection()))
            .thenReturn(List.of(identity));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(28, 10))
            .thenReturn(List.of(legacyMembership));
        when(memberships.save(any(UserCommunityStatus.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MemberSnapshotResponse response = service.finalizeDiscordMemberSnapshot(
            "100",
            new DiscordMemberFinalizeRequest(907L, true, 0, null)
        );

        CommunityNetworkMemberStatus established = networkStateByUser.get(28);
        assertNotNull(established);
        assertEquals(Boolean.FALSE, established.getIsPresent());
        assertNull(established.getFirstKnownJoinAt());
        assertNull(established.getLastJoinAt());
        assertNull(established.getLeftAt());
        assertSame(run, established.getLastSyncRun());
        assertEquals(Boolean.FALSE, legacyMembership.getIsPresent());
        assertNull(legacyMembership.getLeftAt());
        assertEquals(LocalDateTime.of(2021, 5, 1, 12, 0), legacyMembership.getMemberSince());
        assertEquals(LocalDateTime.of(2024, 8, 1, 9, 0), legacyMembership.getLastJoinDate());
        assertEquals(1, response.updatedMembers());
        assertEquals(0, link.getUsersQuantity());
        verify(networkEvents, never()).save(any());
    }

    @Test
    void finalizeSnapshotPreservesMemberObservedAfterRunStarted() {
        User concurrentJoin = user(27, "Concurrent");
        UserCommunityStatus aggregate = membership(concurrentJoin, community, true);
        aggregate.setIsPresent(true);

        CommunityNetworkSyncRun run = runningRun(906L, SyncTrigger.RECOVERY);
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            906L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(run));

        CommunityNetworkMemberStatus concurrentState =
            networkMembership(
                concurrentJoin,
                true,
                run.getStartedAt().plusSeconds(5)
            );
        networkStateByUser.put(concurrentJoin.getId(), concurrentState);

        MemberSnapshotResponse response = service.finalizeDiscordMemberSnapshot(
            "100",
            new DiscordMemberFinalizeRequest(906L, true, 0, null)
        );

        assertEquals(0, response.observedMembers());
        assertEquals(0, response.updatedMembers());
        assertEquals(Boolean.TRUE, concurrentState.getIsPresent());
        assertEquals(1, link.getUsersQuantity());
        verify(networkEvents, never()).save(any());
    }

    @Test
    void finalizeSnapshotFailsClosedWhenBatchCoverageDoesNotMatchObservedCount() {
        User observed = user(25, "Observed");
        CommunityNetworkSyncRun run = runningRun(904L, SyncTrigger.MANUAL);
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            904L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(run));

        CommunityNetworkMemberStatus observedState =
            networkMembership(observed, true, LocalDateTime.of(2024, 1, 1, 10, 0));
        observedState.setLastSyncRun(run);
        networkStateByUser.put(observed.getId(), observedState);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.finalizeDiscordMemberSnapshot(
                "100",
                new DiscordMemberFinalizeRequest(904L, true, 2, null)
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(Boolean.TRUE, observedState.getIsPresent());
        verify(networkEvents, never()).save(any());
    }

    @Test
    void incompleteSnapshotIsRejectedBeforeMembershipMutation() {
        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.applyDiscordMemberSnapshot(
                "100",
                new DiscordMemberSnapshotRequest(null, false, List.of())
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(memberships, never()).save(any());
    }

    @Test
    void acceptsDiscordGlobalDisplayNameAt32UnicodeCodePoints() {
        User member = user(29, "Emoji");
        UserDiscord identity = identity(member, 2900L);
        CommunityNetworkMemberStatus state =
            networkMembership(member, true, LocalDateTime.of(2024, 1, 1, 10, 0));
        networkStateByUser.put(member.getId(), state);
        String discordName = "😀".repeat(32);

        when(userDiscord.findByDiscordUserId(2900L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);

        service.applyDiscordMemberBatch(
            "100",
            new DiscordMemberBatchRequest(null, List.of(
                new DiscordMemberSnapshot(
                    "2900",
                    "emoji-user",
                    discordName,
                    "Guild Emoji",
                    true,
                    null
                )
            ))
        );

        assertEquals(discordName, identity.getDisplayName());
    }

    @Test
    void rejectsDiscordGlobalDisplayNameBeyond32UnicodeCodePoints() {
        String discordName = "😀".repeat(33);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.applyDiscordMemberBatch(
                "100",
                new DiscordMemberBatchRequest(null, List.of(
                    new DiscordMemberSnapshot(
                        "3000",
                        "emoji-user",
                        discordName,
                        "Guild Emoji",
                        true,
                        null
                    )
                ))
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(userDiscord, never()).save(any(UserDiscord.class));
    }

    @Test
    void trustedBatchReconcilesLegacyApprovalFromCurrentVisitorState() {
        when(availability.isPortariaEnabled(100L)).thenReturn(true);
        User member = user(26, "Legacy");
        UserDiscord identity = identity(member, 2600L);
        UserCommunityStatus membership = membership(member, community, true);
        membership.setApprovalRequired(null);
        membership.setApproved(false);
        membership.setApprovedAt(null);
        when(userDiscord.findByDiscordUserId(2600L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(26, 10))
            .thenReturn(List.of(membership));

        service.applyDiscordMemberBatch(
            "100",
            new DiscordMemberBatchRequest(null, List.of(
                new DiscordMemberSnapshot("2600", "legacy", "Legacy", "Legacy Guild", true, null)
            ))
        );

        assertNull(membership.getApprovalRequired());
        assertEquals(Boolean.TRUE, membership.getApproved());
        assertNull(membership.getApprovedAt());
    }

    @Test
    void trustedSnapshotResolvesLegacyUnknownApprovalApplicabilityOnce() {
        when(availability.isPortariaEnabled(100L)).thenReturn(false);
        User member = user(11, "Legacy");
        UserDiscord identity = identity(member, 1100L);
        UserCommunityStatus membership = membership(member, community, true);
        membership.setApprovalRequired(null);
        when(userDiscord.findByDiscordUserId(1100L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(11, 10))
            .thenReturn(List.of(membership));

        service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("1100", "legacy", "Legacy Global", "Legacy Guild", true, null)
            ))
        );

        assertEquals(Boolean.FALSE, membership.getApprovalRequired());
    }

    @Test
    void snapshotResolvesOwnershipAfterCreatingPreviouslyUnknownOwnerIdentity() {
        link.setDiscordAdminId(800L);
        User created = user(8, null);
        UserDiscord createdIdentity = identity(created, 800L);
        when(userDiscord.findByDiscordUserId(800L))
            .thenReturn(Optional.empty(), Optional.of(createdIdentity));
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User value = invocation.getArgument(0);
            value.setId(8);
            return value;
        });
        when(userDiscord.save(any(UserDiscord.class))).thenAnswer(invocation -> {
            UserDiscord value = invocation.getArgument(0);
            value.setId(80);
            return value;
        });
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(8, 10))
            .thenReturn(List.of());
        when(memberships.save(any(UserCommunityStatus.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("800", "owner", "Owner Global", "Owner Guild", false,
                    LocalDateTime.of(2026, 10, 2, 12, 0))
            ))
        );

        assertSame(created, community.getOwnerUser());
        assertEquals(800L, link.getDiscordAdminId());
        verify(communities).save(community);
    }

    @Test
    void ambiguousLegacyMembershipDoesNotAbortWholeSnapshot() {
        User member = user(9, "Legacy");
        UserDiscord identity = identity(member, 900L);
        UserCommunityStatus first = membership(member, community, true);
        UserCommunityStatus second = membership(member, community, true);
        second.setId(99);
        when(userDiscord.findByDiscordUserId(900L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(9, 10))
            .thenReturn(List.of(first, second));

        MemberSnapshotResponse response = service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("900", "legacy", "Legacy Global", "Legacy Guild", true, null)
            ))
        );

        assertEquals(1, response.observedMembers());
        assertEquals(1, response.updatedMembers());
        verify(memberships, never()).save(first);
        verify(memberships, never()).save(second);
    }

    @Test
    void rejoinPreservesDiscordJoinedAtInsteadOfApiProcessingTime() {
        User member = user(7, "Platform Fox");
        UserDiscord identity = identity(member, 700L);
        UserCommunityStatus membership = membership(member, community, true);
        membership.setIsPresent(false);
        LocalDateTime joinedAt = LocalDateTime.of(2026, 9, 30, 9, 15);
        when(userDiscord.findByDiscordUserId(700L)).thenReturn(Optional.of(identity));
        when(userDiscord.save(identity)).thenReturn(identity);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership));

        service.applyDiscordMemberSnapshot(
            "100",
            new DiscordMemberSnapshotRequest(null, true, List.of(
                new DiscordMemberSnapshot("700", "fox", "Fox Global", "Fox Guild", true, joinedAt)
            ))
        );

        assertEquals(joinedAt, membership.getLastJoinDate());
    }

    @Test
    void completeGuildPresenceSnapshotDeactivatesOnlyMissingGuilds() {
        CommunityDiscord other = new CommunityDiscord();
        Community otherCommunity = new Community();
        otherCommunity.setId(11);
        other.setCommunity(otherCommunity);
        other.setGuildId(200L);
        other.setActive(true);
        when(discord.findAllByActiveTrueOrderByGuildIdAsc()).thenReturn(List.of(link, other));
        when(discord.findByGuildIdForUpdate(200L)).thenReturn(Optional.of(other));

        DiscordNetworkPresenceSnapshotResponse response =
            service.applyDiscordNetworkPresenceSnapshot(
                new DiscordNetworkPresenceSnapshotRequest(true, List.of("100"))
            );

        assertEquals(1, response.observedGuilds());
        assertEquals(1, response.deactivatedGuilds());
        assertTrue(link.getActive());
        assertFalse(other.getActive());
        verify(discord).save(other);
        verify(discord, never()).save(link);
        verify(backupControlPlaneStore).observeGuildBackupPresence(100L, true);
        verify(backupControlPlaneStore).observeGuildBackupPresence(200L, false);
    }

    @Test
    void incompleteGuildPresenceSnapshotCannotDeactivateAnything() {
        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.applyDiscordNetworkPresenceSnapshot(
                new DiscordNetworkPresenceSnapshotRequest(false, List.of("100"))
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(discord, never()).findAllByActiveTrueOrderByGuildIdAsc();
        verifyNoInteractions(backupControlPlaneStore);
    }

    @Test
    void manualQueueReusesPendingRunAndClaimMovesItToRunning() {
        CommunityNetworkSyncRun pending = new CommunityNetworkSyncRun();
        pending.setId(700L);
        pending.setCommunity(community);
        pending.setNetworkType("DISCORD");
        pending.setExternalNetworkId(100L);
        pending.setTrigger(SyncTrigger.MANUAL.name());
        pending.setStatus(SyncStatus.QUEUED.name());
        when(runs.findFirstByNetworkTypeAndStatusOrderByIdAsc("DISCORD", "QUEUED"))
            .thenReturn(Optional.of(pending));

        var claimed = service.claimNextDiscordRun();

        assertTrue(claimed.isPresent());
        assertEquals(SyncStatus.RUNNING, claimed.get().status());
        assertNotNull(pending.getStartedAt());
        assertEquals("RUNNING", pending.getStatus());
    }

    @Test
    void snapshotRunMustBeRunningBeforeAnyMembershipMutation() {
        CommunityNetworkSyncRun queued = new CommunityNetworkSyncRun();
        queued.setId(901L);
        queued.setCommunity(community);
        queued.setNetworkType("DISCORD");
        queued.setExternalNetworkId(100L);
        queued.setTrigger(SyncTrigger.MANUAL.name());
        queued.setStatus(SyncStatus.QUEUED.name());
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(901L, 10, "DISCORD", 100L))
            .thenReturn(Optional.of(queued));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.applyDiscordMemberSnapshot(
                "100",
                new DiscordMemberSnapshotRequest(901L, true, List.of())
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(memberships, never()).save(any());
    }

    @Test
    void directlyCreatedSuccessfulReconciliationClosesOlderRunningOrphans() {
        CommunityNetworkSyncRun olderOrphan = runningRun(498L, SyncTrigger.RECOVERY);
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(olderOrphan));

        SyncRunResponse response = service.createRuntimeRun(
            "100",
            new SyncRunCreateRequest(
                SyncTrigger.RECOVERY,
                SyncStatus.SUCCESS,
                LocalDateTime.of(2026, 10, 4, 18, 30),
                LocalDateTime.of(2026, 10, 4, 18, 36),
                5503,
                5503,
                null
            )
        );

        assertEquals(SyncStatus.SUCCESS, response.status());
        assertEquals(SyncStatus.FAILED.name(), olderOrphan.getStatus());
        assertEquals("SUPERSEDED_BY_SUCCESS", olderOrphan.getErrorCode());
        assertNotNull(olderOrphan.getCompletedAt());
        verify(runs, atLeastOnce()).save(olderOrphan);
    }

    @Test
    void startupRunSupersedesAllStaleRunningReconciliationsEvenWhenNetworkIsHealthy() {
        CommunityNetworkSyncRun firstStale = runningRun(904L, SyncTrigger.MANUAL);
        CommunityNetworkSyncRun secondStale = runningRun(905L, SyncTrigger.RECOVERY);
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(firstStale, secondStale));

        SyncRunResponse response = service.createRuntimeRun(
            "100",
            new SyncRunCreateRequest(
                SyncTrigger.STARTUP,
                SyncStatus.RUNNING,
                null,
                null,
                null,
                null,
                null
            )
        );

        for (CommunityNetworkSyncRun stale : List.of(firstStale, secondStale)) {
            assertEquals(SyncStatus.FAILED.name(), stale.getStatus());
            assertEquals("SUPERSEDED_BY_STARTUP", stale.getErrorCode());
            assertNotNull(stale.getCompletedAt());
            verify(runs, atLeastOnce()).save(stale);
        }
        assertEquals(SyncTrigger.STARTUP, response.trigger());
        assertEquals(SyncStatus.RUNNING, response.status());
        assertEquals(MembershipSyncState.RECONCILING.name(), link.getMembershipSyncState());
    }

    @Test
    void successfulReconciliationClosesOnlyRunsStartedBeforeIt() {
        CommunityNetworkSyncRun completed = runningRun(910L, SyncTrigger.RECOVERY);
        completed.setStartedAt(LocalDateTime.of(2026, 10, 4, 18, 30));
        CommunityNetworkSyncRun olderOrphan = runningRun(908L, SyncTrigger.STARTUP);
        olderOrphan.setStartedAt(LocalDateTime.of(2026, 10, 4, 18, 15));
        CommunityNetworkSyncRun newerRun = runningRun(909L, SyncTrigger.MANUAL);
        newerRun.setStartedAt(LocalDateTime.of(2026, 10, 4, 18, 35));
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            910L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(completed));
        when(runs.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
            10, "DISCORD", 100L, "RUNNING"
        )).thenReturn(List.of(olderOrphan, newerRun));

        SyncRunResponse response = service.updateRuntimeRun(
            "100",
            910L,
            new SyncRunUpdateRequest(
                SyncStatus.SUCCESS,
                null,
                LocalDateTime.of(2026, 10, 4, 18, 36),
                5503,
                5503,
                null
            )
        );

        assertEquals(SyncStatus.SUCCESS, response.status());
        assertEquals(SyncStatus.FAILED.name(), olderOrphan.getStatus());
        assertEquals("SUPERSEDED_BY_SUCCESS", olderOrphan.getErrorCode());
        assertNotNull(olderOrphan.getCompletedAt());
        assertEquals(SyncStatus.RUNNING.name(), newerRun.getStatus());
        assertNull(newerRun.getCompletedAt());
        verify(runs, atLeastOnce()).save(olderOrphan);
        verify(runs, never()).save(newerRun);
    }

    @Test
    void failedReconciliationRunMarksNetworkDegraded() {
        CommunityNetworkSyncRun running = runningRun(905L, SyncTrigger.MANUAL);
        link.setMembershipSyncState(MembershipSyncState.RECONCILING.name());
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            905L, 10, "DISCORD", 100L
        )).thenReturn(Optional.of(running));

        SyncRunResponse response = service.updateRuntimeRun(
            "100",
            905L,
            new SyncRunUpdateRequest(
                SyncStatus.FAILED,
                null,
                LocalDateTime.of(2026, 10, 2, 10, 5),
                null,
                null,
                "API_409"
            )
        );

        assertEquals(SyncStatus.FAILED, response.status());
        assertEquals(MembershipSyncState.DEGRADED.name(), link.getMembershipSyncState());
        verify(discord).save(link);
    }

    @Test
    void syncRunCannotMoveBackwardsFromRunningToQueued() {
        CommunityNetworkSyncRun running = new CommunityNetworkSyncRun();
        running.setId(902L);
        running.setCommunity(community);
        running.setNetworkType("DISCORD");
        running.setExternalNetworkId(100L);
        running.setTrigger(SyncTrigger.MANUAL.name());
        running.setStatus(SyncStatus.RUNNING.name());
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(902L, 10, "DISCORD", 100L))
            .thenReturn(Optional.of(running));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.updateRuntimeRun(
                "100",
                902L,
                new SyncRunUpdateRequest(SyncStatus.QUEUED, null, null, null, null, null)
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("RUNNING", running.getStatus());
    }

    @Test
    void runFromAnotherTenantCannotBeUpdatedThroughThisGuild() {
        when(runs.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(900L, 10, "DISCORD", 100L))
            .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.updateRuntimeRun(
                "100",
                900L,
                new SyncRunUpdateRequest(SyncStatus.RUNNING, null, null, null, null, null)
            )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private CommunityNetworkSyncRun runningRun(long id, SyncTrigger trigger) {
        CommunityNetworkSyncRun run = new CommunityNetworkSyncRun();
        run.setId(id);
        run.setCommunity(community);
        run.setNetworkType("DISCORD");
        run.setExternalNetworkId(100L);
        run.setTrigger(trigger.name());
        run.setStatus(SyncStatus.RUNNING.name());
        run.setStartedAt(LocalDateTime.of(2026, 10, 2, 10, 0));
        return run;
    }

    private CommunityNetworkMemberStatus networkMembership(
        User user,
        boolean present,
        LocalDateTime joinedAt
    ) {
        CommunityNetworkMemberStatus state = new CommunityNetworkMemberStatus();
        state.setId((long) user.getId());
        state.setCommunity(community);
        state.setNetworkType("DISCORD");
        state.setExternalNetworkId(100L);
        state.setUser(user);
        state.setIsPresent(present);
        state.setFirstKnownJoinAt(joinedAt);
        state.setFirstKnownJoinSource("DISCORD_REPORTED");
        state.setLastJoinAt(joinedAt);
        state.setLastJoinSource("DISCORD_REPORTED");
        state.setCurrentPresenceSince(present ? joinedAt : null);
        state.setLeftAt(present ? null : joinedAt.plusDays(1));
        state.setLastObservedAt(joinedAt);
        state.setApprovalRequired(null);
        state.setApproved(true);
        return state;
    }

    private User user(int id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }

    private UserDiscord identity(User user, long discordUserId) {
        UserDiscord identity = new UserDiscord();
        identity.setId((int) discordUserId);
        identity.setUser(user);
        identity.setDiscordUserId(discordUserId);
        identity.setUsername("network-user");
        return identity;
    }

    private UserCommunityStatus membership(User user, Community community, boolean approvalRequired) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setId(user.getId());
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setMemberSince(LocalDateTime.now());
        membership.setApprovalRequired(approvalRequired);
        membership.setApproved(false);
        membership.setBanned(false);
        membership.setIsPresent(true);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBirthdayMentionable(false);
        return membership;
    }

    private Community community(int id) {
        Community value = new Community();
        value.setId(id);
        value.setName("Community " + id);
        return value;
    }
}
