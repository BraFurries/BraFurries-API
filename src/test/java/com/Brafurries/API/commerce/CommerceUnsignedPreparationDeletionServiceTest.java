package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceUnsignedPreparationDeletionServiceTest {
    private static final String KEY=
        "commerce/contracts/41/12345678-1234-1234-1234-123456789abc.pdf";
    @Mock PlatformAuthorizationService authorization;
    @Mock JdbcTemplate jdbc;
    @Mock CommercePrivateDocumentStorage documents;
    @Mock PlatformTransactionManager transactionManager;
    @Mock Authentication auth;
    private CommerceUnsignedPreparationDeletionService service;

    @BeforeEach void setup() {
        User owner=new User();
        owner.setId(11);
        lenient().when(authorization.requireOwner(auth)).thenReturn(owner);
        lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
        service=new CommerceUnsignedPreparationDeletionService(
            authorization,jdbc,documents,transactionManager);
    }

    private void unsigned(String status) {
        when(jdbc.queryForList(contains("FROM commerce_artworks"),eq(41L)))
            .thenReturn(List.of(Map.of("id",41L)));
        Map<String,Object> row=new HashMap<>();
        row.put("status",status);
        row.put("signed_contract_id",null);
        row.put("generated_storage_key",KEY);
        when(jdbc.queryForList(contains("FROM commerce_contract_preparations"),
            eq(5L),eq(41L))).thenReturn(List.of(row));
    }

    @Test void refusesNonOwnerBeforeAnyDatabaseOrR2Operation() {
        when(authorization.requireOwner(auth))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.delete(auth,41L,5L,null)).getStatusCode());
        verifyNoInteractions(jdbc,documents,transactionManager);
    }

    @Test void deletesPendingRowAndAuditsBeforeR2Cleanup() {
        unsigned("PENDING");
        when(jdbc.update(contains("DELETE FROM commerce_contract_preparations"),
            eq(5L),eq(41L))).thenReturn(1);
        service.delete(auth,41L,5L,null);
        verify(jdbc).update(contains("DELETED_UNSIGNED"),eq(11),eq(5L));
        InOrder order=inOrder(transactionManager,documents);
        order.verify(transactionManager).commit(any());
        order.verify(documents).deleteConfirmedGenerated(41L,KEY);
    }

    @Test void canPurgeCancelledButNeverSignedDrafts() {
        unsigned("CANCELLED");
        when(jdbc.update(contains("DELETE FROM commerce_contract_preparations"),
            eq(5L),eq(41L))).thenReturn(1);
        service.delete(auth,41L,5L,KEY);
        verify(documents).deleteConfirmedGenerated(41L,KEY);
    }

    @Test void signedDraftOrLinkedAgreementIsProtected() {
        unsigned("SIGNED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.delete(auth,41L,5L,null)).getStatusCode());
        verify(documents,never()).deleteConfirmedGenerated(anyLong(),anyString());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void rejectsStaleKeyOrCrossReferencedSignedObject() {
        unsigned("PENDING");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.delete(auth,41L,5L,"commerce/contracts/41/other.pdf")).getStatusCode());
        when(jdbc.queryForList(contains("FROM commerce_artwork_contracts"),eq(KEY)))
            .thenReturn(List.of(Map.of("id",99)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.delete(auth,41L,5L,null)).getStatusCode());
        verify(documents,never()).deleteConfirmedGenerated(anyLong(),anyString());
    }

    @Test void failedDatabaseRemovalDoesNotDeleteR2File() {
        unsigned("PENDING");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.delete(auth,41L,5L,null)).getStatusCode());
        verify(transactionManager).rollback(any());
        verifyNoInteractions(documents);
    }

    @Test void failedR2CleanupReportsPartialSuccessAfterDatabaseCommit() {
        unsigned("PENDING");
        when(jdbc.update(contains("DELETE FROM commerce_contract_preparations"),
            eq(5L),eq(41L))).thenReturn(1);
        doThrow(new IllegalStateException("R2 unavailable")).when(documents).deleteConfirmedGenerated(41L,KEY);
        ResponseStatusException ex=assertThrows(ResponseStatusException.class,
            ()->service.delete(auth,41L,5L,null));
        assertEquals(HttpStatus.BAD_GATEWAY,ex.getStatusCode());
        assertTrue(ex.getReason().contains("Minuta removida do cadastro"));
        verify(transactionManager).commit(any());
    }
}
