package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserIdentityLink;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfirmedIdentityClusterServiceTest {

    @Mock UserIdentityLinkRepository links;

    private final List<UserIdentityLink> storedEdges = new ArrayList<>();
    private ConfirmedIdentityClusterService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmedIdentityClusterService(links);
        when(links.findActiveLinksContainingUsersByStatus(any(), eq(UserIdentityLinkStatus.CONFIRMED)))
            .thenAnswer(invocation -> {
                Collection<Integer> frontier = invocation.getArgument(0);
                return storedEdges.stream()
                    .filter(edge -> edge.getRevokedAt() == null)
                    .filter(edge -> edge.getStatus() == UserIdentityLinkStatus.CONFIRMED)
                    .filter(edge -> frontier.contains(edge.getUserA().getId()) || frontier.contains(edge.getUserB().getId()))
                    .toList();
            });
    }

    @Test
    void resolvesBothSidesOfSingleEdge() {
        storedEdges.add(edge(1, 2, UserIdentityLinkStatus.CONFIRMED));

        assertEquals(Set.of(1, 2), service.resolveConfirmedUserIds(1));
        assertEquals(Set.of(1, 2), service.resolveConfirmedUserIds(2));
    }

    @Test
    void resolvesTransitiveChainFromEveryMember() {
        storedEdges.add(edge(1, 2, UserIdentityLinkStatus.CONFIRMED));
        storedEdges.add(edge(2, 3, UserIdentityLinkStatus.CONFIRMED));

        assertEquals(Set.of(1, 2, 3), service.resolveConfirmedUserIds(1));
        assertEquals(Set.of(1, 2, 3), service.resolveConfirmedUserIds(2));
        assertEquals(Set.of(1, 2, 3), service.resolveConfirmedUserIds(3));
    }

    @Test
    void handlesCycleWithoutLoopOrDuplicates() {
        storedEdges.add(edge(1, 2, UserIdentityLinkStatus.CONFIRMED));
        storedEdges.add(edge(2, 3, UserIdentityLinkStatus.CONFIRMED));
        storedEdges.add(edge(3, 1, UserIdentityLinkStatus.CONFIRMED));

        assertEquals(Set.of(1, 2, 3), service.resolveConfirmedUserIds(1));
        verify(links, atMost(3)).findActiveLinksContainingUsersByStatus(any(), eq(UserIdentityLinkStatus.CONFIRMED));
    }

    @Test
    void excludesSuspectedEdges() {
        storedEdges.add(edge(1, 2, UserIdentityLinkStatus.CONFIRMED));
        storedEdges.add(edge(2, 3, UserIdentityLinkStatus.SUSPECTED));

        assertEquals(Set.of(1, 2), service.resolveConfirmedUserIds(1));
        assertEquals(Set.of(3), service.resolveConfirmedUserIds(3));
    }

    @Test
    void revokedBridgeSeparatesComponents() {
        storedEdges.add(edge(1, 2, UserIdentityLinkStatus.CONFIRMED));
        UserIdentityLink bridge = edge(2, 3, UserIdentityLinkStatus.CONFIRMED);
        bridge.setRevokedAt(LocalDateTime.now());
        storedEdges.add(bridge);

        assertEquals(Set.of(1, 2), service.resolveConfirmedUserIds(1));
        assertEquals(Set.of(3), service.resolveConfirmedUserIds(3));
    }

    @Test
    void newConfirmedBridgeJoinsTwoExistingClusters() {
        storedEdges.add(edge(1, 2, UserIdentityLinkStatus.CONFIRMED));
        storedEdges.add(edge(3, 4, UserIdentityLinkStatus.CONFIRMED));
        assertEquals(Set.of(1, 2), service.resolveConfirmedUserIds(1));

        storedEdges.add(edge(2, 3, UserIdentityLinkStatus.CONFIRMED));

        assertEquals(Set.of(1, 2, 3, 4), service.resolveConfirmedUserIds(1));
        assertEquals(Set.of(1, 2, 3, 4), service.resolveConfirmedUserIds(4));
    }

    private UserIdentityLink edge(int a, int b, UserIdentityLinkStatus status) {
        UserIdentityLink edge = new UserIdentityLink();
        edge.setUserA(user(a));
        edge.setUserB(user(b));
        edge.setStatus(status);
        return edge;
    }

    private User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
