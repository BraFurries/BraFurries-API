package com.Brafurries.API.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Global BraFurries platform ACL, independent of Community capabilities. */
public enum PlatformPermission {
    ARTWORKS_MANAGE, CONTRACTS_MANAGE, SUPPLIERS_MANAGE, PRODUCTS_MANAGE,
    SALES_MANAGE, FINANCE_VIEW, SETTLEMENTS_MANAGE, PAYMENTS_RECORD,
    CASHFLOW_MANAGE, PARTNERS_MANAGE;

    public static PlatformPermission parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Permissão de plataforma desconhecida");
        }
    }
}
