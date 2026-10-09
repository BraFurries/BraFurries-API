package com.Brafurries.API.user.dto;

import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberProfileUpdateDtosTest {

    @Test
    void updateMemberProfileRequestRejectsBlankDisplayName() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertFalse(validator.validate(new UpdateMemberProfileRequest(" ")).isEmpty());
        }
    }

    @Test
    void updateMemberProfileRequestLeavesLengthValidationForThePostTrimServiceRule() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertTrue(validator.validate(new UpdateMemberProfileRequest("a".repeat(33))).isEmpty());
            assertTrue(validator.validate(new UpdateMemberProfileRequest("a".repeat(32))).isEmpty());
        }
    }
}
