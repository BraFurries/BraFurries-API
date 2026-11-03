package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommerceProcessingFeeTest {
    @Test
    void calculatesCardPercentAndFixedFeeWithCentPrecision() {
        assertEquals(new BigDecimal("4.50"),
            CommerceProcessingFee.calculate(new BigDecimal("100.00"),
                new BigDecimal("3.5000"),new BigDecimal("1.00")));
        assertEquals(new BigDecimal("95.50"),
            new BigDecimal("100.00").subtract(CommerceProcessingFee.calculate(
                new BigDecimal("100.00"),new BigDecimal("3.5000"),BigDecimal.ONE)));
        assertEquals(new BigDecimal("0.01"),
            CommerceProcessingFee.calculate(new BigDecimal("0.10"),
                new BigDecimal("5"),BigDecimal.ZERO));
    }
    @Test
    void ratesAndFeesCannotCreateNegativeReceipts() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                CommerceProcessingFee.calculate(new BigDecimal("100.00"),
                    new BigDecimal("101"),BigDecimal.ZERO)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                CommerceProcessingFee.calculate(new BigDecimal("10.00"),
                    new BigDecimal("20"),new BigDecimal("9.00"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                CommerceProcessingFee.calculate(new BigDecimal("10.00"),
                    new BigDecimal("0.00001"),BigDecimal.ZERO)).getStatusCode());
    }
    @Test
    void noFeesAndFeeOnlyOnTotalSalePreserveGrossBasis() {
        assertEquals(new BigDecimal("0.00"),
            CommerceProcessingFee.calculate(new BigDecimal("200.00"),
                BigDecimal.ZERO,BigDecimal.ZERO));
        assertEquals(new BigDecimal("7.00"),
            CommerceProcessingFee.calculate(new BigDecimal("200.00"),
                new BigDecimal("2.5"),new BigDecimal("2.00")));
    }
}
