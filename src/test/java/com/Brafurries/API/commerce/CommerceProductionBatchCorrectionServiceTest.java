package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceProductionBatchCorrectionServiceTest {
    @Mock PlatformAuthorizationService authz;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CommerceProductionBatchCorrectionService service;
    @BeforeEach void before() {
        service=new CommerceProductionBatchCorrectionService(authz,jdbc);
    }
    void grant() {
        User user=new User();user.setId(7);
        var actor=new PlatformAuthorizationService.Actor(user,
            new PlatformAuthorizationService.Access(false,true,
                Set.of(PlatformPermission.PRODUCTS_MANAGE,PlatformPermission.SUPPLIERS_MANAGE)));
        when(authz.require(auth,PlatformPermission.PRODUCTS_MANAGE)).thenReturn(actor);
    }
    Map<String,Object> row() {
        return Map.of("productId",2L,"supplierId",3L,"quantity",10,
            "unitCost",new BigDecimal("20.00"),"additionalCost",new BigDecimal("5.00"),
            "producedOn",Date.valueOf("2026-11-02"),"reference","L-1");
    }
    CommerceProductionBatchCorrectionService.Correction input(
        int quantity,String reference
    ) {
        return new CommerceProductionBatchCorrectionService.Correction(
            new CreateProductionBatch(2L,3L,quantity,new BigDecimal("20.00"),
                new BigDecimal("5.00"),LocalDate.of(2026,11,2),reference),
            "Correção de digitação");
    }
    void setupBatch() {
        when(jdbc.queryForList(contains("FROM commerce_production_batches WHERE id=? FOR UPDATE"),
            eq(5L))).thenReturn(List.of(row()));
    }

    @Test void requiresProductAndSupplierPermissionsBeforeTouchingDatabase() {
        grant();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(authz).require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.correct(auth,5L,input(12,"L-2"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void blocksCostOrQuantityCorrectionAfterAnyHistoricalSale() {
        grant();setupBatch();
        when(jdbc.queryForList(contains("FROM commerce_sale_items"),
             eq(Long.class),eq(5L))).thenReturn(List.of(100L));
        var ex=assertThrows(ResponseStatusException.class,
            ()->service.correct(auth,5L,input(12,"L-2")));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_production_batches"),
                                    any(Object[].class));
    }

    @Test void nonzeroSoldCounterBlocksEconomicCorrectionEvenIfSaleItemsAreMissing() {
        grant();
        Map<String,Object> batch=new java.util.HashMap<>(row());
        batch.put("soldQuantity",1);
        when(jdbc.queryForList(contains("FROM commerce_production_batches WHERE id=? FOR UPDATE"),
             eq(5L))).thenReturn(List.of(batch));
        when(jdbc.queryForList(contains("FROM commerce_sale_items"),
             eq(Long.class),eq(5L))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.correct(auth,5L,input(12,"L-2"))).getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_production_batches"),
                                   any(Object[].class));
    }

    @Test void allowsReferenceCorrectionAfterSaleWithBeforeAndAfterAudit() {
        grant();setupBatch();
        when(jdbc.queryForList(contains("FROM commerce_sale_items"),
             eq(Long.class),eq(5L))).thenReturn(List.of(100L));
        when(jdbc.queryForObject(contains("SELECT JSON_OBJECT"),
             eq(String.class),eq(5L))).thenReturn("{\"reference\":\"L-1\"}",
                                                   "{\"reference\":\"L-2\"}");
        service.correct(auth,5L,input(10,"L-2"));
        verify(jdbc).update("UPDATE commerce_production_batches SET reference=? WHERE id=?",
                            "L-2",5L);
        verify(jdbc).update(contains("INSERT INTO commerce_production_batch_revisions"),
            eq(5L),eq("{\"reference\":\"L-1\"}"),eq("{\"reference\":\"L-2\"}"),
            eq("Correção de digitação"),eq(7));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(7),eq("PRODUCTION_BATCH"),eq(5L),eq("CORRECTED"));
    }

    @Test void unusedLotCanCorrectQuantityAndTotalAtomically() {
        grant();setupBatch();
        when(jdbc.queryForList(contains("FROM commerce_sale_items"),
             eq(Long.class),eq(5L))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("SELECT JSON_OBJECT"),
             eq(String.class),eq(5L))).thenReturn("{}", "{\"quantity\":12}");
        service.correct(auth,5L,input(12,"L-2"));
        verify(jdbc).update(contains("UPDATE commerce_production_batches"),
            eq(2L),eq(3L),eq(12),eq(new BigDecimal("20.00")),
            eq(new BigDecimal("5.00")),eq(new BigDecimal("245.00")),
            eq(LocalDate.of(2026,11,2)),eq("L-2"),eq(5L));
    }

    @Test void noOpDoesNotWriteAnAuditEntry() {
        grant();setupBatch();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.correct(auth,5L,input(10,"L-1"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
