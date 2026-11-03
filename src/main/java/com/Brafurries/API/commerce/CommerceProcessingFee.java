package com.Brafurries.API.commerce;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Card/payment processing fees are a sale expense, never a reduction in gross royalties. */
public final class CommerceProcessingFee {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private CommerceProcessingFee() {}

    public static BigDecimal calculate(BigDecimal gross,BigDecimal percent,BigDecimal fixed) {
        BigDecimal salesGross=CommerceFinanceSupport.money(gross,"Total bruto");
        BigDecimal flat=CommerceFinanceSupport.money(fixed,"Taxa fixa");
        if (percent==null || percent.signum()<0 || percent.compareTo(HUNDRED)>0
            || percent.scale()>4) {
            throw CommerceFinanceSupport.invalid("Percentual da maquininha inválido");
        }
        BigDecimal calculated=salesGross.multiply(percent)
            .divide(HUNDRED,2,RoundingMode.HALF_UP).add(flat);
        calculated=CommerceFinanceSupport.money(calculated,"Total das taxas");
        if (calculated.compareTo(salesGross)>0) {
            throw CommerceFinanceSupport.invalid("A taxa não pode exceder o valor da venda");
        }
        return calculated;
    }
}
