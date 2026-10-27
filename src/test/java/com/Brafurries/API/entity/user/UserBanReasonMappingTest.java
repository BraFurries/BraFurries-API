package com.Brafurries.API.entity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.Column;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UserBanReasonMappingTest {

    @Test
    void reasonColumnSupportsDetailedModerationRationale() throws Exception {
        Column column = UserBan.class.getDeclaredField("reason").getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals(2048, column.length());
        assertEquals(false, column.nullable());
    }

    @Test
    void banDateAndValidityRemainDateOnlyDuringCapacityMigration() throws Exception {
        assertEquals(LocalDate.class, UserBan.class.getDeclaredField("date").getType());
        assertEquals(LocalDate.class, UserBan.class.getDeclaredField("validUntil").getType());
    }
}
