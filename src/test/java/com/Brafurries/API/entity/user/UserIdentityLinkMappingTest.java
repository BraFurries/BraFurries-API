package com.Brafurries.API.entity.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.persistence.JoinColumn;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class UserIdentityLinkMappingTest {

    @Test
    void mapsEquivalentEndpointsToTheRenamedPhysicalColumns() throws Exception {
        assertEquals("user_a_id", joinColumn("userA"));
        assertEquals("user_b_id", joinColumn("userB"));

        Arrays.stream(UserIdentityLink.class.getDeclaredFields())
            .map(Field::getName)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .forEach(name -> {
                assertFalse(name.equals("primaryuser"));
                assertFalse(name.equals("linkeduser"));
                assertFalse(name.contains("root"));
            });
    }

    private String joinColumn(String fieldName) throws Exception {
        return UserIdentityLink.class.getDeclaredField(fieldName).getAnnotation(JoinColumn.class).name();
    }
}
