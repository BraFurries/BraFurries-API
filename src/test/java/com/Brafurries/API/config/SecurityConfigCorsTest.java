package com.Brafurries.API.config;

import com.Brafurries.API.auth.social.SocialFrontendOriginPolicy;
import com.Brafurries.API.auth.social.SocialOAuth2AuthenticationSuccessHandler;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    @Test
    void alwaysAddsFirebasePreviewPatternToConfiguredCorsOrigins() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientRegistrationRepository> registrations = mock(ObjectProvider.class);

        SecurityConfig config = new SecurityConfig(
            mock(BearerTokenAuthenticationFilter.class),
            mock(UserRepository.class),
            mock(UserTokenRepository.class),
            mock(ApiUserRoleRepository.class),
            mock(ApiRolePermissionRepository.class),
            mock(SocialOAuth2AuthenticationSuccessHandler.class),
            registrations,
            new ObjectMapper()
        );
        ReflectionTestUtils.setField(
            config,
            "allowedOrigins",
            "https://brafurries.com.br,https://www.brafurries.com.br"
        );

        var cors = config.corsConfigurationSource()
            .getCorsConfiguration(new MockHttpServletRequest());

        List<String> patterns = cors.getAllowedOriginPatterns();
        assertEquals(3, patterns.size());
        assertTrue(patterns.contains("https://brafurries.com.br"));
        assertTrue(patterns.contains("https://www.brafurries.com.br"));
        assertTrue(patterns.contains(SocialFrontendOriginPolicy.FIREBASE_PREVIEW_ORIGIN_PATTERN));
    }
}
