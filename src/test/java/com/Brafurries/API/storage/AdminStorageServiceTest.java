package com.Brafurries.API.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

@ExtendWith(MockitoExtension.class)
class AdminStorageServiceTest {
    private static final String CONTRACT=
        "commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf";
    private static final String ARTWORK=
        "commerce/artworks/12/12345678-1234-1234-1234-123456789abc.psd";
    private static final Instant OLD=Instant.parse("2025-01-01T00:00:00Z");
    @Mock PlatformAuthorizationService authorization;
    @Mock JdbcTemplate jdbc;
    @Mock AdminStorageR2Client r2;
    @Mock Authentication auth;
    AdminStorageService service;

    @BeforeEach void init() {
        service=new AdminStorageService(authorization,jdbc,r2);
    }

    private HeadObjectResponse head(Instant timestamp,String etag) {
        return HeadObjectResponse.builder().lastModified(timestamp)
            .contentLength(123L).contentType("application/pdf").eTag(etag).build();
    }

    @Test void noStorageOperationIsVisibleToNonOwners() {
        when(authorization.requireOwner(auth))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.list(auth,AdminStorageService.Scope.PRIVATE,"commerce/contracts/",
                null,50)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.inspect(auth,AdminStorageService.Scope.PUBLIC,CONTRACT)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.deleteOrphan(auth,
                new AdminStorageService.DeleteRequest(
                    AdminStorageService.Scope.PRIVATE,CONTRACT,"\"abc\""))).getStatusCode());
        verifyNoInteractions(r2,jdbc);
    }

    @Test void listIsBoundedAndKeepsOpaqueCursorWithoutBucketNames() {
        when(r2.list(AdminStorageService.Scope.PRIVATE,"commerce/",null,50))
            .thenReturn(new AdminStorageR2Client.Page(
                List.of(S3Object.builder().key(CONTRACT).size(123L)
                    .eTag("\"abc\"").lastModified(OLD).build()),"opaque-token",true));
        var page=service.list(auth,AdminStorageService.Scope.PRIVATE,"commerce/",null,50);
        assertEquals("opaque-token",page.nextCursor());
        assertTrue(page.truncated());
        assertEquals(123L,page.pageBytes());
        assertEquals(1,page.objects().size());
        assertEquals(AdminStorageService.ReferenceStatus.ORPHAN,
            page.objects().get(0).referenceStatus());
        assertTrue(page.objects().get(0).deletable());
        assertEquals(AdminStorageService.Scope.PRIVATE,page.bucket());
        verify(jdbc,times(3)).queryForList(anyString(),eq(String.class),any(Object[].class));
    }

    @Test void unknownPrivateAndAllPublicObjectsAreNeverDeletable() {
        when(r2.head(AdminStorageService.Scope.PRIVATE,"administrative/documents/a.pdf"))
            .thenReturn(head(OLD,"\"abc\""));
        var unknown=service.inspect(auth,AdminStorageService.Scope.PRIVATE,
            "administrative/documents/a.pdf");
        assertEquals(AdminStorageService.ReferenceStatus.UNVERIFIED,unknown.referenceStatus());
        assertFalse(unknown.deletable());

        when(r2.head(AdminStorageService.Scope.PUBLIC,"themes/7/logo.webp"))
            .thenReturn(head(OLD,"\"abc\""));
        var publicFile=service.inspect(auth,AdminStorageService.Scope.PUBLIC,
            "themes/7/logo.webp");
        assertFalse(publicFile.deletable());
        verifyNoInteractions(jdbc);
    }

    @Test void linkedDocumentsAndFilesAreProtectedEvenWhenOld() {
        when(r2.head(AdminStorageService.Scope.PRIVATE,CONTRACT))
            .thenReturn(head(OLD,"\"abc\""));
        when(jdbc.queryForList(contains("commerce_artwork_contracts"),
            eq(String.class),any(Object[].class))).thenReturn(List.of(CONTRACT));
        var item=service.inspect(auth,AdminStorageService.Scope.PRIVATE,CONTRACT);
        assertEquals(AdminStorageService.ReferenceStatus.LINKED,item.referenceStatus());
        assertFalse(item.deletable());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.deleteOrphan(auth,
                new AdminStorageService.DeleteRequest(
                    AdminStorageService.Scope.PRIVATE,CONTRACT,"\"abc\""))).getStatusCode());
        verify(r2,never()).deleteVerifiedOrphan(any(),anyString());
    }

    @Test void aRecentlyUploadedButUnreferencedArtworkIsNotSafeToDelete() {
        when(r2.head(AdminStorageService.Scope.PRIVATE,ARTWORK))
            .thenReturn(head(Instant.now(), "\"abc\""));
        var file=service.inspect(auth,AdminStorageService.Scope.PRIVATE,ARTWORK);
        assertEquals(AdminStorageService.ReferenceStatus.RECENT,file.referenceStatus());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.deleteOrphan(auth,
                new AdminStorageService.DeleteRequest(
                    AdminStorageService.Scope.PRIVATE,ARTWORK,"\"abc\""))).getStatusCode());
        verify(r2,never()).deleteVerifiedOrphan(any(),anyString());
    }

    @Test void staleEtagPreventsAccidentalObjectReplacementDeletion() {
        when(r2.head(AdminStorageService.Scope.PRIVATE,CONTRACT))
            .thenReturn(head(OLD,"\"new\""));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.deleteOrphan(auth,
                new AdminStorageService.DeleteRequest(
                    AdminStorageService.Scope.PRIVATE,CONTRACT,"\"old\""))).getStatusCode());
        verifyNoInteractions(jdbc);
        verify(r2,never()).deleteVerifiedOrphan(any(),anyString());
    }

    @Test void deletesOnlyExplicitlyInspectedOldUnreferencedPrivateObject() {
        when(r2.head(AdminStorageService.Scope.PRIVATE,CONTRACT))
            .thenReturn(head(OLD,"\"abc\""));
        service.deleteOrphan(auth,new AdminStorageService.DeleteRequest(
            AdminStorageService.Scope.PRIVATE,CONTRACT,"\"abc\""));
        verify(r2).deleteVerifiedOrphan(AdminStorageService.Scope.PRIVATE,CONTRACT);
        verify(jdbc,times(3)).queryForList(anyString(),eq(String.class),any(Object[].class));
    }

    @Test void rejectsPublicDeletionAndUnboundedOrInvalidSearches() {
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.deleteOrphan(auth,new AdminStorageService.DeleteRequest(
                AdminStorageService.Scope.PUBLIC,CONTRACT,"\"abc\""))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            () -> service.list(auth,AdminStorageService.Scope.PRIVATE,"",null,101)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            () -> service.list(auth,AdminStorageService.Scope.PRIVATE,"../",null,50)).getStatusCode());
        verifyNoInteractions(r2,jdbc);
    }
}
