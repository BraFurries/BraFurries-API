package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
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
import org.springframework.web.server.ResponseStatusException;

import static com.Brafurries.API.commerce.CommerceOrderDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommerceOrderServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    private CommerceOrderService orders;
    private static final String KEY="11111111-1111-4111-8111-111111111111";

    @BeforeEach
    void setup() {
        orders=new CommerceOrderService(authorization,support,jdbc);
        var user=new User();user.setId(7);
        lenient().when(authorization.require(eq(auth),eq(PlatformPermission.SALES_MANAGE)))
            .thenReturn(new PlatformAuthorizationService.Actor(user,
                new PlatformAuthorizationService.Access(true,true,java.util.Set.of())));
    }

    @Test
    void salesManagerCanSeeProductChoicesWithoutSupplierCost() {
        orders.productChoices(auth);
        verify(authorization).require(auth,PlatformPermission.SALES_MANAGE);
        verify(jdbc).queryForList(argThat(sql->sql.contains("commerce_products")
            && sql.contains("suggested_price")
            && !sql.contains("estimated_unit_cost")
            && !sql.contains("supplier")));
    }

    @Test
    void cancellationRequiresReceiptReversalFirst() {
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE id="),eq(25L)))
            .thenReturn(List.of(Map.of("id",25L,"status","OPEN",
                "totalAmount",new BigDecimal("100.00"))));
        when(jdbc.queryForObject(contains("COALESCE(SUM(amount),0)"),
            eq(BigDecimal.class),eq(25L))).thenReturn(new BigDecimal("10.00"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->orders.transition(auth,25L,new Transition("CANCELLED",null,
                "Cliente desistiu"))).getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_orders"),any(Object[].class));
    }

    @Test
    void auditingFailureCannotBeMistakenForSuccessfulOrderReplay() {
        var data=new Create(KEY,"Kéti","DIRECT",null,LocalDate.of(2026,11,3),
            null,null,List.of(new Line(null,"Camiseta","GG",2,new BigDecimal("55.00"))));
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE request_key"),
            eq(KEY))).thenReturn(List.of());
        when(support.insert(contains("INSERT INTO commerce_orders"),any(Object[].class)))
            .thenReturn(25L);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("audit failed"))
            .when(support).audit(7,"ORDER",25L,"CREATED");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->orders.create(auth,data));
        verify(jdbc,times(1)).queryForList(
            contains("FROM commerce_orders WHERE request_key"),eq(KEY));
    }

    @Test
    void concurrentDuplicateUsesLockingReadToAvoidRepeatableReadSnapshot() {
        var data=new Create(KEY,"Kéti","DIRECT",null,LocalDate.of(2026,11,3),
            null,null,List.of(new Line(null,"Poster",null,1,new BigDecimal("45.00"))));
        String hash=CommerceFinanceSupport.fingerprint(java.util.Arrays.asList(
            "Kéti","DIRECT","2026-11-03",null,null,null,null,"Poster",null,"1","45.00"));
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE request_key"),
            eq(KEY))).thenReturn(List.of(),List.of(Map.of("id",50L,"requestHash",hash)));
        when(support.insert(contains("INSERT INTO commerce_orders"),any(Object[].class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        assertEquals(50L,orders.create(auth,data).id());
        verify(jdbc).queryForList(argThat(sql->sql.contains("FROM commerce_orders WHERE request_key")
            && sql.contains("FOR UPDATE")),eq(KEY));
        verify(support,never()).audit(anyInt(),anyString(),anyLong(),anyString());
    }

    @Test
    void retriesPreviouslyCreatedOrderAfterItsCatalogProductIsRetired() {
        var data=new Create(KEY,"Kéti","DIRECT",null,LocalDate.of(2026,11,3),
            null,null,List.of(new Line(30L,"Camiseta","GG",1,new BigDecimal("55.00"))));
        String hash=CommerceFinanceSupport.fingerprint(java.util.Arrays.asList(
            "Kéti","DIRECT","2026-11-03",null,null,null,"30","Camiseta","GG","1","55.00"));
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE request_key"),
            eq(KEY))).thenReturn(List.of(Map.of("id",50L,"requestHash",hash)));
        assertEquals(50L,orders.create(auth,data).id());
        verify(jdbc,never()).queryForList(contains("commerce_products"),eq(Long.class),anyLong());
        verifyNoInteractions(support);
    }

    @Test
    void forbidsUnauthorizedOrderReadsBeforeAnySql() {
        when(authorization.require(auth,PlatformPermission.SALES_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->orders.list(auth)).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void allowsCustomItemWithoutCatalogProductAndDoesNotTouchSalesLedger() {
        var data=new Create(KEY,"Kéti","DIRECT",null,LocalDate.of(2026,11,3),
            LocalDate.of(2026,11,15),null,
            List.of(new Line(null,"Camiseta","GG",2,new BigDecimal("55.00"))));
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE request_key"),
            eq(KEY))).thenReturn(List.of());
        when(support.insert(contains("INSERT INTO commerce_orders"),
            any(Object[].class))).thenReturn(25L);

        assertEquals(25L,orders.create(auth,data).id());
        verify(jdbc).update(contains("INSERT INTO commerce_order_items"),
            eq(25L),isNull(),eq("Camiseta"),eq("GG"),eq(2),
            eq(new BigDecimal("55.00")),eq(new BigDecimal("110.00")));
        verify(support).audit(7,"ORDER",25L,"CREATED");
        verify(jdbc,never()).update(contains("commerce_sales"),any(Object[].class));
        verify(jdbc,never()).update(contains("commerce_royalty_accruals"),any(Object[].class));
    }

    @Test
    void rejectsInvalidQuantityOrFractionalCentsBeforeWriting() {
        var input=new Create(KEY,"Kéti","DIRECT",null,LocalDate.of(2026,11,3),
            null,null,List.of(new Line(null,"Camiseta","GG",2,
                new BigDecimal("55.005"))));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->orders.create(auth,input)).getStatusCode());
        verifyNoInteractions(support);
    }

    @Test
    void disallowsInvalidDeliveryTransition() {
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE id="),
            eq(25L))).thenReturn(List.of(Map.of(
            "id",25L,"status","OPEN","totalAmount",new BigDecimal("110.00"))));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->orders.transition(auth,25L,
                new Transition("DELIVERED",LocalDate.of(2026,11,3),null)))
            .getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_orders"),any(Object[].class));
        verifyNoInteractions(support);
    }

    @Test
    void preventsReceivingMoreThanOrderAmountEvenUnderRetries() {
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE id="),eq(25L)))
            .thenReturn(List.of(Map.of("id",25L,"status","OPEN",
                "totalAmount",new BigDecimal("100.00"))));
        when(jdbc.queryForList(contains("FROM commerce_order_payment_events WHERE request_key"),
            eq(KEY))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("COALESCE(SUM(amount),0)"),
            eq(BigDecimal.class),eq(25L))).thenReturn(new BigDecimal("90.00"));
        var payment=new Receive(KEY,new BigDecimal("20.00"),LocalDate.of(2026,11,3),
            "PIX",null,null);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->orders.receive(auth,25L,payment)).getStatusCode());
        verifyNoInteractions(support);
    }

    @Test
    void refusesReversingReceiptFromAnotherOrder() {
        when(jdbc.queryForList(contains("FROM commerce_orders WHERE id="),eq(25L)))
            .thenReturn(List.of(Map.of("id",25L,"status","OPEN",
                "totalAmount",new BigDecimal("100.00"))));
        when(jdbc.queryForList(contains("FROM commerce_order_payment_events"),
            eq(88L),eq(25L))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->orders.reverse(auth,25L,88L,
                new Reverse(KEY,LocalDate.of(2026,11,3),"Duplicado"))).getStatusCode());
        verifyNoInteractions(support);
    }
}
