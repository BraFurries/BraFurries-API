package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceCashMovementDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
class CommerceCashMovementServiceTest {
    @Mock CommerceAuthorizationService authorization;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    private CommerceCashMovementService service;

    private static String key() {
        return UUID.nameUUIDFromBytes("cash-fixture".getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static CreateMovement posted(String type,String nature,String contributor) {
        return new CreateMovement(
            key(),type,new BigDecimal("150.00"),LocalDate.of(2026,10,23),
            "MATERIAIS","Registro de teste","Contribuinte de exemplo",null,
            contributor,nature,"PIX","REF-1",null);
    }

    @BeforeEach
    void setup() {
        service=new CommerceCashMovementService(authorization,support,jdbc);
        User owner=new User();owner.setId(2);
        lenient().when(authorization.require(eq(auth),any())).thenReturn(
            new CommerceAuthorizationService.Actor(owner,
                new CommerceAuthorizationService.Access(true,true,Set.of())));
    }

    @Test
    void noOneCanPostOrReadCashMovementsWithOnlySalesPermission() {
        when(authorization.require(auth,CommercePermission.CASHFLOW_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                service.create(auth,posted("EXPENSE",null,null))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                service.reverse(auth,9L,"Registrado por engano")).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void financeViewIsRequiredInAdditionToCashflowManagement() {
        when(authorization.require(auth,CommercePermission.FINANCE_VIEW))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                service.create(auth,posted("EXPENSE",null,null))).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void contributionMustSayWhetherItWillBeRepaid() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.create(auth,posted("CONTRIBUTION",null,"STAFF"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.create(auth,posted("EXPENSE","REPAYABLE","STAFF"))).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test
    void writesAProperlyCategorizedExpenseAndAuditsActor() {
        when(support.insert(contains("INSERT INTO commerce_cash_movements"),
            any(Object[].class))).thenReturn(45L);
        Created created=service.create(auth,posted("EXPENSE",null,null));
        assertEquals(45L,created.id());
        verify(authorization).require(auth,CommercePermission.CASHFLOW_MANAGE);
        verify(authorization).require(auth,CommercePermission.FINANCE_VIEW);
        verify(support).audit(2,"CASH_MOVEMENT",45L,"POSTED");
    }

    @Test
    void retriesWithSameRequestKeyReturnSameMovementWithoutDuplicating() {
        when(jdbc.queryForList(contains("WHERE request_key=?"),eq(key())))
            .thenReturn(List.of(Map.of("id",99L,"request_sha256","wrong")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.create(auth,posted("EXPENSE",null,null))).getStatusCode());
        verify(support,never()).insert(anyString(),any(Object[].class));
    }

    @Test
    void reversingAlreadyReversedMovementIsIdempotent() {
        Map<String,Object> original=new HashMap<>();
        original.put("id",91L);
        original.put("event_kind","POSTED");
        when(jdbc.queryForList(contains("FROM commerce_cash_movements WHERE id=? FOR UPDATE"),
            eq(91L))).thenReturn(List.of(original));
        when(jdbc.queryForList(contains("WHERE reversal_of_id=? FOR UPDATE"),
            eq(Long.class),eq(91L))).thenReturn(List.of(92L));

        assertEquals(92L,service.reverse(auth,91L,"Lançamento duplicado").id());
        verify(support,never()).insert(anyString(),any(Object[].class));
    }

    @Test
    void readersNeedFinanceViewForSummary() {
        when(authorization.require(auth,CommercePermission.FINANCE_VIEW))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.summary(auth,2026,10)).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }
}
