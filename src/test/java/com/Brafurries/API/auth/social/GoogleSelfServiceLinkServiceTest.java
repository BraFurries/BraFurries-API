package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AccessTokenService;
import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.hamcrest.Matchers;

@ExtendWith(MockitoExtension.class)
class GoogleSelfServiceLinkServiceTest {
    @Mock UserRepository users;
    @Mock UserRefreshTokenRepository refreshTokens;
    @Mock TokenService tokens;
    @Mock AccessTokenService accessTokens;
    @Mock ApiUserRoleRepository roles;
    @Mock ApiRolePermissionRepository permissions;
    @Mock UserDiscordRepository discord;
    @Mock UserTokenRepository userTokens;
    @Mock DiscordSelfServiceLinkService discordLinks;
    @Mock VerifiedSocialIdentityService identities;

    private AuthSocialService service() {
        return new AuthSocialService(users, refreshTokens, tokens, accessTokens,
            roles, permissions, discord, userTokens, discordLinks, identities);
    }

    private User siteUser(int id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    @Test
    void requiresSiteSessionBeforeVerifyingGoogleToken() {
        AuthSocialService service = service();
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.linkGoogleToAuthenticatedAccount(null, "fake-id-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(users, identities);
    }

    @Test
    void deniesLinkingWhenGoogleOAuthClientIdIsNotConfigured() {
        AuthSocialService service = service();
        var session = new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.linkGoogleToAuthenticatedAccount(session, "fake-id-token"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verifyNoInteractions(users, identities);
    }

    @Test
    void verifiesGoogleTokenAndAuthenticatedSiteAccountIndependently() {
        AuthSocialService service = service();
        ReflectionTestUtils.setField(service, "googleClientId", "expected-client");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "restClient", builder.build());
        server.expect(requestTo(Matchers.containsString("id_token=verified-google-token")))
            .andRespond(withSuccess("""
                {"sub":"immutable-google-sub","email":"Owner@Example.com",
                 "email_verified":"true","aud":"expected-client","name":"Owner"}
                """, MediaType.APPLICATION_JSON));
        User owner = siteUser(10, "owner@example.com");
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(owner));

        String status = service.linkGoogleToAuthenticatedAccount(
            new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()),
            "verified-google-token");

        assertEquals("VERIFIED", status);
        verify(identities).bindProvenSubject(owner, "google", "immutable-google-sub", "owner@example.com");
        verify(users, never()).save(any());
        server.verify();
    }

    @Test
    void verifiedGoogleSubjectCannotBypassExistingOtherAccount() {
        AuthSocialService service = service();
        ReflectionTestUtils.setField(service, "googleClientId", "expected-client");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "restClient", builder.build());
        server.expect(requestTo(Matchers.containsString("id_token=verified-google-token")))
            .andRespond(withSuccess("""
                {"sub":"immutable-google-sub","email":"other@example.com",
                 "email_verified":"true","aud":"expected-client","name":"Other"}
                """, MediaType.APPLICATION_JSON));
        User owner = siteUser(10, "owner@example.com");
        User other = siteUser(20, "other@example.com");
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(users.findByEmail("other@example.com")).thenReturn(Optional.of(other));
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(owner));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.linkGoogleToAuthenticatedAccount(
                new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()),
                "verified-google-token"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("ACCOUNT_RECONCILIATION_REQUIRED", error.getReason());
        verifyNoInteractions(identities);
        server.verify();
    }

    @Test
    void rejectsTokenIssuedForDifferentOAuthClient() {
        AuthSocialService service = service();
        ReflectionTestUtils.setField(service, "googleClientId", "expected-client");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(service, "restClient", builder.build());
        server.expect(requestTo(Matchers.containsString("id_token=verified-google-token")))
            .andRespond(withSuccess("""
                {"sub":"immutable-google-sub","email":"owner@example.com",
                 "email_verified":"true","aud":"wrong-client","name":"Owner"}
                """, MediaType.APPLICATION_JSON));

        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
            () -> service.linkGoogleToAuthenticatedAccount(
                new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()),
                "verified-google-token"));
        verifyNoInteractions(users, identities);
        server.verify();
    }
}
