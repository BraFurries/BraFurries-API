package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class OpenAiCredentialValidatorTest {
    private MockRestServiceServer server;
    private OpenAiCredentialValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        validator = new OpenAiCredentialValidator(builder.build());
    }

    @Test
    void sendsCredentialOnlyToOpenAiValidationRequest() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer sk-test"))
            .andRespond(withSuccess("{\"id\":\"resp_test\"}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> validator.validate("sk-test", "gpt-5-mini"));
        server.verify();
    }

    @Test
    void rejectedCredentialMapsToUnprocessableEntity() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"invalid_api_key\"}}"));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> validator.validate("bad-secret", "gpt-5-mini")
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }

    @Test
    void quotaFailureMapsWithoutExposingProviderBody() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"insufficient_quota\"}}"));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> validator.validate("sk-test", "gpt-5-mini")
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertFalse(error.getReason().contains("insufficient_quota"));
    }
}
