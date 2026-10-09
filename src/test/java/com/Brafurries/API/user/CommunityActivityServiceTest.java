package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.user.dto.CommunityActivityDtos.ActivityPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityActivityServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityTeamDemandRepository demands;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityDiscordRepository discord;
    @Mock Authentication auth;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private CommunityActivityService service;
    private Community community;
    private User owner;
    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        service = new CommunityActivityService(
            authorization,
            auditLogs,
            roles,
            demands,
            memberships,
            discord,
            objectMapper
        );
        community = new Community();
        community.setId(10);
        community.setName("BraFurries");
        owner = user(1, "Titio");
        admin = user(2, "Magia");
        member = user(3, "Ninsei");
        community.setOwnerUser(owner);
    }

    @Test
    void ownerCanReadActivity() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of());

        ActivityPage page = service.list(auth, 10, null, null, null, null, null, 30);

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    void communityAdminCanReadActivity() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of());

        ActivityPage page = service.list(auth, 10, null, null, null, null, null, 30);

        assertTrue(page.items().isEmpty());
    }

    @Test
    void ordinaryMemberCannotReadActivity() {
        when(authorization.resolve(auth, 10)).thenReturn(context(member, false, false));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.list(auth, 10, null, null, null, null, null, 30)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void inaccessibleCommunityNeverReachesAuditRepository() {
        when(authorization.resolve(auth, 99)).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Community não encontrada")
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.list(auth, 99, null, null, null, null, null, 30)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void usesStableCursorFromLastVisibleRow() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        LocalDateTime t3 = LocalDateTime.of(2026, 10, 4, 15, 3);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 4, 15, 2);
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 4, 15, 1);
        CommunityAuditLog third = audit(103L, "SOME_FUTURE_ACTION", null, null, null, t3);
        CommunityAuditLog second = audit(102L, "SOME_FUTURE_ACTION", null, null, null, t2);
        CommunityAuditLog first = audit(101L, "SOME_FUTURE_ACTION", null, null, null, t1);

        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of(third, second, first));

        ActivityPage firstPage = service.list(auth, 10, null, null, null, null, null, 2);

        assertEquals(2, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertNotNull(firstPage.nextCursor());

        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), eq(t2), eq(102L),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of(first));

        ActivityPage nextPage = service.list(
            auth, 10, null, null, null, null, firstPage.nextCursor(), 2
        );

        assertEquals(List.of(101L), nextPage.items().stream().map(item -> item.id()).toList());
        assertFalse(nextPage.hasMore());
    }

    @Test
    void appliesExplicitCategoryActorAndPeriodFilters() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 4, 23, 59);

        when(auditLogs.findCommunityActivity(
            eq(10), eq(2), eq(from), eq(to), isNull(), isNull(),
            eq("INCLUDE"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of());

        service.list(auth, 10, "team", 2, from, to, null, 30);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> actions = ArgumentCaptor.forClass(Collection.class);
        verify(auditLogs).findCommunityActivity(
            eq(10), eq(2), eq(from), eq(to), isNull(), isNull(),
            eq("INCLUDE"), actions.capture(), anyCollection(), any(Pageable.class)
        );
        assertTrue(actions.getValue().contains("TEAM_ROLE_CREATED"));
        assertTrue(actions.getValue().contains("TEAM_MEMBER_ASSIGNED"));
        assertFalse(actions.getValue().contains("MEMBER_NOTE_CREATED"));
    }

    @Test
    void resolvesExistingRoleOnlyInsideAuthorizedCommunity() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        CommunityAuditLog row = audit(
            200L, "TEAM_ROLE_CREATED", "TEAM_ROLE", "42", "{}", LocalDateTime.now()
        );
        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of(row));

        CommunityTeamRole role = new CommunityTeamRole();
        role.setId(42);
        role.setName("Eventos");
        role.setCommunity(community);
        when(roles.findByCommunityAndIdInOrderByNameAsc(eq(community), anyCollection()))
            .thenReturn(List.of(role));

        ActivityPage page = service.list(auth, 10, null, null, null, null, null, 30);

        assertEquals("Eventos", page.items().getFirst().target().displayName());
        verify(roles).findByCommunityAndIdInOrderByNameAsc(eq(community), anyCollection());
        verify(roles, never()).findById(anyInt());
    }

    @Test
    void deletedTargetUsesSafeFallback() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        CommunityAuditLog row = audit(
            201L, "TEAM_ROLE_DELETED", "TEAM_ROLE", "42", null, LocalDateTime.now()
        );
        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of(row));
        when(roles.findByCommunityAndIdInOrderByNameAsc(eq(community), anyCollection()))
            .thenReturn(List.of());

        ActivityPage page = service.list(auth, 10, null, null, null, null, null, 30);

        assertEquals("Cargo removido #42", page.items().getFirst().target().displayName());
    }

    @Test
    void noteAuditNeverExposesNoteContentFromMetadata() throws Exception {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        CommunityAuditLog row = audit(
            202L,
            "MEMBER_NOTE_UPDATED",
            "MEMBER_NOTE",
            "55",
            "{\"memberUserId\":3,\"content\":\"SEGREDO\",\"oldContent\":\"ANTIGO\"}",
            LocalDateTime.now()
        );
        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of(row));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(10), anyCollection()))
            .thenReturn(List.of(membership(member)));

        ActivityPage page = service.list(auth, 10, null, null, null, null, null, 30);
        String serialized = objectMapper.writeValueAsString(page);

        assertEquals("Ninsei", page.items().getFirst().details().memberDisplayName());
        assertFalse(serialized.contains("SEGREDO"));
        assertFalse(serialized.contains("ANTIGO"));
        assertFalse(serialized.contains("oldContent"));
        assertFalse(serialized.contains("\"content\""));
    }

    @Test
    void unknownActionFallsBackWithoutPublishingMetadata() throws Exception {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        CommunityAuditLog row = audit(
            203L,
            "SOME_FUTURE_ACTION",
            "FUTURE_TARGET",
            "abc",
            "{\"secret\":\"never-publish\"}",
            LocalDateTime.now()
        );
        when(auditLogs.findCommunityActivity(
            eq(10), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq("ALL"), anyCollection(), anyCollection(), any(Pageable.class)
        )).thenReturn(List.of(row));

        ActivityPage page = service.list(auth, 10, null, null, null, null, null, 30);
        String serialized = objectMapper.writeValueAsString(page);

        assertEquals("OTHER", page.items().getFirst().category());
        assertEquals("SOME_FUTURE_ACTION", page.items().getFirst().action());
        assertEquals("FUTURE_TARGET #abc", page.items().getFirst().target().displayName());
        assertFalse(serialized.contains("never-publish"));
        assertFalse(serialized.contains("secret"));
    }

    @Test
    void actorsComeOnlyFromAuthorizedCommunityAuditRowsAndExposeNoEmail() throws Exception {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        admin.setEmail("private@example.com");
        when(auditLogs.findDistinctActorsByCommunityId(10)).thenReturn(List.of(admin));

        var actors = service.listActors(auth, 10);
        String serialized = objectMapper.writeValueAsString(actors);

        assertEquals(1, actors.size());
        assertEquals("Magia", actors.getFirst().displayName());
        assertFalse(serialized.contains("private@example.com"));
        verify(auditLogs).findDistinctActorsByCommunityId(10);
    }

    @Test
    void rejectsInvalidPeriodAndCursorBeforeQueryingAuditRows() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));

        ResponseStatusException periodError = assertThrows(
            ResponseStatusException.class,
            () -> service.list(
                auth,
                10,
                null,
                null,
                LocalDateTime.of(2026, 10, 5, 0, 0),
                LocalDateTime.of(2026, 10, 4, 0, 0),
                null,
                30
            )
        );
        assertEquals(HttpStatus.BAD_REQUEST, periodError.getStatusCode());

        ResponseStatusException cursorError = assertThrows(
            ResponseStatusException.class,
            () -> service.list(auth, 10, null, null, null, null, "not-a-cursor", 30)
        );
        assertEquals(HttpStatus.BAD_REQUEST, cursorError.getStatusCode());

        verifyNoInteractions(auditLogs);
    }

    private CommunityAuthorizationService.CommunityAccessContext context(
        User user,
        boolean isOwner,
        boolean isAdmin
    ) {
        return new CommunityAuthorizationService.CommunityAccessContext(
            community,
            user,
            isOwner,
            isAdmin,
            true,
            Set.of(),
            List.of(),
            Map.of(),
            Set.of()
        );
    }

    private CommunityAuditLog audit(
        Long id,
        String action,
        String targetType,
        String targetId,
        String metadata,
        LocalDateTime createdAt
    ) {
        CommunityAuditLog row = new CommunityAuditLog();
        row.setId(id);
        row.setCommunity(community);
        row.setActorUser(owner);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setMetadata(metadata);
        row.setCreatedAt(createdAt);
        return row;
    }

    private User user(Integer id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setUsername(displayName.toLowerCase());
        return user;
    }

    private UserCommunityStatus membership(User user) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setCommunity(community);
        membership.setUser(user);
        return membership;
    }
}
