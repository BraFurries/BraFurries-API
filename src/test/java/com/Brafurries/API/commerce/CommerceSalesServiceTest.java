package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceFinanceDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import java.math.BigDecimal;
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
class CommerceSalesServiceTest {
    @Mock CommerceAuthorizationService authorization;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    private CommerceSalesService service;

    @BeforeEach
    void setup() {
        service=new CommerceSalesService(authorization,support,jdbc);
        User actor=new User();actor.setId(2);
        lenient().when(authorization.require(eq(auth),any())).thenReturn(
            new CommerceAuthorizationService.Actor(
                actor,new CommerceAuthorizationService.Access(true,true,Set.of())));
    }

    private CreateSale sale(String key,String channel,List<SaleLine> items) {
        return new CreateSale(key,channel,"ORDER-1",null,
            LocalDate.of(2026,10,15),null,items);
    }

    @Test
    void rejectsSaleWithInvalidChannelBeforeReadingOrWritingMoney() {
        CreateSale request=sale("e81e8ef8-7950-4a27-a350-9613ee2635c2","DISCORD_COINS",
            List.of(new SaleLine(1L,1,new BigDecimal("60.00"))));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.createSale(auth,request)).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void rejectsDuplicateBatchLinesToAvoidDoubleInventoryAndRoyalties() {
        CreateSale request=sale("e81e8ef8-7950-4a27-a350-9613ee2635c2","EVENT",
            List.of(new SaleLine(1L,1,BigDecimal.TEN),
                    new SaleLine(1L,1,BigDecimal.TEN)));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.createSale(auth,request)).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void reusingAnIdempotencyKeyWithDifferentDataIsRejected() {
        String key="e81e8ef8-7950-4a27-a350-9613ee2635c2";
        when(jdbc.queryForList(contains("FROM commerce_sales WHERE request_key"),eq(key)))
            .thenReturn(List.of(Map.of("id",99L,"request_sha256","wrong_hash")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.createSale(auth,sale(key,"EVENT",
                List.of(new SaleLine(1L,2,new BigDecimal("60.00")))))).getStatusCode());
        verify(support,never()).insert(anyString(),any(Object[].class));
    }

    @Test
    void rejectsOversizedPricesWithoutAutomaticRounding() {
        CreateSale request=sale("e81e8ef8-7950-4a27-a350-9613ee2635c2","EVENT",
            List.of(new SaleLine(1L,1,new BigDecimal("22.999"))));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.createSale(auth,request)).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void cancellingAlreadyCancelledSaleDoesNotCreateAnotherReversal() {
        when(jdbc.queryForList(contains("FROM commerce_sales WHERE id=? FOR UPDATE"),eq(23L)))
            .thenReturn(List.of(Map.of("id",23L,"status","CANCELLED")));
        service.cancelSale(auth,23L,"Duplicated request");
        verify(jdbc).queryForList(contains("FROM commerce_sales WHERE id=? FOR UPDATE"),eq(23L));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(support);
    }

    @Test
    void unauthorizedSaleManagerIsRejectedBeforeAnyBusinessQuery() {
        when(authorization.require(auth,CommercePermission.SALES_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.createSale(auth,sale(
                "e81e8ef8-7950-4a27-a350-9613ee2635c2","EVENT",
                List.of(new SaleLine(1L,1,BigDecimal.TEN))))).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }
}
