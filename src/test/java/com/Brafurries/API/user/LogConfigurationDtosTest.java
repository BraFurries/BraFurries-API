package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import com.Brafurries.API.user.dto.LogConfigurationDtos.*;
import org.junit.jupiter.api.Test;

class LogConfigurationDtosTest {
    @Test void updateRequestKeepsExplicitTypedFields() {
        UpdateLogRequest request = new UpdateLogRequest(true, "123");
        assertTrue(request.enabled()); assertEquals("123", request.targetId());
    }
    @Test void testRequestKeepsTargetId() { assertEquals("456", new TestLogRequest("456").targetId()); }
}
