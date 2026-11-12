package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformAccessService;
import com.Brafurries.API.platform.PlatformPermission;

import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class CommerceCatalogServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommercePrivateDocumentStorage documents;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    @Mock MultipartFile pdf;

    private CommerceCatalogService catalog;

    @BeforeEach
    void setup() {
        catalog = new CommerceCatalogService(authorization,documents,jdbc);
        User owner = new User();
        owner.setId(7);
        lenient().when(authorization.require(eq(auth),any())).thenReturn(
            new PlatformAuthorizationService.Actor(owner,
                new PlatformAuthorizationService.Access(true,true,java.util.Set.of()))
        );
    }

    @Test
    void blocksArtistListWithoutPrivateArtworkPermission() {
        when(authorization.resolve(auth)).thenReturn(
            new PlatformAuthorizationService.Actor(new User(),
                new PlatformAuthorizationService.Access(false,true,java.util.Set.of()))
        );
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.artworks(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void allContractListsRequireExplicitContractsPermissionAndHideDocumentKeys() {
        when(jdbc.queryForObject(contains("commerce_artworks"),eq(Integer.class),eq(25L)))
            .thenReturn(1);
        catalog.contracts(auth,25L);
        verify(authorization).require(auth,PlatformPermission.CONTRACTS_MANAGE);
        verify(jdbc).queryForList(argThat(sql ->
            sql.contains("commerce_artwork_contracts") &&
            !sql.contains("SELECT *") && !sql.contains("signed_storage_key")),
            eq(25L));
    }

    @Test
    void listsExistingContractTemplatesWithNullFullTextUsingSafeMariaDbAlias() {
        // MariaDB reserves FULLTEXT (case-insensitive); an unquoted 'AS fullText'
        // breaks the entire listing after the additive migration.
        Map<String,Object> legacy = new java.util.HashMap<>();
        legacy.put("id",1L);
        legacy.put("name","Modelo legado");
        legacy.put("fullText",null);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(legacy));

        var templates = catalog.templates(auth);

        assertEquals(1,templates.size());
        assertEquals("Modelo legado",templates.get(0).get("name"));
        assertNull(templates.get(0).get("fullText"));
        verify(authorization).require(auth,PlatformPermission.CONTRACTS_MANAGE);
        verify(jdbc).queryForList(argThat(sql ->
            sql.contains("full_text AS `fullText`") &&
            sql.contains("commerce_contract_templates") &&
            !sql.contains("WHERE full_text IS NOT NULL")));
    }

    @Test
    void aRoyaltyTemplateMustSpecifyMatchingRateOrFixedAmount() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->catalog.createTemplate(auth,new CreateTemplate(
                "Royalties","", "ROYALTY","PERCENT_GROSS",null,null))
        ).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->catalog.createTemplate(auth,new CreateTemplate(
                "Royalties","", "ROYALTY","FIXED_UNIT",new BigDecimal("1.25"),null))
        ).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void moneyRejectsFractionalCentsInsteadOfSilentlyRounding() {
        when(jdbc.queryForObject(contains("users"),eq(Integer.class),eq(12)))
            .thenReturn(1);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->catalog.createArtwork(auth,new CreateArtwork(
                "Art","",12,new BigDecimal("150.125")))
        ).getStatusCode());
        verify(jdbc,never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
            any(org.springframework.jdbc.support.KeyHolder.class));
    }

    @Test
    void batchRequiresBothProductAndSupplierAuthorization() {
        when(authorization.require(auth,PlatformPermission.SUPPLIERS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.createProductionBatch(auth,new CreateProductionBatch(
                2L,3L,10,new BigDecimal("3.00"),BigDecimal.ZERO,
                LocalDate.of(2026,11,3),""))
        ).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void productArtworkRoyaltyAllocationRejectsMoreThanFullShare() {
        when(jdbc.queryForList(contains("SELECT id FROM commerce_products"),
            eq(Long.class),eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForObject(contains("commerce_artworks"),eq(Integer.class),eq(11L)))
            .thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT status FROM commerce_artworks"),
            eq(String.class),eq(11L))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(contains("SUM(royalty_allocation_percent)"),
            eq(BigDecimal.class),eq(12L),eq(11L))).thenReturn(new BigDecimal("80.0000"));

        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->catalog.linkArtwork(auth,12L,11L,new LinkArtwork(new BigDecimal("30.0000"))))
            .getStatusCode());
        verify(authorization).require(auth,PlatformPermission.PRODUCTS_MANAGE);
        verify(authorization).require(auth,PlatformPermission.CONTRACTS_MANAGE);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void onlyPlatformOwnerMayDiscardAContractAndItsR2Document() {
        when(authorization.requireOwner(auth))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.discardUnusedTestContract(auth,12L,44L)).getStatusCode());
        verifyNoInteractions(jdbc);
        verifyNoInteractions(documents);
    }

    @Test
    void refusesDiscardWhenRoyaltiesExistWithoutTouchingPrivateR2() {
        when(authorization.requireOwner(auth)).thenReturn(new User());
        when(jdbc.queryForList(contains("SELECT id FROM commerce_artworks"),
            eq(Long.class),eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("signed_storage_key"),
            eq(44L),eq(12L))).thenReturn(List.of(
                Map.of("id",44L,"signed_storage_key","commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf")));
        when(jdbc.queryForObject(contains("commerce_royalty_accruals"),
            eq(Integer.class),eq(44L))).thenReturn(1);

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->catalog.discardUnusedTestContract(auth,12L,44L)).getStatusCode());
        verifyNoInteractions(documents);
        verify(jdbc,never()).update(contains("DELETE FROM commerce_artwork_contracts"),
            any(Object[].class));
    }

    @Test
    void refusesDiscardOfSignedPreparationAndKeepsDocument() {
        when(authorization.requireOwner(auth)).thenReturn(new User());
        when(jdbc.queryForList(contains("SELECT id FROM commerce_artworks"),
            eq(Long.class),eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("signed_storage_key"),
            eq(44L),eq(12L))).thenReturn(List.of(
                Map.of("id",44L,"signed_storage_key","commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf")));
        when(jdbc.queryForObject(contains("commerce_royalty_accruals"),
            eq(Integer.class),eq(44L))).thenReturn(0);
        when(jdbc.queryForObject(contains("commerce_contract_preparations"),
            eq(Integer.class),eq(44L))).thenReturn(1);

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->catalog.discardUnusedTestContract(auth,12L,44L)).getStatusCode());
        verifyNoInteractions(documents);
    }

    @Test
    void unusedContractIsDeletedWithExactPrivateDocumentKeyOnly() {
        String key="commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf";
        when(authorization.requireOwner(auth)).thenReturn(new User());
        when(jdbc.queryForList(contains("SELECT id FROM commerce_artworks"),
            eq(Long.class),eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("signed_storage_key"),
            eq(44L),eq(12L))).thenReturn(List.of(Map.of("id",44L,"signed_storage_key",key)));
        when(jdbc.queryForObject(contains("commerce_royalty_accruals"),
            eq(Integer.class),eq(44L))).thenReturn(0);
        when(jdbc.queryForObject(contains("commerce_contract_preparations"),
            eq(Integer.class),eq(44L))).thenReturn(0);
        when(jdbc.update(contains("DELETE FROM commerce_artwork_contracts"),
            eq(44L),eq(12L))).thenReturn(1);

        catalog.discardUnusedTestContract(auth,12L,44L);

        var order=inOrder(jdbc,documents);
        order.verify(jdbc).update(contains("DELETE FROM commerce_artwork_contracts"),
            eq(44L),eq(12L));
        order.verify(jdbc).update(contains("UPDATE commerce_artworks"),eq(12L));
        order.verify(documents).deleteExistingContract(12L,key);
        verify(authorization).requireOwner(auth);
    }

    @Test
    void r2FailureIsPropagatedSoTransactionCanRollbackDbDeletion() {
        String key="commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf";
        when(authorization.requireOwner(auth)).thenReturn(new User());
        when(jdbc.queryForList(contains("SELECT id FROM commerce_artworks"),
            eq(Long.class),eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("signed_storage_key"),
            eq(44L),eq(12L))).thenReturn(List.of(Map.of("id",44L,"signed_storage_key",key)));
        when(jdbc.queryForObject(contains("commerce_royalty_accruals"),
            eq(Integer.class),eq(44L))).thenReturn(0);
        when(jdbc.queryForObject(contains("commerce_contract_preparations"),
            eq(Integer.class),eq(44L))).thenReturn(0);
        when(jdbc.update(contains("DELETE FROM commerce_artwork_contracts"),
            eq(44L),eq(12L))).thenReturn(1);
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY))
            .when(documents).deleteExistingContract(12L,key);

        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(ResponseStatusException.class,
            ()->catalog.discardUnusedTestContract(auth,12L,44L)).getStatusCode());
    }

    @Test
    void orphanPdfCleanupRequiresOwnerBeforeQueryingDocumentReferences() {
        when(authorization.requireOwner(auth))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.deleteOrphanedContractPdf(auth,
                "commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf"))
            .getStatusCode());
        verifyNoInteractions(jdbc,documents);
    }

    @Test
    void orphanPdfCleanupRejectsKeysOutsideExactPrivateContractPrefix() {
        when(authorization.requireOwner(auth)).thenReturn(new User());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->catalog.deleteOrphanedContractPdf(auth,
                "commerce/artworks/12/12345678-1234-1234-1234-123456789abc.pdf"))
            .getStatusCode());
        verifyNoInteractions(jdbc,documents);
    }

    @Test
    void orphanPdfCleanupPreservesAnyStillReferencedR2Object() {
        String key="commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf";
        when(authorization.requireOwner(auth)).thenReturn(new User());
        when(jdbc.queryForObject(contains("commerce_artwork_contracts"),
            eq(Integer.class),eq(key))).thenReturn(0);
        when(jdbc.queryForObject(contains("commerce_contract_preparations"),
            eq(Integer.class),eq(key))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->catalog.deleteOrphanedContractPdf(auth,key)).getStatusCode());
        verifyNoInteractions(documents);
    }

    @Test
    void orphanPdfCleanupDeletesOnlyExactUnreferencedObject() {
        String key="commerce/contracts/12/12345678-1234-1234-1234-123456789abc.pdf";
        when(authorization.requireOwner(auth)).thenReturn(new User());
        when(jdbc.queryForObject(contains("commerce_artwork_contracts"),
            eq(Integer.class),eq(key))).thenReturn(0);
        when(jdbc.queryForObject(contains("commerce_contract_preparations"),
            eq(Integer.class),eq(key))).thenReturn(0);
        catalog.deleteOrphanedContractPdf(auth,key);
        verify(documents).deleteOrphanedDocument(12L,key);
        verifyNoMoreInteractions(documents);
    }

    @Test
    void doesNotDownloadPrivateContractWithoutExplicitPermission() {
        when(authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.downloadContract(auth,10L,11L)).getStatusCode());
        verifyNoInteractions(documents);
        verifyNoInteractions(jdbc);
    }
}
