package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommerceRoyaltyMathTest {
    @Test
    void grossRoyaltyUsesExactContractRateAndExplicitArtworkAllocation() {
        assertEquals(new BigDecimal("7.81"),
            CommerceRoyaltyMath.gross(new BigDecimal("125.00"),
                new BigDecimal("12.5000"),new BigDecimal("50.0000")));
        assertEquals(new BigDecimal("0.01"),
            CommerceRoyaltyMath.gross(new BigDecimal("0.05"),
                new BigDecimal("20"),new BigDecimal("100")));
    }
    @Test
    void fixedPerUnitMultipliesByQuantityAndThenByAllocation() {
        assertEquals(new BigDecimal("3.75"),
            CommerceRoyaltyMath.fixedUnit(new BigDecimal("2.50"),3,
                new BigDecimal("50.0000")));
        assertEquals(new BigDecimal("0.00"),
            CommerceRoyaltyMath.fixedUnit(BigDecimal.ZERO,2,new BigDecimal("100")));
    }
    @Test
    void invalidShareIsNeverSilentlyClampedOrAssumed() {
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->CommerceRoyaltyMath.gross(
                BigDecimal.TEN,new BigDecimal("15"),null)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->CommerceRoyaltyMath.fixedUnit(
                BigDecimal.ONE,1,new BigDecimal("110"))).getStatusCode());
    }
}
