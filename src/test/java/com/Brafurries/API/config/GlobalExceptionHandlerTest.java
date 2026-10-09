package com.Brafurries.API.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void unexpectedExceptionReturnsSafeInternalServerError() throws Exception {
        MvcResult result = mvc.perform(get("/unexpected/private-invite-token"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("Internal Server Error"))
            .andExpect(jsonPath("$.message").value("Erro interno do servidor"))
            .andExpect(jsonPath("$.path").value("/unexpected/private-invite-token"))
            .andExpect(content().string(not(containsString("internal storage credential"))))
            .andReturn();

        assertEquals("/unexpected/{token}", GlobalExceptionHandler.resolveSafeRoute(result.getRequest()));
    }

    @Test
    void unresolvedRouteDoesNotUseConcreteRequestUri() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unexpected/private-invite-token");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, null);

        assertEquals("<unresolved-route>", GlobalExceptionHandler.resolveSafeRoute(request));
    }

    @Test
    void responseStatusExceptionKeepsItsPublicContract() throws Exception {
        mvc.perform(get("/expected"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("Erro esperado"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/unexpected/{token}")
        void unexpected() {
            throw new IllegalStateException("internal storage credential");
        }

        @GetMapping("/expected")
        void expected() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Erro esperado");
        }
    }
}
