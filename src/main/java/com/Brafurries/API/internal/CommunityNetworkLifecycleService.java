package com.Brafurries.API.internal;

import com.Brafurries.API.user.BackupControlPlaneStore;

import static com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.*;

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
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.CommunityMembershipAggregateService;
import com.Brafurries.API.user.CommunityNetworkAvailabilityService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityNetworkLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(CommunityNetworkLifecycleService.class);
    private static final String DISCORD = "DISCORD";
    private static final int DISCORD_IDENTITY_NAME_MAX_CODE_POINTS = 32;

    private final CommunityRepository communityRepository;
    private final CommunityDiscordRepository discordRepository;
    private final CommunityNetworkSyncRunRepository runRepository;
    private final CommunityNetworkMemberStatusRepository networkMembershipRepository;
    private final CommunityNetworkMemberEventRepository networkMemberEventRepository;
    private final CommunityAuditLogRepository auditRepository;
    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserCommunityStatusRepository membershipRepository;
    private final CommunityNetworkAvailabilityService networkAvailability;
    private final CommunityAuthorizationService authorizationService;
    private final CommunityMembershipAggregateService membershipAggregateService;
    private final BackupControlPlaneStore backupControlPlaneStore;

    public CommunityNetworkLifecycleService(
        CommunityRepository communityRepository,
        CommunityDiscordRepository discordRepository,
        CommunityNetworkSyncRunRepository runRepository,
        CommunityNetworkMemberStatusRepository networkMembershipRepository,
        CommunityNetworkMemberEventRepository networkMemberEventRepository,
        CommunityAuditLogRepository auditRepository,
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserCommunityStatusRepository membershipRepository,
        CommunityNetworkAvailabilityService networkAvailability,
        CommunityAuthorizationService authorizationService,
        CommunityMembershipAggregateService membershipAggregateService,
        BackupControlPlaneStore backupControlPlaneStore
    ) {
        this.communityRepository = communityRepository;
        this.discordRepository = discordRepository;
        this.runRepository = runRepository;
        this.networkMembershipRepository = networkMembershipRepository;
        this.networkMemberEventRepository = networkMemberEventRepository;
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.membershipRepository = membershipRepository;
        this.networkAvailability = networkAvailability;
        this.authorizationService = authorizationService;
        this.membershipAggregateService = membershipAggregateService;
        this.backupControlPlaneStore = backupControlPlaneStore;
    }

    @Transactional
    public DiscordNetworkResponse observeDiscordNetwork(
        String rawGuildId,
        DiscordNetworkObservationRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        long ownerDiscordId = parsePositiveSnowflake(request.ownerDiscordUserId(), "ownerDiscordUserId");
        CommunityDiscord link = discordRepository.findByGuildIdForUpdate(guildId).orElse(null);
        boolean newLink = link == null;
        boolean wasActive = link != null && Boolean.TRUE.equals(link.getActive());

        if (link == null) {
            Community community = new Community();
            community.setName(request.name().trim());
            community = communityRepository.save(community);

            link = new CommunityDiscord();
            link.setCommunity(community);
            link.setGuildId(guildId);
            link.setDiscordAdminId(null);
            link.setMembershipSyncState(MembershipSyncState.RECONCILIATION_REQUIRED.name());
        }

        long trackedPresent = networkMembershipRepository
            .countByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(DISCORD, guildId);
        if (
            Boolean.TRUE.equals(request.active())
                && (
                    newLink
                        || !wasActive
                        || (
                            MembershipSyncState.HEALTHY.name().equals(link.getMembershipSyncState())
                                && trackedPresent != request.observedMembers()
                        )
                )
        ) {
            link.setMembershipSyncState(MembershipSyncState.RECONCILIATION_REQUIRED.name());
        }

        link.setName(request.name().trim());
        link.setActive(request.active());
        link.setUsersQuantity(request.observedMembers());
        link = discordRepository.save(link);

        boolean changed = synchronizeOwnership(link, ownerDiscordId);

        // Backup retention is secondary to Community lifecycle. A Backup schema
        // or storage failure must never roll back the tenant/network observation
        // that makes a newly joined guild usable by the platform.
        try {
            backupControlPlaneStore.observeGuildBackupPresence(
                guildId,
                Boolean.TRUE.equals(request.active())
            );
        } catch (RuntimeException error) {
            log.error(
                "Falha ao observar presença da guild {} no domínio Backup; lifecycle da Community foi preservado",
                guildId,
                error
            );
        }

        return networkResponse(link, changed);
    }

    @Transactional
    public DiscordNetworkResponse observeDiscordOwnership(
        String rawGuildId,
        DiscordOwnershipObservationRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        long ownerDiscordId = parsePositiveSnowflake(request.ownerDiscordUserId(), "ownerDiscordUserId");
        CommunityDiscord link = lockedDiscord(guildId);
        boolean changed = synchronizeOwnership(link, ownerDiscordId);
        return networkResponse(link, changed);
    }

    @Transactional
    public MemberObservationResponse observeDiscordMember(
        String rawGuildId,
        String rawDiscordUserId,
        DiscordMemberObservationRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        long discordUserId = parsePositiveSnowflake(rawDiscordUserId, "discordUserId");
        CommunityDiscord link = lockedDiscord(guildId);
        DiscordMemberSnapshot member = new DiscordMemberSnapshot(
            String.valueOf(discordUserId),
            request.username(),
            request.globalDisplayName(),
            request.displayName(),
            request.approved(),
            request.joinedAt()
        );
        ObservedMemberResult result = upsertObservedMember(
            link,
            member,
            networkAvailability.isPortariaEnabled(guildId),
            LocalDateTime.now(),
            false,
            null,
            "BOT_EVENT"
        );
        if (link.getDiscordAdminId() != null && link.getDiscordAdminId().equals(discordUserId)) {
            synchronizeOwnership(link, discordUserId);
        }
        return new MemberObservationResponse(
            link.getCommunity().getId(),
            String.valueOf(guildId),
            String.valueOf(discordUserId),
            true,
            result.updated()
        );
    }

    @Transactional
    public MemberObservationResponse observeDiscordMemberApproval(
        String rawGuildId,
        String rawDiscordUserId,
        DiscordMemberApprovalRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        long discordUserId = parsePositiveSnowflake(rawDiscordUserId, "discordUserId");
        CommunityDiscord link = lockedDiscord(guildId);
        UserDiscord identity = userDiscordRepository.findByDiscordUserId(discordUserId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Identidade Discord não encontrada"
            ));

        CommunityNetworkMemberStatus networkState = networkMembershipRepository
            .findByNetworkTypeAndExternalNetworkIdAndUserId(
                DISCORD,
                guildId,
                identity.getUser().getId()
            )
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Membership da rede não encontrada"
            ));

        LocalDateTime now = LocalDateTime.now();
        boolean approved = Boolean.TRUE.equals(request.approved());
        boolean changed = !Objects.equals(networkState.getApproved(), approved)
            || !Boolean.TRUE.equals(networkState.getApprovalRequired())
            || (approved && networkState.getApprovedAt() == null)
            || (!approved && networkState.getApprovedAt() != null);

        if (changed) {
            networkState.setApprovalRequired(true);
            networkState.setApproved(approved);
            networkState.setApprovedAt(
                approved
                    ? Objects.requireNonNullElse(networkState.getApprovedAt(), now)
                    : null
            );
            networkState.setLastObservedAt(now);
            networkMembershipRepository.save(networkState);
            recordNetworkMemberEvent(
                link,
                identity.getUser(),
                approved ? "APPROVED" : "APPROVAL_REVOKED",
                now,
                "BOT_EVENT",
                null
            );
            recomputeCommunityMembershipIfUnambiguous(
                link.getCommunity(),
                identity.getUser(),
                now
            );
            link.setLastMembershipEventAt(now);
            discordRepository.save(link);
        }

        return new MemberObservationResponse(
            link.getCommunity().getId(),
            String.valueOf(guildId),
            String.valueOf(discordUserId),
            true,
            changed
        );
    }

    @Transactional
    public MemberObservationResponse removeDiscordMember(
        String rawGuildId,
        String rawDiscordUserId
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        long discordUserId = parsePositiveSnowflake(rawDiscordUserId, "discordUserId");
        CommunityDiscord link = lockedDiscord(guildId);
        var identity = userDiscordRepository.findByDiscordUserId(discordUserId);
        if (identity.isEmpty()) {
            return new MemberObservationResponse(
                link.getCommunity().getId(),
                String.valueOf(guildId),
                String.valueOf(discordUserId),
                false,
                false
            );
        }

        User user = identity.get().getUser();
        CommunityNetworkMemberStatus networkState = networkMembershipRepository
            .findByNetworkTypeAndExternalNetworkIdAndUserId(
                DISCORD,
                guildId,
                user.getId()
            )
            .orElse(null);
        if (networkState == null) {
            return new MemberObservationResponse(
                link.getCommunity().getId(),
                String.valueOf(guildId),
                String.valueOf(discordUserId),
                true,
                false
            );
        }

        boolean changed = Boolean.TRUE.equals(networkState.getIsPresent());
        if (changed) {
            LocalDateTime now = LocalDateTime.now();
            networkState.setIsPresent(false);
            networkState.setCurrentPresenceSince(null);
            networkState.setLeftAt(now);
            networkState.setLastObservedAt(now);
            networkMembershipRepository.save(networkState);
            recordNetworkMemberEvent(
                link,
                user,
                "LEAVE",
                now,
                "BOT_EVENT",
                null
            );
            recomputeCommunityMembershipIfUnambiguous(link.getCommunity(), user, now);
            link.setLastMembershipEventAt(now);
            discordRepository.save(link);
        }

        return new MemberObservationResponse(
            link.getCommunity().getId(),
            String.valueOf(guildId),
            String.valueOf(discordUserId),
            true,
            changed
        );
    }

    @Transactional
    public MemberSnapshotResponse applyDiscordMemberBatch(
        String rawGuildId,
        DiscordMemberBatchRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        CommunityDiscord link = lockedDiscord(guildId);
        CommunityNetworkSyncRun run = request.runId() == null ? null : findRun(link, request.runId());
        if (run != null && !SyncStatus.RUNNING.name().equals(run.getStatus())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Batch só pode ser associado a sync run em execução"
            );
        }

        LinkedHashSet<Long> uniqueDiscordIds = new LinkedHashSet<>();
        for (DiscordMemberSnapshot member : request.members()) {
            long discordUserId = parsePositiveSnowflake(member.discordUserId(), "discordUserId");
            if (!uniqueDiscordIds.add(discordUserId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch contém Discord duplicado");
            }
        }

        boolean approvalRequired = networkAvailability.isPortariaEnabled(guildId);
        LocalDateTime now = LocalDateTime.now();
        int updated = 0;
        for (DiscordMemberSnapshot member : request.members()) {
            if (upsertObservedMember(
                link,
                member,
                approvalRequired,
                now,
                true,
                run,
                "SNAPSHOT_RECONCILIATION"
            ).updated()) {
                updated++;
            }
        }

        if (link.getDiscordAdminId() != null) {
            synchronizeOwnership(link, link.getDiscordAdminId());
        }

        return new MemberSnapshotResponse(
            link.getCommunity().getId(),
            String.valueOf(guildId),
            request.members().size(),
            updated
        );
    }

    @Transactional
    public MemberSnapshotResponse finalizeDiscordMemberSnapshot(
        String rawGuildId,
        DiscordMemberFinalizeRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        if (!Boolean.TRUE.equals(request.complete())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Finalização exige snapshot completo"
            );
        }
        if (request.runId() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Finalização de reconciliação exige sync run"
            );
        }

        CommunityDiscord link = lockedDiscord(guildId);
        CommunityNetworkSyncRun run = findRun(link, request.runId());
        if (!SyncStatus.RUNNING.name().equals(run.getStatus())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Finalização só pode ser associada a sync run em execução"
            );
        }
        if (run.getStartedAt() == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Sync run em execução sem startedAt"
            );
        }

        int expectedObserved;
        if (request.observedMembers() != null) {
            expectedObserved = request.observedMembers();
        } else if (request.observedDiscordUserIds() != null) {
            LinkedHashSet<Long> compatibilityIds = new LinkedHashSet<>();
            for (String rawDiscordUserId : request.observedDiscordUserIds()) {
                long discordUserId = parsePositiveSnowflake(rawDiscordUserId, "discordUserId");
                if (!compatibilityIds.add(discordUserId)) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Finalização contém Discord duplicado"
                    );
                }
            }
            expectedObserved = compatibilityIds.size();
        } else {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Finalização exige observedMembers"
            );
        }

        long stampedMembers = networkMembershipRepository.countObservedInRun(
            DISCORD,
            guildId,
            run.getId()
        );
        if (stampedMembers != expectedObserved) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Finalização rejeitada: cobertura dos batches não corresponde ao total observado"
            );
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = 0;
        List<CommunityNetworkMemberStatus> missing =
            networkMembershipRepository.findPresentNotObservedInRun(
                DISCORD,
                guildId,
                run.getId(),
                run.getStartedAt()
            );
        for (CommunityNetworkMemberStatus networkState : missing) {
            networkState.setIsPresent(false);
            networkState.setCurrentPresenceSince(null);
            networkState.setLeftAt(now);
            networkState.setLastObservedAt(now);
            networkMembershipRepository.save(networkState);
            recordNetworkMemberEvent(
                link,
                networkState.getUser(),
                "LEAVE",
                now,
                "SNAPSHOT_RECONCILIATION",
                run
            );
            recomputeCommunityMembershipIfUnambiguous(
                link.getCommunity(),
                networkState.getUser(),
                now
            );
            updated++;
        }

        updated += establishAbsentLegacyDiscordStates(link, run, now);

        long currentPresentMembers = networkMembershipRepository
            .countByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(DISCORD, guildId);
        link.setUsersQuantity(Math.toIntExact(currentPresentMembers));
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        link.setLastFullReconciliationAt(now);
        discordRepository.save(link);
        applyRunState(
            run,
            SyncStatus.SUCCESS,
            run.getStartedAt(),
            now,
            expectedObserved,
            run.getUpdatedMembers(),
            null
        );
        runRepository.save(run);
        if (link.getDiscordAdminId() != null) {
            synchronizeOwnership(link, link.getDiscordAdminId());
        }

        return new MemberSnapshotResponse(
            link.getCommunity().getId(),
            String.valueOf(guildId),
            expectedObserved,
            updated
        );
    }

    @Transactional
    public MemberSnapshotResponse applyDiscordMemberSnapshot(
        String rawGuildId,
        DiscordMemberSnapshotRequest request
    ) {
        long guildId = parsePositiveSnowflake(rawGuildId, "guildId");
        if (!Boolean.TRUE.equals(request.complete())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Snapshot incompleto não pode reconciliar presença"
            );
        }

        CommunityDiscord link = lockedDiscord(guildId);
        CommunityNetworkSyncRun snapshotRun =
            request.runId() == null ? null : findRun(link, request.runId());
        if (
            snapshotRun != null
                && !SyncStatus.RUNNING.name().equals(snapshotRun.getStatus())
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Snapshot só pode ser associado a sync run em execução"
            );
        }
        if (snapshotRun != null && snapshotRun.getStartedAt() == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Sync run em execução sem startedAt"
            );
        }

        LinkedHashSet<Long> uniqueDiscordIds = new LinkedHashSet<>();
        for (DiscordMemberSnapshot member : request.members()) {
            long discordUserId =
                parsePositiveSnowflake(member.discordUserId(), "discordUserId");
            if (!uniqueDiscordIds.add(discordUserId)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Snapshot contém Discord duplicado"
                );
            }
        }

        boolean portariaEnabled = networkAvailability.isPortariaEnabled(guildId);
        LocalDateTime now = LocalDateTime.now();
        Set<Integer> observedUserIds = new HashSet<>();
        int updated = 0;
        for (DiscordMemberSnapshot member : request.members()) {
            ObservedMemberResult result = upsertObservedMember(
                link,
                member,
                portariaEnabled,
                now,
                true,
                snapshotRun,
                "SNAPSHOT_RECONCILIATION"
            );
            observedUserIds.add(result.userId());
            if (result.updated()) {
                updated++;
            }
        }

        List<CommunityNetworkMemberStatus> missing;
        if (snapshotRun != null) {
            long stampedMembers = networkMembershipRepository.countObservedInRun(
                DISCORD,
                guildId,
                snapshotRun.getId()
            );
            if (stampedMembers != request.members().size()) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Snapshot rejeitado: cobertura observada não corresponde ao payload"
                );
            }
            missing = networkMembershipRepository.findPresentNotObservedInRun(
                DISCORD,
                guildId,
                snapshotRun.getId(),
                snapshotRun.getStartedAt()
            );
        } else {
            missing = networkMembershipRepository
                .findByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(
                    DISCORD,
                    guildId
                )
                .stream()
                .filter(state -> !observedUserIds.contains(state.getUser().getId()))
                .toList();
        }

        for (CommunityNetworkMemberStatus networkState : missing) {
            networkState.setIsPresent(false);
            networkState.setCurrentPresenceSince(null);
            networkState.setLeftAt(now);
            networkState.setLastObservedAt(now);
            networkMembershipRepository.save(networkState);
            recordNetworkMemberEvent(
                link,
                networkState.getUser(),
                "LEAVE",
                now,
                "SNAPSHOT_RECONCILIATION",
                snapshotRun
            );
            recomputeCommunityMembershipIfUnambiguous(
                link.getCommunity(),
                networkState.getUser(),
                now
            );
            updated++;
        }

        long currentPresentMembers = networkMembershipRepository
            .countByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(DISCORD, guildId);
        link.setUsersQuantity(Math.toIntExact(currentPresentMembers));
        link.setMembershipSyncState(MembershipSyncState.HEALTHY.name());
        link.setLastFullReconciliationAt(now);
        discordRepository.save(link);

        if (link.getDiscordAdminId() != null) {
            synchronizeOwnership(link, link.getDiscordAdminId());
        }

        if (snapshotRun != null) {
            applyRunState(
                snapshotRun,
                SyncStatus.SUCCESS,
                snapshotRun.getStartedAt(),
                now,
                request.members().size(),
                updated,
                null
            );
            runRepository.save(snapshotRun);
        }

        return new MemberSnapshotResponse(
            link.getCommunity().getId(),
            String.valueOf(guildId),
            request.members().size(),
            updated
        );
    }

    private ObservedMemberResult upsertObservedMember(
        CommunityDiscord link,
        DiscordMemberSnapshot member,
        boolean portariaEnabled,
        LocalDateTime now,
        boolean trustedReconciliation,
        CommunityNetworkSyncRun syncRun,
        String observationSource
    ) {
        validateDiscordIdentityNames(member);
        long discordUserId = parsePositiveSnowflake(member.discordUserId(), "discordUserId");
        UserDiscord identity = userDiscordRepository.findByDiscordUserId(discordUserId)
            .orElseGet(() -> createDiscordIdentity(discordUserId, member));
        updateDiscordIdentity(identity, member);
        identity = userDiscordRepository.save(identity);

        User user = identity.getUser();
        CommunityNetworkMemberStatus networkState = networkMembershipRepository
            .findByNetworkTypeAndExternalNetworkIdAndUserId(
                DISCORD,
                link.getGuildId(),
                user.getId()
            )
            .orElse(null);

        boolean newNetworkState = networkState == null;
        boolean wasPresent = networkState != null && Boolean.TRUE.equals(networkState.getIsPresent());
        Boolean previousApproved = networkState == null ? null : networkState.getApproved();

        LocalDateTime joinEvidence = member.joinedAt() == null ? now : member.joinedAt();
        String joinSource = member.joinedAt() == null ? observationSource : "DISCORD_REPORTED";

        if (networkState == null) {
            List<UserCommunityStatus> legacyMemberships =
                membershipRepository.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(
                    user.getId(),
                    link.getCommunity().getId()
                );
            UserCommunityStatus legacyMembership =
                legacyMemberships.size() == 1 ? legacyMemberships.getFirst() : null;

            networkState = new CommunityNetworkMemberStatus();
            networkState.setCommunity(link.getCommunity());
            networkState.setNetworkType(DISCORD);
            networkState.setExternalNetworkId(link.getGuildId());
            networkState.setUser(user);
            networkState.setIsPresent(true);
            networkState.setFirstKnownJoinAt(joinEvidence);
            networkState.setFirstKnownJoinSource(joinSource);
            networkState.setLastJoinAt(joinEvidence);
            networkState.setLastJoinSource(joinSource);
            networkState.setCurrentPresenceSince(joinEvidence);
            networkState.setLeftAt(null);
            networkState.setLastObservedAt(now);
            networkState.setDisplayName(normalize(member.displayName()));

            if (
                trustedReconciliation
                    && legacyMembership != null
                    && legacyMembership.getApprovalRequired() != null
            ) {
                networkState.setApprovalRequired(legacyMembership.getApprovalRequired());
                networkState.setApproved(Boolean.TRUE.equals(legacyMembership.getApproved()));
                networkState.setApprovedAt(
                    Boolean.TRUE.equals(legacyMembership.getApproved())
                        ? legacyMembership.getApprovedAt()
                        : null
                );
            } else if (trustedReconciliation) {
                if (!portariaEnabled) {
                    networkState.setApprovalRequired(false);
                    networkState.setApproved(Boolean.TRUE.equals(member.approved()));
                } else if (Boolean.FALSE.equals(member.approved())) {
                    networkState.setApprovalRequired(true);
                    networkState.setApproved(false);
                } else {
                    networkState.setApprovalRequired(null);
                    networkState.setApproved(true);
                }
                networkState.setApprovedAt(null);
            } else {
                networkState.setApprovalRequired(portariaEnabled);
                networkState.setApproved(
                    portariaEnabled ? false : Boolean.TRUE.equals(member.approved())
                );
                networkState.setApprovedAt(null);
            }
        } else {
            if (
                member.joinedAt() != null
                    && (
                        networkState.getFirstKnownJoinAt() == null
                            || member.joinedAt().isBefore(networkState.getFirstKnownJoinAt())
                    )
            ) {
                networkState.setFirstKnownJoinAt(member.joinedAt());
                networkState.setFirstKnownJoinSource("DISCORD_REPORTED");
            }

            boolean newerJoinEvidence = member.joinedAt() != null
                && (
                    networkState.getLastJoinAt() == null
                        || member.joinedAt().isAfter(networkState.getLastJoinAt())
                );
            if (!wasPresent || (trustedReconciliation && newerJoinEvidence)) {
                networkState.setLastJoinAt(joinEvidence);
                networkState.setLastJoinSource(joinSource);
                networkState.setCurrentPresenceSince(joinEvidence);
                networkState.setLeftAt(null);
            }
            networkState.setIsPresent(true);
            networkState.setLastObservedAt(now);
            networkState.setDisplayName(normalize(member.displayName()));

            if (!wasPresent && !trustedReconciliation) {
                networkState.setApprovalRequired(portariaEnabled);
                networkState.setApproved(
                    portariaEnabled ? false : Boolean.TRUE.equals(member.approved())
                );
                networkState.setApprovedAt(null);
            } else if (trustedReconciliation) {
                if (
                    networkState.getApprovalRequired() == null
                        && portariaEnabled
                        && Boolean.FALSE.equals(member.approved())
                ) {
                    networkState.setApprovalRequired(true);
                }
                if (Boolean.TRUE.equals(networkState.getApprovalRequired())) {
                    networkState.setApproved(Boolean.TRUE.equals(member.approved()));
                    if (Boolean.FALSE.equals(member.approved())) {
                        networkState.setApprovedAt(null);
                    }
                } else if (networkState.getApprovalRequired() == null) {
                    networkState.setApproved(Boolean.TRUE.equals(member.approved()));
                }
            }

            if (wasPresent && trustedReconciliation && newerJoinEvidence) {
                recordNetworkMemberEvent(
                    link,
                    user,
                    "JOIN",
                    joinEvidence,
                    "SNAPSHOT_RECONCILIATION",
                    syncRun
                );
            }
        }

        if (syncRun != null) {
            networkState.setLastSyncRun(syncRun);
        }
        networkState = networkMembershipRepository.save(networkState);

        boolean establishedBaseline = link.getLastFullReconciliationAt() != null;
        if (!wasPresent && (!newNetworkState || !trustedReconciliation || establishedBaseline)) {
            recordNetworkMemberEvent(
                link,
                user,
                "JOIN",
                joinEvidence,
                observationSource,
                syncRun
            );
        }

        if (
            trustedReconciliation
                && !newNetworkState
                && !Objects.equals(previousApproved, networkState.getApproved())
                && establishedBaseline
        ) {
            recordNetworkMemberEvent(
                link,
                user,
                Boolean.TRUE.equals(networkState.getApproved())
                    ? "APPROVED"
                    : "APPROVAL_REVOKED",
                now,
                observationSource,
                syncRun
            );
        }

        recomputeCommunityMembershipIfUnambiguous(link.getCommunity(), user, now);
        if ("BOT_EVENT".equals(observationSource)) {
            link.setLastMembershipEventAt(now);
            discordRepository.save(link);
        }
        return new ObservedMemberResult(user.getId(), true);
    }

    private int establishAbsentLegacyDiscordStates(
        CommunityDiscord link,
        CommunityNetworkSyncRun run,
        LocalDateTime observedAt
    ) {
        List<UserCommunityStatus> legacyMemberships =
            membershipRepository.findAllByCommunityIdWithUser(link.getCommunity().getId());
        if (legacyMemberships.isEmpty()) {
            return 0;
        }

        Set<Integer> legacyUserIds = new LinkedHashSet<>();
        for (UserCommunityStatus membership : legacyMemberships) {
            legacyUserIds.add(membership.getUser().getId());
        }

        Map<Integer, User> discordUsers = new LinkedHashMap<>();
        for (UserDiscord identity : userDiscordRepository.findByUserIdIn(legacyUserIds)) {
            discordUsers.putIfAbsent(identity.getUser().getId(), identity.getUser());
        }
        if (discordUsers.isEmpty()) {
            return 0;
        }

        Set<Integer> usersWithNetworkState = new HashSet<>();
        for (CommunityNetworkMemberStatus state :
            networkMembershipRepository.findByNetworkTypeAndExternalNetworkIdAndUserIdIn(
                DISCORD,
                link.getGuildId(),
                discordUsers.keySet()
            )) {
            usersWithNetworkState.add(state.getUser().getId());
        }

        int established = 0;
        for (Map.Entry<Integer, User> entry : discordUsers.entrySet()) {
            if (usersWithNetworkState.contains(entry.getKey())) {
                continue;
            }

            CommunityNetworkMemberStatus absent = new CommunityNetworkMemberStatus();
            absent.setCommunity(link.getCommunity());
            absent.setNetworkType(DISCORD);
            absent.setExternalNetworkId(link.getGuildId());
            absent.setUser(entry.getValue());
            absent.setIsPresent(false);
            absent.setFirstKnownJoinAt(null);
            absent.setFirstKnownJoinSource(null);
            absent.setLastJoinAt(null);
            absent.setLastJoinSource(null);
            absent.setCurrentPresenceSince(null);
            absent.setLeftAt(null);
            absent.setLastObservedAt(observedAt);
            absent.setLastSyncRun(run);
            absent.setApprovalRequired(null);
            absent.setApproved(null);
            absent.setApprovedAt(null);
            absent.setDisplayName(null);
            networkMembershipRepository.save(absent);

            // A complete snapshot proves current absence, but it does not prove
            // when this user joined or left this specific network. Keep history
            // unknown while making current Community presence authoritative.
            recomputeCommunityMembershipIfUnambiguous(
                link.getCommunity(),
                entry.getValue(),
                observedAt
            );
            established++;
        }
        return established;
    }

    private void recomputeCommunityMembershipIfUnambiguous(
        Community community,
        User user,
        LocalDateTime observedAt
    ) {
        List<UserCommunityStatus> legacyMemberships =
            membershipRepository.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(
                user.getId(),
                community.getId()
            );
        if (legacyMemberships.size() > 1) {
            log.warn(
                "Skipping Community membership aggregate for ambiguous legacy membership: communityId={}, userId={}, rows={}",
                community.getId(),
                user.getId(),
                legacyMemberships.size()
            );
            return;
        }
        membershipAggregateService.recompute(community, user, observedAt);
    }

    private void recordNetworkMemberEvent(
        CommunityDiscord link,
        User user,
        String eventType,
        LocalDateTime occurredAt,
        String source,
        CommunityNetworkSyncRun syncRun
    ) {
        CommunityNetworkMemberEvent event = new CommunityNetworkMemberEvent();
        event.setCommunity(link.getCommunity());
        event.setNetworkType(DISCORD);
        event.setExternalNetworkId(link.getGuildId());
        event.setUser(user);
        event.setEventType(eventType);
        event.setOccurredAt(occurredAt);
        event.setSource(source);
        event.setSyncRun(syncRun);
        networkMemberEventRepository.save(event);
    }

    private record ObservedMemberResult(Integer userId, boolean updated) {}

    @Transactional
    public SyncRunResponse createRuntimeRun(String rawGuildId, SyncRunCreateRequest request) {
        CommunityDiscord link = lockedDiscord(parsePositiveSnowflake(rawGuildId, "guildId"));
        if (
            request.trigger() == SyncTrigger.STARTUP
                && request.status() == SyncStatus.RUNNING
        ) {
            supersedeRunningMembershipReconciliations(
                link,
                null,
                "SUPERSEDED_BY_STARTUP"
            );
        }

        CommunityNetworkSyncRun run = new CommunityNetworkSyncRun();
        run.setCommunity(link.getCommunity());
        run.setNetworkType(DISCORD);
        run.setExternalNetworkId(link.getGuildId());
        run.setTrigger(request.trigger().name());
        applyRunState(
            run,
            request.status(),
            request.startedAt(),
            request.completedAt(),
            request.observedMembers(),
            request.updatedMembers(),
            request.errorCode()
        );
        if (isMembershipReconciliationTrigger(request.trigger())
            && request.status() == SyncStatus.RUNNING) {
            link.setMembershipSyncState(MembershipSyncState.RECONCILING.name());
            discordRepository.save(link);
        }
        run = runRepository.save(run);
        if (
            isMembershipReconciliationTrigger(request.trigger())
                && request.status() == SyncStatus.SUCCESS
        ) {
            supersedeRunningMembershipReconciliations(
                link,
                run,
                "SUPERSEDED_BY_SUCCESS"
            );
        }
        return runResponse(run);
    }

    @Transactional
    public SyncRunResponse updateRuntimeRun(
        String rawGuildId,
        Long runId,
        SyncRunUpdateRequest request
    ) {
        CommunityDiscord link = lockedDiscord(parsePositiveSnowflake(rawGuildId, "guildId"));
        CommunityNetworkSyncRun run = findRun(link, runId);
        SyncStatus current = SyncStatus.valueOf(run.getStatus());
        if (!validTransition(current, request.status())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transição de sync run inválida"
            );
        }
        applyRunState(
            run,
            request.status(),
            request.startedAt(),
            request.completedAt(),
            request.observedMembers(),
            request.updatedMembers(),
            request.errorCode()
        );
        if (isMembershipReconciliationTrigger(SyncTrigger.valueOf(run.getTrigger()))) {
            if (request.status() == SyncStatus.FAILED) {
                link.setMembershipSyncState(MembershipSyncState.DEGRADED.name());
                discordRepository.save(link);
            } else if (request.status() == SyncStatus.SUCCESS) {
                supersedeRunningMembershipReconciliations(
                    link,
                    run,
                    "SUPERSEDED_BY_SUCCESS"
                );
            }
        }
        return runResponse(runRepository.save(run));
    }

    @Transactional
    public SyncQueueResponse enqueueCommunitySync(Authentication authentication, Integer communityId) {
        Community community = authorizationService.requireCapability(
            authentication,
            communityId,
            CommunityCapability.COMMUNITY_ADMIN
        ).community();
        CommunityDiscord available = networkAvailability.requireActiveDiscord(community);
        CommunityDiscord link = lockedDiscord(available.getGuildId());
        CommunityNetworkSyncRun run = queuedRun(link, SyncTrigger.MANUAL);
        return new SyncQueueResponse(List.of(run.getId()), 1);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<SyncRunResponse> latestCommunitySync(
        Authentication authentication,
        Integer communityId
    ) {
        Community community = authorizationService.requireCapability(
            authentication,
            communityId,
            CommunityCapability.COMMUNITY_ADMIN
        ).community();
        CommunityDiscord link = networkAvailability.requireActiveDiscord(community);
        return runRepository
            .findFirstByCommunityIdAndNetworkTypeAndExternalNetworkIdOrderByIdDesc(
                community.getId(),
                DISCORD,
                link.getGuildId()
            )
            .map(this::runResponse);
    }

    @Transactional
    public SyncQueueResponse enqueueGlobalSync() {
        List<Long> runIds = discordRepository.findAllByActiveTrueOrderByGuildIdAsc().stream()
            .map(link -> lockedDiscord(link.getGuildId()))
            .map(link -> queuedRun(link, SyncTrigger.MANUAL).getId())
            .toList();
        return new SyncQueueResponse(runIds, runIds.size());
    }

    @Transactional
    public DiscordNetworkPresenceSnapshotResponse applyDiscordNetworkPresenceSnapshot(
        DiscordNetworkPresenceSnapshotRequest request
    ) {
        if (!Boolean.TRUE.equals(request.complete())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Snapshot incompleto não pode reconciliar presença de guilds"
            );
        }

        Set<Long> observedGuildIds = request.guildIds().stream()
            .map(raw -> parsePositiveSnowflake(raw, "guildId"))
            .collect(java.util.stream.Collectors.toSet());

        for (Long observedGuildId : observedGuildIds) {
            backupControlPlaneStore.observeGuildBackupPresence(observedGuildId, true);
        }

        int deactivated = 0;
        for (CommunityDiscord current : discordRepository.findAllByActiveTrueOrderByGuildIdAsc()) {
            if (observedGuildIds.contains(current.getGuildId())) {
                continue;
            }
            CommunityDiscord locked = lockedDiscord(current.getGuildId());
            if (Boolean.TRUE.equals(locked.getActive())
                && !observedGuildIds.contains(locked.getGuildId())) {
                locked.setActive(false);
                discordRepository.save(locked);
                backupControlPlaneStore.observeGuildBackupPresence(
                    locked.getGuildId(),
                    false
                );
                deactivated++;
            }
        }
        return new DiscordNetworkPresenceSnapshotResponse(
            observedGuildIds.size(),
            deactivated
        );
    }

    @Transactional
    public java.util.Optional<SyncRunResponse> claimNextDiscordRun() {
        return runRepository.findFirstByNetworkTypeAndStatusOrderByIdAsc(
                DISCORD,
                SyncStatus.QUEUED.name()
            )
            .map(run -> {
                run.setStatus(SyncStatus.RUNNING.name());
                if (run.getStartedAt() == null) {
                    run.setStartedAt(LocalDateTime.now());
                }
                run.setCompletedAt(null);
                run.setErrorCode(null);
                CommunityDiscord link = lockedDiscord(run.getExternalNetworkId());
                link.setMembershipSyncState(MembershipSyncState.RECONCILING.name());
                discordRepository.save(link);
                return runResponse(runRepository.save(run));
            });
    }

    private boolean synchronizeOwnership(CommunityDiscord link, Long observedOwnerDiscordId) {
        Community community = link.getCommunity();
        User previousOwner = community.getOwnerUser();
        User resolvedOwner = userDiscordRepository.findByDiscordUserId(observedOwnerDiscordId)
            .map(UserDiscord::getUser)
            .orElse(null);

        Integer previousOwnerId = previousOwner == null ? null : previousOwner.getId();
        Integer resolvedOwnerId = resolvedOwner == null ? null : resolvedOwner.getId();
        boolean changed = !Objects.equals(link.getDiscordAdminId(), observedOwnerDiscordId)
            || !Objects.equals(previousOwnerId, resolvedOwnerId);
        if (!changed) {
            return false;
        }

        link.setDiscordAdminId(observedOwnerDiscordId);
        community.setOwnerUser(resolvedOwner);
        discordRepository.save(link);
        communityRepository.save(community);

        recordOwnershipRun(link, resolvedOwner == null ? "OWNER_USER_UNRESOLVED" : null);
        User auditActor = resolvedOwner != null ? resolvedOwner : previousOwner;
        if (auditActor != null) {
            CommunityAuditLog audit = new CommunityAuditLog();
            audit.setCommunity(community);
            audit.setActorUser(auditActor);
            audit.setAction(resolvedOwner == null
                ? "COMMUNITY_OWNER_UNRESOLVED"
                : "COMMUNITY_OWNER_SYNCHRONIZED");
            audit.setTargetType("DISCORD_GUILD");
            audit.setTargetId(String.valueOf(link.getGuildId()));
            audit.setMetadata(String.format(
                Locale.ROOT,
                "{\"source\":\"DISCORD_RUNTIME\",\"previousOwnerUserId\":%s,\"ownerUserId\":%s,\"ownerDiscordUserId\":\"%d\"}",
                jsonNumber(previousOwnerId),
                jsonNumber(resolvedOwnerId),
                observedOwnerDiscordId
            ));
            auditRepository.save(audit);
        }
        return true;
    }

    private void recordOwnershipRun(CommunityDiscord link, String errorCode) {
        CommunityNetworkSyncRun run = new CommunityNetworkSyncRun();
        run.setCommunity(link.getCommunity());
        run.setNetworkType(DISCORD);
        run.setExternalNetworkId(link.getGuildId());
        run.setTrigger(SyncTrigger.OWNERSHIP_CHANGE.name());
        run.setStatus(SyncStatus.SUCCESS.name());
        LocalDateTime now = LocalDateTime.now();
        run.setStartedAt(now);
        run.setCompletedAt(now);
        run.setObservedMembers(link.getUsersQuantity());
        run.setUpdatedMembers(0);
        run.setErrorCode(errorCode);
        runRepository.save(run);
    }

    private CommunityNetworkSyncRun queuedRun(CommunityDiscord link, SyncTrigger trigger) {
        var running = runRepository
            .findFirstByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
                link.getCommunity().getId(),
                DISCORD,
                link.getGuildId(),
                SyncStatus.RUNNING.name()
            );
        if (running.isPresent()) {
            return running.get();
        }
        return runRepository
            .findFirstByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
                link.getCommunity().getId(),
                DISCORD,
                link.getGuildId(),
                SyncStatus.QUEUED.name()
            )
            .orElseGet(() -> {
                CommunityNetworkSyncRun run = new CommunityNetworkSyncRun();
                run.setCommunity(link.getCommunity());
                run.setNetworkType(DISCORD);
                run.setExternalNetworkId(link.getGuildId());
                run.setTrigger(trigger.name());
                run.setStatus(SyncStatus.QUEUED.name());
                return runRepository.save(run);
            });
    }

    private UserDiscord createDiscordIdentity(long discordUserId, DiscordMemberSnapshot member) {
        User user = userRepository.save(new User());
        UserDiscord identity = new UserDiscord();
        identity.setUser(user);
        identity.setDiscordUserId(discordUserId);
        identity.setUsername(member.username().trim());
        identity.setDisplayName(normalize(member.globalDisplayName()));
        return identity;
    }

    private void updateDiscordIdentity(UserDiscord identity, DiscordMemberSnapshot member) {
        identity.setUsername(member.username().trim());
        identity.setDisplayName(normalize(member.globalDisplayName()));
    }

    private UserCommunityStatus newMembership(
        User user,
        Community community,
        DiscordMemberSnapshot member,
        boolean approvalRequired,
        LocalDateTime now
    ) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setMemberSince(member.joinedAt() == null ? now : member.joinedAt());
        membership.setLastJoinDate(member.joinedAt() == null ? now : member.joinedAt());
        membership.setApprovalRequired(approvalRequired);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBanned(false);
        membership.setBirthdayMentionable(false);
        return membership;
    }

    private void applyRunState(
        CommunityNetworkSyncRun run,
        SyncStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Integer observedMembers,
        Integer updatedMembers,
        String errorCode
    ) {
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(status.name());
        run.setStartedAt(startedAt != null
            ? startedAt
            : status == SyncStatus.QUEUED ? run.getStartedAt() : Objects.requireNonNullElse(run.getStartedAt(), now));
        run.setCompletedAt(completedAt != null
            ? completedAt
            : isTerminal(status) ? Objects.requireNonNullElse(run.getCompletedAt(), now) : null);
        run.setObservedMembers(observedMembers);
        run.setUpdatedMembers(updatedMembers);
        run.setErrorCode(normalize(errorCode));
    }

    private boolean validTransition(SyncStatus current, SyncStatus requested) {
        if (current == requested) {
            return true;
        }
        return switch (current) {
            case QUEUED -> requested == SyncStatus.RUNNING || requested == SyncStatus.FAILED;
            case RUNNING -> requested == SyncStatus.SUCCESS || requested == SyncStatus.FAILED;
            case SUCCESS, FAILED -> false;
        };
    }

    private void supersedeRunningMembershipReconciliations(
        CommunityDiscord link,
        CommunityNetworkSyncRun supersedingRun,
        String errorCode
    ) {
        LocalDateTime completedAt = LocalDateTime.now();
        for (CommunityNetworkSyncRun existing :
            runRepository.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
                link.getCommunity().getId(),
                DISCORD,
                link.getGuildId(),
                SyncStatus.RUNNING.name()
            )) {
            if (!isMembershipReconciliationTrigger(SyncTrigger.valueOf(existing.getTrigger()))) {
                continue;
            }
            if (supersedingRun != null) {
                if (Objects.equals(existing.getId(), supersedingRun.getId())) {
                    continue;
                }
                LocalDateTime existingStartedAt = existing.getStartedAt();
                LocalDateTime supersedingStartedAt = supersedingRun.getStartedAt();
                if (
                    existingStartedAt == null
                        || supersedingStartedAt == null
                        || !existingStartedAt.isBefore(supersedingStartedAt)
                ) {
                    continue;
                }
            }
            applyRunState(
                existing,
                SyncStatus.FAILED,
                existing.getStartedAt(),
                completedAt,
                existing.getObservedMembers(),
                existing.getUpdatedMembers(),
                errorCode
            );
            runRepository.save(existing);
        }
    }

    private void selfHealSupersededRunningMembershipReconciliations(
        CommunityDiscord link
    ) {
        LocalDateTime reconciliationWatermark = link.getLastFullReconciliationAt();
        if (reconciliationWatermark == null) {
            return;
        }

        LocalDateTime completedAt = LocalDateTime.now();
        for (CommunityNetworkSyncRun existing :
            runRepository.findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
                link.getCommunity().getId(),
                DISCORD,
                link.getGuildId(),
                SyncStatus.RUNNING.name()
            )) {
            if (!isMembershipReconciliationTrigger(SyncTrigger.valueOf(existing.getTrigger()))) {
                continue;
            }
            LocalDateTime startedAt = existing.getStartedAt();
            if (startedAt == null || !startedAt.isBefore(reconciliationWatermark)) {
                continue;
            }
            applyRunState(
                existing,
                SyncStatus.FAILED,
                startedAt,
                completedAt,
                existing.getObservedMembers(),
                existing.getUpdatedMembers(),
                "SUPERSEDED_BY_COMPLETED_SNAPSHOT"
            );
            runRepository.save(existing);
        }
    }

    private boolean isMembershipReconciliationTrigger(SyncTrigger trigger) {
        return trigger == SyncTrigger.GUILD_JOIN
            || trigger == SyncTrigger.STARTUP
            || trigger == SyncTrigger.PERIODIC
            || trigger == SyncTrigger.MANUAL
            || trigger == SyncTrigger.DRIFT
            || trigger == SyncTrigger.RECOVERY;
    }

    private boolean isTerminal(SyncStatus status) {
        return status == SyncStatus.SUCCESS || status == SyncStatus.FAILED;
    }

    private CommunityDiscord lockedDiscord(long guildId) {
        return discordRepository.findByGuildIdForUpdate(guildId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integração Discord não encontrada"));
    }

    private CommunityNetworkSyncRun findRun(CommunityDiscord link, Long runId) {
        return runRepository.findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
            runId,
            link.getCommunity().getId(),
            DISCORD,
            link.getGuildId()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync run não encontrado"));
    }

    private DiscordNetworkResponse networkResponse(CommunityDiscord link, boolean ownershipChanged) {
        selfHealSupersededRunningMembershipReconciliations(link);
        long trackedPresent = networkMembershipRepository
            .countByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(
                DISCORD,
                link.getGuildId()
            );
        boolean membershipReconciliationRunning = runRepository
            .findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
                link.getCommunity().getId(),
                DISCORD,
                link.getGuildId(),
                SyncStatus.RUNNING.name()
            )
            .stream()
            .anyMatch(existing ->
                isMembershipReconciliationTrigger(SyncTrigger.valueOf(existing.getTrigger()))
            );
        return new DiscordNetworkResponse(
            link.getCommunity().getId(),
            String.valueOf(link.getGuildId()),
            Boolean.TRUE.equals(link.getActive()),
            link.getCommunity().getOwnerUser() == null
                ? null
                : link.getCommunity().getOwnerUser().getId(),
            String.valueOf(link.getDiscordAdminId()),
            ownershipChanged,
            MembershipSyncState.valueOf(link.getMembershipSyncState()),
            membershipReconciliationRunning,
            trackedPresent,
            link.getLastFullReconciliationAt()
        );
    }

    private SyncRunResponse runResponse(CommunityNetworkSyncRun run) {
        return new SyncRunResponse(
            run.getId(),
            run.getCommunity().getId(),
            run.getNetworkType(),
            String.valueOf(run.getExternalNetworkId()),
            SyncTrigger.valueOf(run.getTrigger()),
            SyncStatus.valueOf(run.getStatus()),
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getObservedMembers(),
            run.getUpdatedMembers(),
            run.getErrorCode()
        );
    }

    private long parsePositiveSnowflake(String raw, String field) {
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " inválido");
        }
    }

    private void validateDiscordIdentityNames(DiscordMemberSnapshot member) {
        validateMaxCodePoints(
            member.username(),
            DISCORD_IDENTITY_NAME_MAX_CODE_POINTS,
            "username"
        );
        validateMaxCodePoints(
            member.globalDisplayName(),
            DISCORD_IDENTITY_NAME_MAX_CODE_POINTS,
            "globalDisplayName"
        );
    }

    private void validateMaxCodePoints(String value, int maxCodePoints, String field) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                field + " excede o limite de " + maxCodePoints + " caracteres Unicode"
            );
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String jsonNumber(Integer value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
