package com.Brafurries.API.auth.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocialFrontendOriginPolicyTest {

    private final SocialFrontendOriginPolicy policy =
        new SocialFrontendOriginPolicy("https://brafurries.com.br");

    @Test
    void acceptsProductionOrigins() {
        assertEquals("https://brafurries.com.br", policy.resolve("https://brafurries.com.br"));
        assertEquals("https://www.brafurries.com.br", policy.resolve("https://www.brafurries.com.br"));
    }

    @Test
    void acceptsFirebasePreviewOrigin() {
        assertEquals(
            "https://brafurries--pr94-feature-community-se-c4u6j0t1.web.app",
            policy.resolve("https://brafurries--pr94-feature-community-se-c4u6j0t1.web.app")
        );
    }

    @Test
    void acceptsLocalDevelopmentOrigin() {
        assertEquals("http://localhost:4200", policy.resolve("http://localhost:4200"));
    }

    @Test
    void rejectsForeignHostsAndLookalikes() {
        assertEquals("https://brafurries.com.br", policy.resolve("https://evil.example"));
        assertEquals("https://brafurries.com.br", policy.resolve("https://brafurries--pr94.web.app.evil.example"));
        assertEquals("https://brafurries.com.br", policy.resolve("https://other-project--pr94.web.app"));
    }

    @Test
    void rejectsPathsQueriesFragmentsAndPreviewPorts() {
        assertEquals("https://brafurries.com.br", policy.resolve("https://brafurries--pr94.web.app/steal"));
        assertEquals("https://brafurries.com.br", policy.resolve("https://brafurries--pr94.web.app?next=x"));
        assertEquals("https://brafurries.com.br", policy.resolve("https://brafurries--pr94.web.app#x"));
        assertEquals("https://brafurries.com.br", policy.resolve("https://brafurries--pr94.web.app:8443"));
    }

    @Test
    void normalizesAllowedOriginCaseAndTrailingSlash() {
        assertEquals(
            "https://brafurries--pr94.web.app",
            policy.resolve("HTTPS://BRAFURRIES--PR94.WEB.APP/")
        );
    }
}
