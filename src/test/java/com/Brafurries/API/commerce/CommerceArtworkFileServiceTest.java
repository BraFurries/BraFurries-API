package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommerceArtworkFileServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommerceArtworkFileStorage storage;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    @Mock MultipartFile multipart;
    private CommerceArtworkFileService service;

    @BeforeEach
    void init() {
        service=new CommerceArtworkFileService(authorization,storage,support,jdbc);
        var user=new User();user.setId(7);
        lenient().when(authorization.require(eq(auth),eq(PlatformPermission.ARTWORKS_MANAGE)))
            .thenReturn(new PlatformAuthorizationService.Actor(user,
                new PlatformAuthorizationService.Access(true,true,java.util.Set.of())));
    }

    @Test
    void deniesListBeforeAnyDbOrStorageLookup() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.list(auth,14)).getStatusCode());
        verifyNoInteractions(jdbc,storage);
    }

    @Test
    void neverExposesPrivateKeyInMetadataList() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L)))
            .thenReturn(List.of(14L));
        when(jdbc.queryForList(contains("FROM commerce_artwork_files"),eq(14L)))
            .thenReturn(List.of(Map.of("id",25L)));
        assertEquals(25L,service.list(auth,14).get(0).get("id"));
        verify(jdbc).queryForList(argThat(sql->sql.contains("original_name")
            && !sql.contains("storage_key")),eq(14L));
    }

    @Test
    void doesNotUploadUntilArtworkExistsAndKindIsValid() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.upload(auth,14,"SOURCE",null,multipart)).getStatusCode());
        verifyNoInteractions(storage);
    }

    @Test
    void compensatesR2WhenDatabaseInsertionFails() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L)))
            .thenReturn(List.of(14L));
        var stored=new CommerceArtworkFileStorage.StoredFile(
            "commerce/artworks/14/00000000-0000-4000-8000-000000000000.psd",
            "source.psd","psd","application/octet-stream","a".repeat(64),1000);
        when(storage.upload(14L,multipart)).thenReturn(stored);
        when(support.insert(anyString(),any(Object[].class)))
            .thenThrow(new IllegalStateException("database failed"));
        assertThrows(IllegalStateException.class,
            ()->service.upload(auth,14,"SOURCE",null,multipart));
        verify(storage).deleteUnregistered(14L,stored.key());
    }

    @Test
    void compensatesUploadedFileWhenOuterTransactionRollsBackAfterInsert() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L)))
            .thenReturn(List.of(14L));
        var stored=new CommerceArtworkFileStorage.StoredFile(
            "commerce/artworks/14/00000000-0000-4000-8000-000000000000.psd",
            "source.psd","psd","application/octet-stream","a".repeat(64),1000);
        when(storage.upload(14L,multipart)).thenReturn(stored);
        when(support.insert(anyString(),any(Object[].class))).thenReturn(25L);

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(25L,service.upload(auth,14,"SOURCE",null,multipart).id());
            verify(storage,never()).deleteUnregistered(anyLong(),anyString());
            var synchronizations=org.springframework.transaction.support
                .TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1,synchronizations.size());
            synchronizations.get(0).afterCompletion(
                org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(storage).deleteUnregistered(14L,stored.key());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void keepsFileAfterSuccessfulTransactionCommit() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L)))
            .thenReturn(List.of(14L));
        var stored=new CommerceArtworkFileStorage.StoredFile(
            "commerce/artworks/14/00000000-0000-4000-8000-000000000000.psd",
            "source.psd","psd","application/octet-stream","a".repeat(64),1000);
        when(storage.upload(14L,multipart)).thenReturn(stored);
        when(support.insert(anyString(),any(Object[].class))).thenReturn(25L);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(25L,service.upload(auth,14,"SOURCE",null,multipart).id());
            org.springframework.transaction.support.TransactionSynchronizationManager
                .getSynchronizations().get(0).afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
            verify(storage,never()).deleteUnregistered(anyLong(),anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void preservesR2FileWhenTransactionOutcomeIsUnknown() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L)))
            .thenReturn(List.of(14L));
        var stored=new CommerceArtworkFileStorage.StoredFile(
            "commerce/artworks/14/00000000-0000-4000-8000-000000000000.psd",
            "source.psd","psd","application/octet-stream","a".repeat(64),1000);
        when(storage.upload(14L,multipart)).thenReturn(stored);
        when(support.insert(anyString(),any(Object[].class))).thenReturn(25L);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(auth,14,"SOURCE",null,multipart);
            org.springframework.transaction.support.TransactionSynchronizationManager
                .getSynchronizations().get(0).afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);
            // Unknown is not proof of rollback; avoid deleting an object
            // which may have a committed record in the SQL database.
            verify(storage,never()).deleteUnregistered(anyLong(),anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deniesDownloadBeforeSelectingAnyObject() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->service.download(auth,14L,55L));
        verifyNoInteractions(jdbc,storage);
    }

    @Test
    void archiveNeverDeletesR2ObjectOrLosesAudit() {
        when(jdbc.queryForList(contains("SELECT status FROM commerce_artwork_files"),
            eq(14L),eq(55L))).thenReturn(List.of(Map.of("status","ACTIVE")));
        service.archive(auth,14L,55L);
        verify(jdbc).update(contains("UPDATE commerce_artwork_files"),
            eq(7),eq(14L),eq(55L));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(7),eq(55L),eq("ARCHIVED"));
        verifyNoInteractions(storage);
    }
}
