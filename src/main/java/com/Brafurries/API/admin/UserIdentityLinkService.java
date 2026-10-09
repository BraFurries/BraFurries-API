package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.UserIdentityDtos.DiscordAccount;
import com.Brafurries.API.admin.dto.UserIdentityDtos.IdentityLinkView;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserIdentityResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserSummary;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserIdentityLink;
import com.Brafurries.API.entity.user.UserIdentityLinkSource;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserIdentityLinkService {

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserIdentityLinkRepository linkRepository;
    private final ConfirmedIdentityClusterService clusterService;
    private final IdentityBanPropagationService banPropagationService;

    public UserIdentityLinkService(
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserIdentityLinkRepository linkRepository,
        ConfirmedIdentityClusterService clusterService,
        IdentityBanPropagationService banPropagationService
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.linkRepository = linkRepository;
        this.clusterService = clusterService;
        this.banPropagationService = banPropagationService;
    }

    @Transactional(readOnly = true)
    public UserIdentityResponse getIdentity(Integer requestedUserId) {
        return getIdentity(requestedUserId, clusterService.resolveConfirmedUserIds(requestedUserId));
    }

    UserIdentityResponse getIdentity(Integer requestedUserId, Collection<Integer> resolvedConfirmedUserIds) {
        requireUser(requestedUserId);
        var confirmedUserIds = new LinkedHashSet<>(resolvedConfirmedUserIds);
        List<UserIdentityLink> confirmedLinks = linkRepository.findActiveLinksContainingUsersByStatus(
            confirmedUserIds,
            UserIdentityLinkStatus.CONFIRMED
        );
        List<UserIdentityLink> suspectedLinks = linkRepository.findActiveLinksContainingUsersByStatus(
            confirmedUserIds,
            UserIdentityLinkStatus.SUSPECTED
        );

        var referencedUserIds = new LinkedHashSet<>(confirmedUserIds);
        confirmedLinks.forEach(link -> {
            referencedUserIds.add(link.getUserA().getId());
            referencedUserIds.add(link.getUserB().getId());
        });
        suspectedLinks.forEach(link -> {
            referencedUserIds.add(link.getUserA().getId());
            referencedUserIds.add(link.getUserB().getId());
        });

        Map<Integer, User> usersById = userRepository.findAllById(referencedUserIds).stream()
            .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        Map<Integer, List<DiscordAccount>> discord = discordAccounts(referencedUserIds);

        List<UserSummary> confirmedAccounts = confirmedUserIds.stream()
            .map(usersById::get)
            .filter(java.util.Objects::nonNull)
            .map(user -> toSummary(user, discord))
            .sorted(Comparator.comparing(UserSummary::userId))
            .toList();
        List<IdentityLinkView> confirmedViews = confirmedLinks.stream()
            .map(link -> toView(link, usersById, discord))
            .toList();
        List<IdentityLinkView> suspectedViews = suspectedLinks.stream()
            .map(link -> toView(link, usersById, discord))
            .toList();

        return new UserIdentityResponse(requestedUserId, confirmedAccounts, confirmedViews, suspectedViews);
    }

    @Transactional
    public IdentityLinkView create(
        Integer userId,
        Integer otherUserId,
        UserIdentityLinkStatus status,
        String reason,
        String actorEmail
    ) {
        validateDistinctUsers(userId, otherUserId);
        Map<Integer, User> lockedUsers = lockUsers(userId, otherUserId);
        User actor = requireActor(actorEmail);

        if (linkRepository.findActiveByPair(userId, otherUserId).isPresent()) {
            throw conflict("Já existe um vínculo ativo entre os usuários informados");
        }

        UserIdentityLink link = new UserIdentityLink();
        link.setUserA(lockedUsers.get(userId));
        link.setUserB(lockedUsers.get(otherUserId));
        link.setStatus(status);
        link.setLinkSource(UserIdentityLinkSource.ADMIN);
        link.setReason(normalizeReason(reason));
        link.setCreatedByUser(actor);

        try {
            link = linkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Já existe um vínculo ativo entre os usuários informados");
        }
        if (status == UserIdentityLinkStatus.CONFIRMED) {
            scheduleBanPropagationAfterCommit(userId);
        }
        return toView(link, lockedUsers, discordAccounts(lockedUsers.keySet()));
    }

    @Transactional
    public IdentityLinkView update(
        Integer userId,
        Integer otherUserId,
        UserIdentityLinkStatus status,
        String reason
    ) {
        validateDistinctUsers(userId, otherUserId);
        Map<Integer, User> lockedUsers = lockUsers(userId, otherUserId);
        UserIdentityLink link = requireActivePair(userId, otherUserId);
        UserIdentityLinkStatus previousStatus = link.getStatus();
        link.setStatus(status);
        link.setReason(normalizeReason(reason));
        IdentityLinkView view = toView(
            linkRepository.save(link),
            lockedUsers,
            discordAccounts(lockedUsers.keySet())
        );
        if (
            status == UserIdentityLinkStatus.CONFIRMED
            && previousStatus != UserIdentityLinkStatus.CONFIRMED
        ) {
            scheduleBanPropagationAfterCommit(userId);
        }
        return view;
    }

    @Transactional
    public IdentityLinkView revoke(
        Integer userId,
        Integer otherUserId,
        String reason,
        String actorEmail
    ) {
        validateDistinctUsers(userId, otherUserId);
        Map<Integer, User> lockedUsers = lockUsers(userId, otherUserId);
        User actor = requireActor(actorEmail);
        UserIdentityLink link = requireActivePair(userId, otherUserId);
        link.setRevokedAt(LocalDateTime.now());
        link.setRevokedByUser(actor);
        link.setRevocationReason(normalizeReason(reason));
        return toView(linkRepository.save(link), lockedUsers, discordAccounts(lockedUsers.keySet()));
    }

    private void scheduleBanPropagationAfterCommit(Integer seedUserId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        banPropagationService.propagateForConfirmedCluster(seedUserId);
                    }
                }
            );
            return;
        }
        banPropagationService.propagateForConfirmedCluster(seedUserId);
    }

    private UserIdentityLink requireActivePair(Integer userId, Integer otherUserId) {
        return linkRepository.findActiveByPair(userId, otherUserId)
            .orElseThrow(() -> notFound("Vínculo ativo não encontrado"));
    }

    private void validateDistinctUsers(Integer userId, Integer otherUserId) {
        if (userId.equals(otherUserId)) {
            throw badRequest("As pontas do vínculo devem ser usuários diferentes");
        }
    }

    private Map<Integer, User> lockUsers(Integer first, Integer second) {
        var locked = new LinkedHashMap<Integer, User>();
        new LinkedHashSet<>(List.of(first, second)).stream().sorted().forEach(id ->
            locked.put(id, userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> notFound("Usuário " + id + " não encontrado")))
        );
        return locked;
    }

    private User requireActor(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Administrador não autenticado");
        }
        return userRepository.findByEmail(email.trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Administrador não encontrado"));
    }

    private User requireUser(Integer userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> notFound("Usuário " + userId + " não encontrado"));
    }

    private Map<Integer, List<DiscordAccount>> discordAccounts(Collection<Integer> userIds) {
        Map<Integer, List<DiscordAccount>> accounts = new HashMap<>();
        for (UserDiscord link : userDiscordRepository.findByUserIdIn(userIds)) {
            accounts.computeIfAbsent(link.getUser().getId(), ignored -> new ArrayList<>())
                .add(new DiscordAccount(String.valueOf(link.getDiscordUserId()), link.getUsername(), link.getDisplayName()));
        }
        accounts.values().forEach(list -> list.sort(Comparator.comparing(DiscordAccount::discordUserId)));
        return accounts;
    }

    private IdentityLinkView toView(
        UserIdentityLink link,
        Map<Integer, User> usersById,
        Map<Integer, List<DiscordAccount>> discord
    ) {
        User userA = usersById.getOrDefault(link.getUserA().getId(), link.getUserA());
        User userB = usersById.getOrDefault(link.getUserB().getId(), link.getUserB());
        return new IdentityLinkView(
            link.getId(),
            toSummary(userA, discord),
            toSummary(userB, discord),
            link.getStatus(),
            link.getLinkSource(),
            link.getReason(),
            link.getCreatedByUser() == null ? null : link.getCreatedByUser().getId(),
            link.getCreatedAt(),
            link.getRevokedAt(),
            link.getRevokedByUser() == null ? null : link.getRevokedByUser().getId(),
            link.getRevocationReason()
        );
    }

    private UserSummary toSummary(User user, Map<Integer, List<DiscordAccount>> discord) {
        return new UserSummary(
            user.getId(),
            user.getUsername(),
            user.getDisplayName(),
            user.getEmail(),
            List.copyOf(discord.getOrDefault(user.getId(), List.of()))
        );
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw badRequest("Motivo é obrigatório");
        }
        return normalized;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
