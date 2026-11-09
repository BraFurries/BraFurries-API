package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceCatalogManagementServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CommerceCatalogManagementService service;
    User actor;

    @BeforeEach void setup() {
        service=new CommerceCatalogManagementService(authorization,jdbc);
        actor=new User();
        actor.setId(2);
    }

    private void grant(PlatformPermission permission) {
        when(authorization.require(auth,permission))
            .thenReturn(new PlatformAuthorizationService.Actor(actor,
                new PlatformAuthorizationService.Access(false,true,Set.of(permission))));
    }

    @Test void managerPermissionIsCheckedBeforeAnyCatalogRead() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.deleteArtwork(auth,7)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void editsToArtistOrFinancialCostAreBlockedAfterContract() {
        grant(PlatformPermission.ARTWORKS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_artworks WHERE id=? FOR UPDATE"),
                               eq(9L))).thenReturn(List.of(Map.of(
            "artistId",15,"creationCost",new BigDecimal("20.00"),"status","ACTIVE")));
        when(jdbc.queryForObject(contains("FROM users WHERE"),eq(Number.class),eq(16)))
            .thenReturn(1);
        when(jdbc.queryForObject(contains("FROM commerce_artwork_contracts"),
                                 eq(Number.class),eq(9L))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.updateArtwork(auth,9,new CreateArtwork(
                "Nova arte","desc",16,new BigDecimal("20.00")))).getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_artworks"),any(Object[].class));
    }

    @Test void permitsTitleCorrectionWhileKeepingFinancialOwnership() {
        grant(PlatformPermission.ARTWORKS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_artworks WHERE id=? FOR UPDATE"),
                               eq(9L))).thenReturn(List.of(Map.of(
            "artistId",15,"creationCost",new BigDecimal("20.00"),"status","ACTIVE")));
        when(jdbc.queryForObject(contains("FROM users WHERE"),eq(Number.class),eq(15)))
            .thenReturn(1);
        service.updateArtwork(auth,9,new CreateArtwork(
            "Título revisado",null,15,new BigDecimal("20.00")));
        verify(jdbc).update(contains("UPDATE commerce_artworks"),
            eq("Título revisado"),isNull(),eq(15),eq(new BigDecimal("20.00")),eq(9L));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTWORK"),eq(9L),eq("UPDATED"));
    }

    @Test void historicalArtworkCannotBePhysicallyDeleted() {
        grant(PlatformPermission.ARTWORKS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_artworks WHERE id=? FOR UPDATE"),
                               eq(3L))).thenReturn(List.of(Map.of("status","ARCHIVED")));
        when(jdbc.update(contains("DELETE FROM commerce_artworks"),eq(3L)))
            .thenThrow(new DataIntegrityViolationException("foreign key"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.deleteArtwork(auth,3)).getStatusCode());
        verify(jdbc,never()).update(contains("INSERT INTO commerce_catalog_audit"),
                                    any(),any(),any(),any());
    }

    @Test void unusedDraftArtworkMayBeDeletedAndAudited() {
        grant(PlatformPermission.ARTWORKS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_artworks WHERE id=? FOR UPDATE"),
                               eq(3L))).thenReturn(List.of(Map.of("status","DRAFT")));
        when(jdbc.update(contains("DELETE FROM commerce_artworks"),eq(3L)))
            .thenReturn(1);
        service.deleteArtwork(auth,3);
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTWORK"),eq(3L),eq("DELETED"));
    }

    @Test void cannotArchiveArtworkWithActiveSignedContract() {
        grant(PlatformPermission.ARTWORKS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_artworks WHERE id=? FOR UPDATE"),
                               eq(3L))).thenReturn(List.of(Map.of("status","ACTIVE")));
        when(jdbc.queryForObject(contains("status='ACTIVE'"),eq(Number.class),eq(3L)))
            .thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.setArtworkArchived(auth,3,true)).getStatusCode());
        verify(jdbc,never()).update(contains("SET status='ARCHIVED'"),eq(3L));
    }

    @Test void unlinkNeedsBothProductAndContractGrant() {
        grant(PlatformPermission.PRODUCTS_MANAGE);
        when(authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.unlinkArtwork(auth,12,13)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void productSkuCannotChangeAfterProductionHistory() {
        grant(PlatformPermission.PRODUCTS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_products"),
                               eq(5L))).thenReturn(List.of(Map.of("sku","ORIGINAL")));
        when(jdbc.queryForObject(contains("FROM commerce_production_batches"),
                                 eq(Number.class),eq(5L))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.updateProduct(auth,5,new CreateProduct("NEW","Produto",null,
                new BigDecimal("12.00"),new BigDecimal("15.00"),null))).getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_products"),any(Object[].class));
    }

    @Test void onlyOwnerOrExplicitProductManagerCanArchiveProduct() {
        when(authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.setProductActive(auth,25L,false)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void changingSupplierContactsKeepsFinancialHistoryUntouched() {
        grant(PlatformPermission.SUPPLIERS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_suppliers"),
                               eq(14L))).thenReturn(List.of(Map.of("id",14L)));
        service.updateSupplier(auth,14L,new CreateSupplier(
            "Oficina revisada","Contato","contato@example.org","11999999999","Novo telefone"));
        verify(jdbc).update(contains("UPDATE commerce_suppliers"),
            eq("Oficina revisada"),eq("Contato"),eq("contato@example.org"),
            eq("11999999999"),eq("Novo telefone"),eq(14L));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("SUPPLIER"),eq(14L),eq("UPDATED"));
        verify(jdbc,never()).update(contains("commerce_sales"),any(Object[].class));
    }

    @Test void archiveUnusedArtworkPreservesRecordAndWritesAudit() {
        grant(PlatformPermission.ARTWORKS_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_artworks WHERE id=? FOR UPDATE"),
                               eq(3L))).thenReturn(List.of(Map.of("status","DRAFT")));
        when(jdbc.queryForObject(contains("status='ACTIVE'"),eq(Number.class),eq(3L)))
            .thenReturn(0);
        service.setArtworkArchived(auth,3,true);
        verify(jdbc).update(contains("SET status='ARCHIVED'"),eq(3L));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTWORK"),eq(3L),eq("ARCHIVED"));
        verify(jdbc,never()).update(contains("DELETE FROM commerce_artworks"),eq(3L));
    }
}
