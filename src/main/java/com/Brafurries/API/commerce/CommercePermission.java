package com.Brafurries.API.commerce;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Private BraFurries commercial permissions, deliberately NOT CommunityCapability. */
public enum CommercePermission {
    ARTWORKS_MANAGE,
    CONTRACTS_MANAGE,
    SUPPLIERS_MANAGE,
    PRODUCTS_MANAGE,
    SALES_MANAGE,
    FINANCE_VIEW,
    SETTLEMENTS_MANAGE,
    PAYMENTS_RECORD,
    CASHFLOW_MANAGE;

    public static CommercePermission parse(String input) {
        try {
            return valueOf(input);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Permissão comercial desconhecida"
            );
        }
    }
}
