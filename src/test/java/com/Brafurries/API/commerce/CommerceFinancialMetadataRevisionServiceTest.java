package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
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
class CommerceFinancialMetadataRevisionServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CommerceFinancialMetadataRevisionService service;
    @BeforeEach void setup() {
        service=new CommerceFinancialMetadataRevisionService(authorization,support,jdbc);
    }
    void grant(PlatformPermission permission) {
        User actor=new User();actor.setId(7);
        when(authorization.require(auth,permission))
            .thenReturn(new PlatformAuthorizationService.Actor(actor,
                new PlatformAuthorizationService.Access(false,true,Set.of(permission))));
    }
    Map<String,Object> originalPayment() {
        Map<String,Object> row=new HashMap<>();
        row.put("id",3L);row.put("event_kind","PAYMENT");
        row.put("reference","PIX-001");row.put("notes","Original");
        return row;
    }

    @Test void blocksPayoutCorrectionWithoutPaymentPermission() {
        when(authorization.require(auth,PlatformPermission.PAYMENTS_RECORD))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.revisePayment(auth,15L,3L,
                new CommerceFinancialMetadataRevisionService.PaymentRevision(
                    "PIX-002","Observação nova","Ajuste de referência"))).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test void paymentReferenceCorrectionIsAppendOnlyAndAudited() {
        grant(PlatformPermission.PAYMENTS_RECORD);
        when(jdbc.queryForList(contains("FROM commerce_settlements"),
             eq(Long.class),eq(15L))).thenReturn(List.of(15L));
        when(jdbc.queryForList(contains("FROM commerce_payments WHERE id="),
             eq(3L),eq(15L))).thenReturn(List.of(originalPayment()));
        when(jdbc.queryForList(contains("FROM commerce_payment_metadata_revisions"),
             eq(3L))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM commerce_payments p"),
             eq(Long.class),eq(15L),eq(3L),eq("PIX-002"))).thenReturn(List.of());
        service.revisePayment(auth,15L,3L,
            new CommerceFinancialMetadataRevisionService.PaymentRevision(
                "PIX-002","Observação nova","Referência digitada errada"));
        verify(jdbc).update(contains("INSERT INTO commerce_payment_metadata_revisions"),
            eq(3L),eq("PIX-001"),eq("PIX-002"),
            eq("Original"),eq("Observação nova"),
            eq("Referência digitada errada"),eq(7));
        verify(support).audit(7,"PAYMENT",3L,"METADATA_CORRECTED");
        verify(jdbc,never()).update(contains("UPDATE commerce_payments"),any(Object[].class));
        verify(jdbc,never()).update(contains("UPDATE commerce_settlements"),any(Object[].class));
    }

    @Test void paymentCannotDuplicateTheEffectiveReferenceOfAnotherPayment() {
        grant(PlatformPermission.PAYMENTS_RECORD);
        when(jdbc.queryForList(contains("FROM commerce_settlements"),
             eq(Long.class),eq(15L))).thenReturn(List.of(15L));
        when(jdbc.queryForList(contains("FROM commerce_payments WHERE id="),
             eq(3L),eq(15L))).thenReturn(List.of(originalPayment()));
        when(jdbc.queryForList(contains("FROM commerce_payment_metadata_revisions"),
             eq(3L))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM commerce_payments p"),
             eq(Long.class),eq(15L),eq(3L),eq("PIX-002")))
            .thenReturn(List.of(19L));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.revisePayment(auth,15L,3L,
                new CommerceFinancialMetadataRevisionService.PaymentRevision(
                    "PIX-002","Observação nova","Referência já usada"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void cashCorrectionCannotRewriteEconomicValuesAndRequiresFinancePermission() {
        grant(PlatformPermission.CASHFLOW_MANAGE);
        Map<String,Object> row=new HashMap<>();
        row.put("id",11L);row.put("event_kind","POSTED");
        row.put("description","Impressão");row.put("reference","NF-001");row.put("notes",null);
        when(jdbc.queryForList(contains("FROM commerce_cash_movements WHERE id="),
             eq(11L))).thenReturn(List.of(row));
        when(jdbc.queryForList(contains("FROM commerce_cash_metadata_revisions"),
             eq(11L))).thenReturn(List.of());
        service.reviseCash(auth,11L,
            new CommerceFinancialMetadataRevisionService.CashRevision(
                "Impressão cartazes","NF-002",null,"Descrição e referência incorretas"));
        verify(jdbc).update(contains("INSERT INTO commerce_cash_metadata_revisions"),
            eq(11L),eq("Impressão"),eq("Impressão cartazes"),
            eq("NF-001"),eq("NF-002"),isNull(),isNull(),
            eq("Descrição e referência incorretas"),eq(7));
        verify(support).audit(7,"CASH_MOVEMENT",11L,"METADATA_CORRECTED");
        verify(jdbc,never()).update(contains("UPDATE commerce_cash_movements"),any(Object[].class));
    }

    @Test void correctionOfCashReversalIsProhibited() {
        grant(PlatformPermission.CASHFLOW_MANAGE);
        when(jdbc.queryForList(contains("FROM commerce_cash_movements WHERE id="),
             eq(11L))).thenReturn(List.of(Map.of(
                "id",11L,"event_kind","REVERSAL","description","Estorno")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reviseCash(auth,11L,
                new CommerceFinancialMetadataRevisionService.CashRevision(
                    "Outra descrição","NF-002",null,"Estorno não pode ser corrigido")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void payoutCorrectionDoesNotAcceptAReversalEvent() {
        grant(PlatformPermission.PAYMENTS_RECORD);
        when(jdbc.queryForList(contains("FROM commerce_settlements"),
             eq(Long.class),eq(15L))).thenReturn(List.of(15L));
        when(jdbc.queryForList(contains("FROM commerce_payments WHERE id="),
             eq(3L),eq(15L))).thenReturn(List.of(Map.of(
                "id",3L,"event_kind","REVERSAL","reference","REV-01")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.revisePayment(auth,15L,3L,
                new CommerceFinancialMetadataRevisionService.PaymentRevision(
                    "NEW-REF",null,"Tentativa de alterar estorno"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
