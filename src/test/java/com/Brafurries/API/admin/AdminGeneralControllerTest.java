package com.Brafurries.API.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.Brafurries.API.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminGeneralControllerTest {
    private AdminBotStatusService logs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        logs = mock(AdminBotStatusService.class);
        mvc = MockMvcBuilders.standaloneSetup(
            new AdminGeneralController(mock(AdminDashboardService.class), logs)
        ).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 5832, Long.MAX_VALUE})
    void acceptsNonNegativeCursor(long after) throws Exception {
        mvc.perform(get("/admin/bot/logs").param("after", Long.toString(after)))
            .andExpect(status().isOk());
        verify(logs).getLogs(null, null, after, null);
    }

    @Test
    void rejectsNegativeCursorBeforeCallingCoddy() throws Exception {
        mvc.perform(get("/admin/bot/logs").param("after", "-1"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(logs);
    }

    @Test
    void acceptsBeforeWithoutAfter() throws Exception {
        mvc.perform(get("/admin/bot/logs").param("before", "100"))
            .andExpect(status().isOk());
        verify(logs).getLogs(null, null, null, 100L);
    }

    @Test
    void rejectsNegativeBeforeBeforeCallingCoddy() throws Exception {
        mvc.perform(get("/admin/bot/logs").param("before", "-1"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(logs);
    }

    @Test
    void rejectsBeforeAndAfterTogetherBeforeCallingCoddy() throws Exception {
        mvc.perform(get("/admin/bot/logs")
                .param("before", "100")
                .param("after", "200"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(logs);
    }

    @Test
    void acceptsFullLoadWithoutCursor() throws Exception {
        mvc.perform(get("/admin/bot/logs")).andExpect(status().isOk());
        verify(logs).getLogs(null, null, null, null);
    }

    @Test
    void acceptsAndForwardsHistoryCursorAndMaximumLimit() throws Exception {
        mvc.perform(get("/admin/bot/logs")
                .param("limit", "1000")
                .param("level", "INFO")
                .param("before", "5000"))
            .andExpect(status().isOk());
        verify(logs).getLogs(1000, "INFO", null, 5000L);
    }
}
