package com.Brafurries.API.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.Brafurries.API.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminCoddyPresenceControllerTest {
    private AdminCoddyPresenceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AdminCoddyPresenceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminCoddyPresenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void forwardsFilterAndPaginationToService() throws Exception {
        mvc.perform(get("/admin/coddy-presence")
            .param("page", "2")
            .param("pageSize", "15")
            .param("status", "inactive")
            .param("search", "Nome")
            .param("communityId", "12"))
            .andExpect(status().isOk());
        verify(service).list(2, 15, "Nome", "inactive", 12);
    }

    @Test
    void usesSafePaginationDefaults() throws Exception {
        mvc.perform(get("/admin/coddy-presence")).andExpect(status().isOk());
        verify(service).list(1, 20, null, null, null);
    }

    @Test
    void doesNotCallServiceOnMalformedPageParameter() throws Exception {
        mvc.perform(get("/admin/coddy-presence").param("page", "wrong"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
