package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;

import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuildManagementDtosTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void readsCamelCaseAndSnakeCaseResourcePayloadsEqually() throws Exception {
        String camel = """
            {"guildId":"123","roles":[{"id":"1","name":"R","position":1,"managed":false,"editableByBot":true}],
             "channels":[{"id":"2","name":"c","type":"text","categoryId":"3"}],"categories":[],
             "botCapabilities":{"canManageChannels":true,"canManageRoles":true,"canApply":true,"missingPermissions":[],
             "hasAdministrator":true,"roleHierarchyOk":false,"protectionReady":false,
             "botTopRole":{"id":"9","name":"Coddy","position":10},
             "rolesAboveBot":[{"id":"11","name":"Integração","position":20,"managed":true}]}}
            """;
        String snake = camel.replace("guildId", "guild_id").replace("editableByBot", "editable_by_bot")
            .replace("categoryId", "category_id").replace("botCapabilities", "bot_capabilities")
            .replace("canManageChannels", "can_manage_channels").replace("canManageRoles", "can_manage_roles")
            .replace("canApply", "can_apply").replace("missingPermissions", "missing_permissions")
            .replace("hasAdministrator", "has_administrator").replace("roleHierarchyOk", "role_hierarchy_ok")
            .replace("protectionReady", "protection_ready").replace("botTopRole", "bot_top_role")
            .replace("rolesAboveBot", "roles_above_bot");
        assertEquals(mapper.readValue(camel, GuildResources.class), mapper.readValue(snake, GuildResources.class));
    }

    @Test void validatesStaffRoleElementsAndEntryRanges() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String invalid : java.util.Arrays.asList(null, "", " ", "abc", "0", "-1")) {
                assertFalse(validator.validate(new StaffRolesRequest(java.util.Collections.singletonList(invalid))).isEmpty());
            }
            assertTrue(validator.validate(new StaffRolesRequest(List.of())).isEmpty());
            assertFalse(validator.validate(new EntryUpdateRequest(null, null, null, null,
                true, 0, false, -1, false, -1, false, -1)).isEmpty());
        }
    }

    @Test void keepsLegacyMissingProtectionFieldsUnknownAndExplicitFalseDistinct() throws Exception {
        String legacy = """
            {"guildId":"123","roles":[],"channels":[],"categories":[],
             "botCapabilities":{"canManageChannels":true,"canManageRoles":true,"canApply":true,"missingPermissions":[]}}
            """;
        BotCapabilities absent = mapper.readValue(legacy, GuildResources.class).botCapabilities();
        assertNull(absent.hasAdministrator());
        assertNull(absent.roleHierarchyOk());
        assertNull(absent.protectionReady());

        String explicitFalse = legacy.replace("\"missingPermissions\":[]", "\"missingPermissions\":[],\"hasAdministrator\":false,\"roleHierarchyOk\":false,\"protectionReady\":false");
        BotCapabilities explicit = mapper.readValue(explicitFalse, GuildResources.class).botCapabilities();
        assertEquals(Boolean.FALSE, explicit.hasAdministrator());
        assertEquals(Boolean.FALSE, explicit.roleHierarchyOk());
        assertEquals(Boolean.FALSE, explicit.protectionReady());
    }

    @Test void keepsBumpChannelAndRoleSnowflakesAsStringsInJson() throws Exception {
        BumpConfigResponse response = new BumpConfigResponse(new BumpProcessingConfig("123456789012345678"),
            new BumpWarningConfig(false, null, null, List.of()),
            new BumpIndividualRewardConfig(new BumpRoleRewardConfig(false, "223456789012345678", 0, null),
                new BumpCoinRewardConfig(false, null)),
            new BumpMonthlyRankingConfig(false, null, "323456789012345678", List.of()),
            new BumpOperationalState(null, null));
        String json = mapper.writeValueAsString(response);
        assertTrue(json.contains("\"channelId\":\"123456789012345678\""));
        assertTrue(json.contains("\"roleId\":\"223456789012345678\""));
        assertEquals("123456789012345678", mapper.readValue(json, BumpConfigResponse.class).processing().channelId());
    }
}
