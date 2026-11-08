package com.Brafurries.API.config;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BearerTokenAuthenticationFilterTest {
    private final TokenService tokens = new TokenService("reconciliation-ci-signing-key", 900, 1209600);
    private final UserRepository users = mock(UserRepository.class);
    private final UserTokenRepository verification = mock(UserTokenRepository.class);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void dispatch(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/me");
        request.addHeader("Authorization", "Bearer " + token);
        new BearerTokenAuthenticationFilter(tokens, users, verification)
            .doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void signedTokenCannotSurviveCanonicalUsersAuthVersionIncrement() throws Exception {
        User user = new User();
        user.setId(10);
        user.setEmail("owner@example.com");
        user.setAuthVersion(2L);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        String priorToken = tokens.generateAccessToken("owner@example.com", List.of("ADMIN"), List.of(), 1L);
        dispatch(priorToken);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void signedTokenWithCurrentVersionStillAuthenticates() throws Exception {
        User user = new User();
        user.setId(10);
        user.setEmail("owner@example.com");
        user.setAuthVersion(2L);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        String current = tokens.generateAccessToken("owner@example.com", List.of(), List.of(), 2L);

        dispatch(current);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("owner@example.com",
            SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void absorbedProvenanceAliasNeverAuthenticatesEvenWithCurrentSignedVersion() throws Exception {
        User canonical = new User();
        canonical.setId(20);
        User absorbed = new User();
        absorbed.setId(10);
        absorbed.setEmail("absorbed@example.com");
        absorbed.setMergedIntoUser(canonical);
        when(users.findByEmail("absorbed@example.com")).thenReturn(Optional.of(absorbed));

        dispatch(tokens.generateAccessToken("absorbed@example.com", List.of(), List.of(), 0L));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void legacyJwtWithoutVersionIsOnlyValidBeforeAccountVersionChanges() throws Exception {
        User user = new User();
        user.setId(10);
        user.setEmail("owner@example.com");
        user.setAuthVersion(0L);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        String legacy = tokens.generateAccessToken("owner@example.com", List.of(), List.of());

        dispatch(legacy);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
