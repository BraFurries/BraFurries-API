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
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceSettlementServiceTest {
    @Mock CommerceAuthorizationService authorization;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    private CommerceSettlementService service;
    private static final String PAYMENT_KEY="d1d2d3d4-1234-4678-8abc-1234567890ab";

    @BeforeEach
    void setup() {
        service=new CommerceSettlementService(authorization,support,jdbc);
        User owner=new User();owner.setId(2);
        lenient().when(authorization.require(eq(auth),any())).thenReturn(
            new CommerceAuthorizationService.Actor(owner,
                new CommerceAuthorizationService.Access(true,true,Set.of()))
        );
    }

    @Test
    void onlyPastMonthsCanBeClosedAndNoFutureRoyaltiesCanBeFrozen() {
        LocalDate now=CommerceFinanceSupport.today();
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.createDraft(auth,
                new CreateStatement(11,now.getYear(),now.getMonthValue())))
            .getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void createDraftFreezesAccrualsAsDistinctImmutableItems() {
        when(jdbc.queryForList(contains("SELECT id,status FROM commerce_settlements"),
            eq(11),eq(2025),eq(12))).thenReturn(List.of());
        when(jdbc.queryForList(contains("SELECT id,amount FROM commerce_royalty_accruals"),
            eq(11),eq(2025),eq(12))).thenReturn(List.of(
                Map.of("id",1L,"amount",new BigDecimal("12.00")),
                Map.of("id",2L,"amount",new BigDecimal("-2.00"))
            ));
        when(support.insert(contains("INSERT INTO commerce_settlements"),
            eq(11),eq(2025),eq(12),eq(new BigDecimal("10.00")),eq(2)))
            .thenReturn(90L);

        Created created=service.createDraft(auth,new CreateStatement(11,2025,12));
        assertEquals(90L,created.id());
        verify(support).lockPeriod(11,2025,12);
        verify(jdbc).update(contains("INSERT INTO commerce_settlement_items"),
            eq(90L),eq(1L),eq(new BigDecimal("12.00")));
        verify(jdbc).update(contains("INSERT INTO commerce_settlement_items"),
            eq(90L),eq(2L),eq(new BigDecimal("-2.00")));
        verify(support).audit(2,"SETTLEMENT",90L,"DRAFT_CREATED");
    }

    @Test
    void creatingDraftWithoutAccrualsIsBlocked() {
        when(jdbc.queryForList(contains("SELECT id,status FROM commerce_settlements"),
            eq(11),eq(2025),eq(12))).thenReturn(List.of());
        when(jdbc.queryForList(contains("SELECT id,amount FROM commerce_royalty_accruals"),
            eq(11),eq(2025),eq(12))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.createDraft(auth,new CreateStatement(11,2025,12)))
            .getStatusCode());
        verify(support,never()).insert(anyString(),any(Object[].class));
    }

    @Test
    void managerCannotCreateStatementWithoutFinanceView() {
        when(authorization.require(auth,CommercePermission.FINANCE_VIEW))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.createDraft(auth,new CreateStatement(11,2025,12)))
            .getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void paymentNeedsRealAmountAndExternalReference() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.recordPayment(auth,90L,
                new RecordPayment(PAYMENT_KEY,BigDecimal.ZERO,
                    LocalDate.of(2025,12,1),"REFERENCE",null)))
            .getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void samePaymentRequestKeyCannotRepresentDifferentTransfer() {
        when(jdbc.queryForList(contains("FROM commerce_payments WHERE request_key"),
            eq(PAYMENT_KEY))).thenReturn(List.of(Map.of(
                "id",12L,"settlement_id",90L,"amount",new BigDecimal("25.00"),
                "paid_on",java.sql.Date.valueOf("2025-12-04"),
                "external_reference","TX-1","notes","Original"
            )));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.recordPayment(auth,90L,
                new RecordPayment(PAYMENT_KEY,new BigDecimal("30.00"),
                    LocalDate.of(2025,12,4),"TX-1","Original"))).getStatusCode());
        verifyNoInteractions(support);
    }

    @SuppressWarnings("unchecked")
    private void givenStatement(String status,BigDecimal total) {
        when(jdbc.query(contains("FROM commerce_settlements WHERE id=?"),
            any(RowMapper.class),eq(90L))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getLong("id")).thenReturn(90L);
            when(rs.getInt("artist_user_id")).thenReturn(12);
            when(rs.getInt("period_year")).thenReturn(2025);
            when(rs.getInt("period_month")).thenReturn(12);
            when(rs.getString("status")).thenReturn(status);
            when(rs.getBigDecimal("total_amount")).thenReturn(total);
            return List.of(mapper.mapRow(rs,0));
        });
    }

    @Test
    void paymentCannotExceedOutstandingStatementBalance() {
        givenStatement("APPROVED",new BigDecimal("100.00"));
        when(jdbc.queryForObject(contains("FROM commerce_payments"),
            eq(BigDecimal.class),eq(90L))).thenReturn(new BigDecimal("75.00"));

        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                () -> service.recordPayment(auth,90L,new RecordPayment(
                    PAYMENT_KEY,new BigDecimal("30.00"),LocalDate.of(2025,12,5),
                    "PIX-OVER",null))).getStatusCode());
        verify(support,never()).insert(anyString(),any(Object[].class));
    }

    @Test
    void paymentCannotBeRecordedAgainstUnapprovedDraft() {
        givenStatement("DRAFT",new BigDecimal("100.00"));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                () -> service.recordPayment(auth,90L,new RecordPayment(
                    PAYMENT_KEY,new BigDecimal("30.00"),LocalDate.of(2025,12,5),
                    "PIX-UNAPPROVED",null))).getStatusCode());
        verify(support,never()).insert(anyString(),any(Object[].class));
    }

    @Test
    void onlyFinanceViewMayReadStatementsAndSensitivePaymentHistory() {
        when(authorization.require(auth,CommercePermission.FINANCE_VIEW))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.listStatements(auth,2025,12)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.payments(auth,90L)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
