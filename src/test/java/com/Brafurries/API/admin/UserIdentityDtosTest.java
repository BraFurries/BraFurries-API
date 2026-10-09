package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.Brafurries.API.admin.dto.UserIdentityDtos;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class UserIdentityDtosTest {

    @Test
    void publicIdentityContractsDoNotExposeHierarchyConcepts() {
        for (Class<?> contract : UserIdentityDtos.class.getDeclaredClasses()) {
            if (!contract.isRecord()) {
                continue;
            }
            Arrays.stream(contract.getRecordComponents()).forEach(component -> {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("primary"), contract.getSimpleName() + "." + component.getName());
                assertFalse(name.contains("root"), contract.getSimpleName() + "." + component.getName());
                assertFalse(name.contains("secondary"), contract.getSimpleName() + "." + component.getName());
                assertFalse(name.equals("linkeduser") || name.equals("linkeduserid"),
                    contract.getSimpleName() + "." + component.getName());
            });
        }
    }
}
