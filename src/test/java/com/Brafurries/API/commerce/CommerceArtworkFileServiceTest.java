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
    void previewRequiresArtworkPermissionBeforeAnyLookup() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.preview(auth,14L,55L)).getStatusCode());
        assertThrows(ResponseStatusException.class,()->service.setPreview(auth,14L,55L,true));
        verifyNoInteractions(jdbc,storage);
    }

    @Test
    void selectingPreviewRejectsFileFromAnotherArtwork() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L))).thenReturn(List.of(14L));
        when(jdbc.queryForList(contains("WHERE artwork_id=? AND id=? FOR UPDATE"),eq(14L),eq(55L)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.setPreview(auth,14L,55L,true)).getStatusCode());
        verifyNoInteractions(storage);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void selectingPreviewRejectsArchivedOrUnsupportedFile() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L))).thenReturn(List.of(14L));
        when(jdbc.queryForList(contains("WHERE artwork_id=? AND id=? FOR UPDATE"),eq(14L),eq(55L)))
            .thenReturn(List.of(Map.of("status","ACTIVE","extension","svg","mediaType","image/svg+xml","sizeBytes",1000L)));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.setPreview(auth,14L,55L,true)).getStatusCode());
        verifyNoInteractions(storage);
    }

    private void stubThumbnailSelection() {
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(14L))).thenReturn(List.of(14L));
        doReturn(List.of(Map.of("status","ACTIVE","extension","png","mediaType","image/png",
            "sizeBytes",6010000L,"storageKey","original","sha256","hash")))
            .when(jdbc).queryForList(contains("WHERE artwork_id=? AND id=? FOR UPDATE"),eq(14L),eq(55L));
        doReturn(List.of(Map.of("id",54L,"previewKey","old")))
            .when(jdbc).queryForList(contains("AND id<>? FOR UPDATE"),eq(14L),eq(55L));
        when(storage.read(14L,"original","hash")).thenReturn(new byte[]{1,2,3});
        when(storage.uploadThumbnail(eq(14L),eq(55L),any(byte[].class)))
            .thenReturn(new CommerceArtworkFileStorage.StoredThumbnail("new","digest",100L,512,400));
    }

    @Test
    void selectingLargePreviewReplacesPreviousOnlyAfterCommit() {
        stubThumbnailSelection();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.setPreview(auth,14L,55L,true);
            verify(jdbc).update(contains("SET is_preview=0"),eq(14L),eq(55L));
            verify(jdbc).update(contains("SET is_preview=1"),eq("new"),eq("digest"),
                eq(100L),eq(512),eq(400),eq(14L),eq(55L));
            verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),eq(7),eq(55L),eq("PREVIEW_SELECTED"));
            verify(storage,never()).deleteThumbnail(anyLong(),anyLong(),anyString());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                .forEach(hook->hook.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(storage).deleteThumbnail(14L,54L,"old");
            verify(storage,never()).deleteThumbnail(14L,55L,"new");
            verify(storage,never()).deleteUnregistered(anyLong(),anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDeletesOnlyNewThumbnail() {
        stubThumbnailSelection();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.setPreview(auth,14L,55L,true);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                .forEach(hook->hook.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(storage).deleteThumbnail(14L,55L,"new");
            verify(storage,never()).deleteThumbnail(14L,54L,"old");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void selectedPreviewUsesOnlyDerivativeAndRejectsUnselectedFile() {
        when(jdbc.queryForList(contains("AND is_preview=1"),eq(14L),eq(55L)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.preview(auth,14L,55L)).getStatusCode());
        verifyNoInteractions(storage);
        when(jdbc.queryForList(contains("AND is_preview=1"),eq(14L),eq(55L)))
            .thenReturn(List.of(Map.of("mediaType","image/png","previewKey","thumb","previewHash","hash")));
        when(storage.readThumbnail(14L,55L,"thumb","hash")).thenReturn(new byte[]{1,2,3});
        var preview=service.preview(auth,14L,55L);
        assertEquals("image/webp",preview.mediaType());
        assertArrayEquals(new byte[]{1,2,3},preview.bytes());
        verify(storage,never()).read(anyLong(),anyString(),anyString());
    }

    @Test
    void legacyPreviewIsConvertedWithoutWritingDuringGet() {
        when(jdbc.queryForList(contains("AND is_preview=1"),eq(14L),eq(55L)))
            .thenReturn(List.of(Map.of("mediaType","image/png","storageKey","original","sha256","hash")));
        when(storage.read(14L,"original","hash")).thenReturn(new byte[]{1});
        when(storage.thumbnail(any(byte[].class)))
            .thenReturn(new CommerceArtworkFileStorage.Thumbnail(new byte[]{2},1,1));
        assertArrayEquals(new byte[]{2},service.preview(auth,14L,55L).bytes());
        verify(storage,never()).uploadThumbnail(anyLong(),anyLong(),any(byte[].class));
    }

    @Test
    void archiveNeverDeletesR2ObjectOrLosesAudit() {
        when(jdbc.queryForList(contains("SELECT status FROM commerce_artwork_files"),
            eq(14L),eq(55L))).thenReturn(List.of(Map.of("status","ACTIVE")));
        service.archive(auth,14L,55L);
        verify(jdbc).update(contains("is_preview=0"),
            eq(7),eq(14L),eq(55L));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(7),eq(55L),eq("ARCHIVED"));
        verifyNoInteractions(storage);
    }
}
