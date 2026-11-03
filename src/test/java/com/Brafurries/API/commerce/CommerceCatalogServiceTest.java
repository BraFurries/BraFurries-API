package com.Brafurries.API.commerce;

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
    @Mock CommerceAuthorizationService authorization;
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
            new CommerceAuthorizationService.Actor(owner,
                new CommerceAuthorizationService.Access(true,true,java.util.Set.of()))
        );
    }

    @Test
    void blocksArtistListWithoutPrivateArtworkPermission() {
        when(authorization.require(auth,CommercePermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.artworks(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void allContractListsRequireExplicitContractsPermissionAndHideDocumentKeys() {
        when(jdbc.queryForObject(contains("commerce_artworks"),eq(Integer.class),eq(25L)))
            .thenReturn(1);
        catalog.contracts(auth,25L);
        verify(authorization).require(auth,CommercePermission.CONTRACTS_MANAGE);
        verify(jdbc).queryForList(argThat(sql ->
            sql.contains("commerce_artwork_contracts") &&
            !sql.contains("SELECT *") && !sql.contains("signed_storage_key")),
            eq(25L));
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
        when(authorization.require(auth,CommercePermission.SUPPLIERS_MANAGE))
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
        when(jdbc.queryForList(contains("royalty_allocation_percent FROM"),
            any(org.springframework.jdbc.core.RowMapper.class),eq(12L),eq(11L)))
            .thenReturn(List.of());
        when(jdbc.queryForObject(contains("SUM(royalty_allocation_percent)"),
            eq(BigDecimal.class),eq(12L),eq(11L))).thenReturn(new BigDecimal("80.0000"));

        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->catalog.linkArtwork(auth,12L,11L,new LinkArtwork(new BigDecimal("30.0000"))))
            .getStatusCode());
        verify(authorization).require(auth,CommercePermission.PRODUCTS_MANAGE);
        verify(authorization).require(auth,CommercePermission.CONTRACTS_MANAGE);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void refusesSecondSignedActiveAgreementBeforeAnyUpload() {
        when(jdbc.queryForList(contains("SELECT id FROM commerce_artworks"),
            eq(Long.class),eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("commerce_artwork_contracts"),
            eq(12L))).thenReturn(List.of(Map.of("id",21L)));

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->catalog.signContract(auth,12L,3L,LocalDate.of(2026,11,3),
                null,BigDecimal.ZERO,pdf)).getStatusCode());
        verifyNoInteractions(documents);
    }

    @Test
    void doesNotDownloadPrivateContractWithoutExplicitPermission() {
        when(authorization.require(auth,CommercePermission.CONTRACTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->catalog.downloadContract(auth,10L,11L)).getStatusCode());
        verifyNoInteractions(documents);
        verifyNoInteractions(jdbc);
    }
}
