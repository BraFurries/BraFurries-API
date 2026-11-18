package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioRetentionHoldServiceTest {
    @Mock PlatformAuthorizationService identity;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioRetentionHoldService service;

    @BeforeEach void init() {
        service = new CreatorStudioRetentionHoldService(identity,jdbc);
    }

    private User owner() {
        User user=new User();
        user.setId(1);
        return user;
    }

    @Test void communityAdminWithoutPlatformOwnershipCannotInspectOrCreateHold() {
        when(identity.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.inspect(auth,31,100L)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.place(auth,new CreatorStudioRetentionHoldService.HoldDraft(
                31,100L,CreatorStudioRetentionHoldService.Category.DISPUTE,
                "Pedido contestado"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void refusesMissingOrForeignCommissionBeforeCreatingAnyLegalRecord() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        var draft = new CreatorStudioRetentionHoldService.HoldDraft(
            31,100L,CreatorStudioRetentionHoldService.Category.LEGAL_OBLIGATION,
            "Conservação contratual");
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            () -> service.place(auth,draft)).getStatusCode());
        verify(jdbc).query(contains("FOR UPDATE"),any(RowMapper.class),eq(31),eq(100L));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void rejectsUnjustifiedHoldsWithEmptyReasons() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        var draft = new CreatorStudioRetentionHoldService.HoldDraft(
            31,100L,CreatorStudioRetentionHoldService.Category.OTHER," ");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            () -> service.place(auth,draft)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void insertHoldAndMinimalAuditInSameTransaction() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        when(jdbc.query(contains("FOR UPDATE"),any(RowMapper.class),eq(31),eq(100L)))
            .thenReturn(List.of(100L));
        when(jdbc.query(contains("FROM creator_studio_retention_holds"),
            any(RowMapper.class),eq(31),eq(100L)))
            .thenReturn(List.of(),List.of(new CreatorStudioRetentionHoldService.Hold(
                31,100L,true,CreatorStudioRetentionHoldService.Category.DISPUTE,
                "Contestação informada")));
        var hold=service.place(auth,new CreatorStudioRetentionHoldService.HoldDraft(
            31,100L,CreatorStudioRetentionHoldService.Category.DISPUTE,
            "Contestação informada"));
        assertTrue(hold.active());
        var ordered=inOrder(jdbc);
        ordered.verify(jdbc).update(contains("INSERT INTO creator_studio_retention_holds"),
            eq(31),eq(100L),eq("DISPUTE"),eq("Contestação informada"),eq(1),eq(1));
        ordered.verify(jdbc).update(contains("INSERT INTO creator_studio_retention_hold_events"),
            eq(31),eq(100L),eq(1),eq("PLACED"),eq("DISPUTE"));
    }

    @Test void repeatedUnchangedHoldDoesNotDuplicateAudit() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        when(jdbc.query(contains("FOR UPDATE"),any(RowMapper.class),eq(31),eq(100L)))
            .thenReturn(List.of(100L));
        when(jdbc.query(contains("FROM creator_studio_retention_holds"),
            any(RowMapper.class),eq(31),eq(100L)))
            .thenReturn(List.of(new CreatorStudioRetentionHoldService.Hold(
                31,100L,true,CreatorStudioRetentionHoldService.Category.DISPUTE,
                "Em disputa")));
        var h=service.place(auth,new CreatorStudioRetentionHoldService.HoldDraft(
            31,100L,CreatorStudioRetentionHoldService.Category.DISPUTE,"Em disputa"));
        assertTrue(h.active());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
