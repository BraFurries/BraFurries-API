package com.Brafurries.API.commerce;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure and deterministic royalty calculations, with HALF_UP at the event cent. */
public final class CommerceRoyaltyMath {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND=new BigDecimal("10000");
    private CommerceRoyaltyMath() {}

    public static BigDecimal gross(BigDecimal gross,BigDecimal contractRate,BigDecimal allocation) {
        validate(gross,"Base de royalties");
        validateRate(contractRate,"Percentual contratual");
        validateRate(allocation,"Rateio da obra");
        return gross.multiply(contractRate).multiply(allocation)
            .divide(TEN_THOUSAND,2,RoundingMode.HALF_UP);
    }

    public static BigDecimal fixedUnit(BigDecimal amountPerUnit,int quantity,BigDecimal allocation) {
        validate(amountPerUnit,"Valor contratual por unidade");
        if (quantity<=0) throw CommerceFinanceSupport.invalid("Quantidade inválida para royalties");
        validateRate(allocation,"Rateio da obra");
        return amountPerUnit.multiply(BigDecimal.valueOf(quantity)).multiply(allocation)
            .divide(HUNDRED,2,RoundingMode.HALF_UP);
    }

    private static void validate(BigDecimal amount,String description) {
        if (amount==null || amount.signum()<0) {
            throw CommerceFinanceSupport.conflict(description+" ausente ou inválido");
        }
    }

    private static void validateRate(BigDecimal value,String description) {
        if (value==null || value.signum()<0 || value.compareTo(HUNDRED)>0) {
            throw CommerceFinanceSupport.conflict(description+" deve estar entre 0 e 100");
        }
    }
}
