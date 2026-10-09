package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserIdentityLink;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class UserIdentityLinkServiceTest {

    @Mock UserRepository users;
    @Mock UserDiscordRepository discord;
    @Mock UserIdentityLinkRepository links;
    @Mock ConfirmedIdentityClusterService clusters;
    @Mock IdentityBanPropagationService banPropagation;

    private UserIdentityLinkService service;
    private User userA;
    private User userB;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new UserIdentityLinkService(users, discord, links, clusters, banPropagation);
        userA = user(10, "User A");
        userB = user(20, "User B");
        actor = user(99, "Admin");
    }

    @Test
    void createsNeutralConfirmedEdgeWithoutMovingUsers() {
        stubLockedUsers();
        stubActor();
        stubDiscord();
        when(links.findActiveByPair(10, 20)).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 7L));

        var result = service.create(10, 20, UserIdentityLinkStatus.CONFIRMED, " mesma pessoa ", "admin@example.com");

        assertEquals(7L, result.id());
        assertEquals(10, result.userA().userId());
        assertEquals(20, result.userB().userId());
        assertEquals("mesma pessoa", result.reason());
        verify(users, never()).delete(any());
        verify(users, never()).deleteById(any());
        verify(discord, never()).save(any());
        verify(banPropagation).propagateForConfirmedCluster(10);
    }

    @Test
    void suspectedCreationDoesNotPropagateBans() {
        stubLockedUsers();
        stubActor();
        stubDiscord();
        when(links.findActiveByPair(10, 20)).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 6L));

        service.create(10, 20, UserIdentityLinkStatus.SUSPECTED, "suspeita", "admin@example.com");

        verifyNoInteractions(banPropagation);
    }

    @Test
    void confirmedPropagationWaitsForTransactionCommitWhenSynchronizationIsActive() {
        stubLockedUsers();
        stubActor();
        stubDiscord();
        when(links.findActiveByPair(10, 20)).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 5L));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(
                10,
                20,
                UserIdentityLinkStatus.CONFIRMED,
                "mesma pessoa",
                "admin@example.com"
            );

            verifyNoInteractions(banPropagation);
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
            verify(banPropagation).propagateForConfirmedCluster(10);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void treatsReverseDirectionAsDuplicateConflict() {
        stubLockedUsers();
        stubActor();
        UserIdentityLink existing = edge(userB, userA, UserIdentityLinkStatus.CONFIRMED);
        when(links.findActiveByPair(10, 20)).thenReturn(Optional.of(existing));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.create(10, 20, UserIdentityLinkStatus.CONFIRMED, "motivo", "admin@example.com"));

        assertEquals(409, error.getStatusCode().value());
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void permitsJoiningExistingConfirmedClusters() {
        stubLockedUsers();
        stubActor();
        stubDiscord();
        when(links.findActiveByPair(10, 20)).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 8L));

        var result = service.create(10, 20, UserIdentityLinkStatus.CONFIRMED, "unir clusters", "admin@example.com");

        assertEquals(UserIdentityLinkStatus.CONFIRMED, result.status());
        assertEquals(8L, result.id());
        verifyNoInteractions(clusters);
    }

    @Test
    void rejectsSelfLink() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.create(10, 10, UserIdentityLinkStatus.CONFIRMED, "motivo", "admin@example.com"));

        assertEquals(400, error.getStatusCode().value());
        verify(links, never()).save(any());
    }

    @Test
    void updatesAndRevokesPairFromEitherDirection() {
        stubLockedUsers();
        stubActor();
        stubDiscord();
        UserIdentityLink existing = persisted(edge(userB, userA, UserIdentityLinkStatus.SUSPECTED), 9L);
        when(links.findActiveByPair(10, 20)).thenReturn(Optional.of(existing));
        when(links.findActiveByPair(20, 10)).thenReturn(Optional.of(existing));
        when(links.save(existing)).thenReturn(existing);

        var updated = service.update(10, 20, UserIdentityLinkStatus.CONFIRMED, " confirmação ");
        var revoked = service.revoke(20, 10, " vínculo incorreto ", "admin@example.com");

        assertEquals(UserIdentityLinkStatus.CONFIRMED, updated.status());
        assertNotNull(revoked.revokedAt());
        assertEquals(99, revoked.revokedByUserId());
        assertEquals("vínculo incorreto", revoked.revocationReason());
        assertSame(actor, existing.getRevokedByUser());
        verify(links, never()).delete(any());
        verify(banPropagation).propagateForConfirmedCluster(10);
    }

    @Test
    void returnsSameConfirmedAccountsForAnyClusterMember() {
        User userC = user(30, "User C");
        when(users.findById(10)).thenReturn(Optional.of(userA));
        when(users.findById(30)).thenReturn(Optional.of(userC));
        when(users.findAllById(any())).thenReturn(List.of(userA, userB, userC));
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10, 20, 30));
        when(clusters.resolveConfirmedUserIds(30)).thenReturn(Set.of(10, 20, 30));
        var edgeAB = persisted(edge(userA, userB, UserIdentityLinkStatus.CONFIRMED), 11L);
        var edgeBC = persisted(edge(userB, userC, UserIdentityLinkStatus.CONFIRMED), 12L);
        when(links.findActiveLinksContainingUsersByStatus(
            any(), org.mockito.ArgumentMatchers.eq(UserIdentityLinkStatus.CONFIRMED)
        )).thenReturn(List.of(edgeAB, edgeBC));
        when(links.findActiveLinksContainingUsersByStatus(
            any(), org.mockito.ArgumentMatchers.eq(UserIdentityLinkStatus.SUSPECTED)
        )).thenReturn(List.of());

        var fromA = service.getIdentity(10);
        var fromC = service.getIdentity(30);

        assertEquals(List.of(10, 20, 30), fromA.confirmedAccounts().stream().map(account -> account.userId()).toList());
        assertEquals(fromA.confirmedAccounts(), fromC.confirmedAccounts());
        assertEquals(List.of(11L, 12L), fromA.confirmedLinks().stream().map(link -> link.id()).toList());
        assertEquals(fromA.confirmedLinks(), fromC.confirmedLinks());
        assertEquals(10, fromA.requestedUserId());
        assertEquals(30, fromC.requestedUserId());
    }

    private UserIdentityLink persisted(UserIdentityLink link, long id) {
        link.setId(id);
        link.setCreatedAt(LocalDateTime.now());
        return link;
    }

    private void stubLockedUsers() {
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(userA));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(userB));
    }

    private void stubActor() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
    }

    private void stubDiscord() {
        when(discord.findByUserIdIn(any())).thenReturn(List.of());
    }

    private User user(int id, String name) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(name);
        return user;
    }

    private UserIdentityLink edge(User a, User b, UserIdentityLinkStatus status) {
        UserIdentityLink link = new UserIdentityLink();
        link.setUserA(a);
        link.setUserB(b);
        link.setStatus(status);
        return link;
    }
}
