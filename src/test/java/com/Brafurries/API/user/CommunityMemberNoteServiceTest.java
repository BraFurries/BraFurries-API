package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityMemberNote;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityMemberNoteRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.user.dto.CommunityMemberNoteDtos.CommunityMemberNoteResponse;
import com.Brafurries.API.user.dto.CommunityMemberNoteDtos.SaveCommunityMemberNoteRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityMemberNoteServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock CommunityMemberNoteRepository notes;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock Authentication auth;

    private CommunityMemberNoteService service;
    private Community community;
    private User actor;
    private User target;

    @BeforeEach
    void setUp() {
        service = new CommunityMemberNoteService(
            authorization,
            notes,
            memberships,
            auditLogs
        );
        community = new Community();
        community.setId(10);
        community.setName("BraFurries");

        actor = user(1, "Admin");
        target = user(7, "Fox");
    }

    @Test
    void listRequiresNotesViewAndStaysInsideTargetMembership() {
        CommunityMemberNote note = note(100L, "Contexto interno", false);
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_VIEW
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_VIEW)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership()));
        when(notes.findMemberNotes(10, 7, false)).thenReturn(List.of(note));

        List<CommunityMemberNoteResponse> result = service.list(auth, 10, 7, false);

        assertEquals(1, result.size());
        assertEquals("Contexto interno", result.getFirst().content());
        verify(notes).findMemberNotes(10, 7, false);
    }

    @Test
    void createRequiresManageAndAuditsWithoutCopyingNoteContent() {
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(
            CommunityCapability.MEMBER_NOTES_VIEW,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership()));
        when(notes.saveAndFlush(any(CommunityMemberNote.class))).thenAnswer(invocation -> {
            CommunityMemberNote value = invocation.getArgument(0);
            value.setId(100L);
            value.setCreatedAt(LocalDateTime.of(2026, 10, 1, 20, 0));
            value.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 20, 0));
            return value;
        });

        CommunityMemberNoteResponse result = service.create(
            auth,
            10,
            7,
            new SaveCommunityMemberNoteRequest("  conteúdo sensível da nota  ")
        );

        assertEquals("conteúdo sensível da nota", result.content());
        assertEquals(1, result.authorUserId());
        assertEquals(1, result.updatedByUserId());

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("MEMBER_NOTE_CREATED", audit.getValue().getAction());
        assertEquals("MEMBER_NOTE", audit.getValue().getTargetType());
        assertEquals("100", audit.getValue().getTargetId());
        assertTrue(audit.getValue().getMetadata().contains("\"memberUserId\":7"));
        assertFalse(audit.getValue().getMetadata().contains("conteúdo sensível"));
    }

    @Test
    void updateRejectsArchivedNote() {
        CommunityMemberNote archived = note(100L, "Anterior", true);
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_MANAGE)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership()));
        when(notes.findForUpdate(100L, 10, 7)).thenReturn(Optional.of(archived));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.update(
                auth,
                10,
                7,
                100L,
                new SaveCommunityMemberNoteRequest("novo")
            )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(notes, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void updateChangesLastEditorAndAuditsOnlyIdentifiers() {
        CommunityMemberNote existing = note(100L, "Anterior", false);
        User editor = actor;
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_MANAGE)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership()));
        when(notes.findForUpdate(100L, 10, 7)).thenReturn(Optional.of(existing));
        when(notes.saveAndFlush(existing)).thenReturn(existing);

        CommunityMemberNoteResponse result = service.update(
            auth,
            10,
            7,
            100L,
            new SaveCommunityMemberNoteRequest("Atualizada")
        );

        assertEquals("Atualizada", result.content());
        assertSame(editor, existing.getUpdatedByUser());
        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("MEMBER_NOTE_UPDATED", audit.getValue().getAction());
        assertFalse(audit.getValue().getMetadata().contains("Atualizada"));
    }

    @Test
    void archiveIsIdempotentAndDoesNotHardDelete() {
        CommunityMemberNote existing = note(100L, "Nota", false);
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_MANAGE)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership()));
        when(notes.findForUpdate(100L, 10, 7)).thenReturn(Optional.of(existing));
        when(notes.saveAndFlush(existing)).thenReturn(existing);

        CommunityMemberNoteResponse first = service.archive(auth, 10, 7, 100L);

        assertNotNull(first.archivedAt());
        verify(notes).saveAndFlush(existing);
        verify(auditLogs).save(any(CommunityAuditLog.class));
        verify(notes, never()).delete(any());

        reset(auditLogs, notes);
        when(notes.findForUpdate(100L, 10, 7)).thenReturn(Optional.of(existing));

        CommunityMemberNoteResponse second = service.archive(auth, 10, 7, 100L);

        assertEquals(first.archivedAt(), second.archivedAt());
        verify(notes, never()).saveAndFlush(any());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void noteIdFromAnotherMemberOrCommunityReturnsNotFound() {
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_MANAGE)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(membership()));
        when(notes.findForUpdate(999L, 10, 7)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.archive(auth, 10, 7, 999L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void targetWithoutCommunityMembershipCannotReceiveNotes() {
        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_MANAGE)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(99, 10))
            .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                10,
                99,
                new SaveCommunityMemberNoteRequest("Nota")
            )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(notes, auditLogs);
    }

    @Test
    void ambiguousLegacyTargetMembershipFailsClosedForNoteMutation() {
        UserCommunityStatus first = membership();
        UserCommunityStatus second = membership();
        second.setId(2);

        when(authorization.requireCapability(
            auth,
            10,
            CommunityCapability.MEMBER_NOTES_MANAGE
        )).thenReturn(context(Set.of(CommunityCapability.MEMBER_NOTES_MANAGE)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 10))
            .thenReturn(List.of(first, second));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                10,
                7,
                new SaveCommunityMemberNoteRequest("Nota")
            )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verifyNoInteractions(notes, auditLogs);
    }

    private CommunityAuthorizationService.CommunityAccessContext context(
        Set<CommunityCapability> capabilities
    ) {
        return new CommunityAuthorizationService.CommunityAccessContext(
            community,
            actor,
            false,
            false,
            true,
            capabilities,
            List.of(),
            Map.of(),
            Set.of()
        );
    }

    private UserCommunityStatus membership() {
        UserCommunityStatus value = new UserCommunityStatus();
        value.setId(1);
        value.setCommunity(community);
        value.setUser(target);
        value.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));
        value.setApproved(true);
        value.setBanned(false);
        value.setIsPresent(true);
        return value;
    }

    private CommunityMemberNote note(Long id, String content, boolean archived) {
        CommunityMemberNote value = new CommunityMemberNote();
        value.setId(id);
        value.setCommunity(community);
        value.setUserId(7);
        value.setAuthorUser(actor);
        value.setUpdatedByUser(actor);
        value.setContent(content);
        value.setCreatedAt(LocalDateTime.of(2026, 10, 1, 20, 0));
        value.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 20, 0));
        if (archived) {
            value.setArchivedAt(LocalDateTime.of(2026, 10, 1, 21, 0));
        }
        return value;
    }

    private User user(int id, String displayName) {
        User value = new User();
        value.setId(id);
        value.setDisplayName(displayName);
        value.setUsername(displayName.toLowerCase());
        value.setEmail("user-" + id + "@example.com");
        return value;
    }
}
