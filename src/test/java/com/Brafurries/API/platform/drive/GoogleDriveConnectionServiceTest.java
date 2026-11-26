package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveConnectionServiceTest {
    private final PlatformAuthorizationService authz=mock(PlatformAuthorizationService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final String key=Base64.getEncoder().encodeToString(new byte[32]);
    private final String state="a".repeat(43);
    private final DriveTokenCipher cipher=new DriveTokenCipher(key);

    private GoogleDriveConnectionService service(boolean enabled) {
        return new GoogleDriveConnectionService(authz,jdbc,google,enabled,
            "pilot.apps.googleusercontent.com","fixture-client-value","institution@example.invalid",
            "fixture_folder",key,"https://api.example.invalid");
    }

    @BeforeEach void owner() {
        User user=new User();user.setId(7);
        when(authz.requireOwner(auth)).thenReturn(user);
    }

    @Test void encryptionUsesRandomNonceAndAuthenticatesPurpose() {
        byte[] a=cipher.encrypt("fixture-token","refresh");
        byte[] b=cipher.encrypt("fixture-token","refresh");
        assertFalse(java.util.Arrays.equals(a,b));
        assertEquals("fixture-token",cipher.decrypt(a,"refresh"));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(a,"verifier"));
        a[a.length-1]^=1;
        assertThrows(IllegalStateException.class,()->cipher.decrypt(a,"refresh"));
    }

    @Test void nonOwnerCannotStartOrReadCredentials() {
        when(authz.requireOwner(auth)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->service(true).start(auth)).getStatusCode());
        verifyNoInteractions(jdbc,google);
    }

    @Test void disabledStatusDoesNotReadNewTables() {
        assertFalse(service(false).status(auth).configured());
        verifyNoInteractions(jdbc,google);
    }

    @Test void startUsesOfflinePkceAndDoesNotExposeClientSecret() {
        String url=service(true).start(auth).authorizationUrl();
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state="));
        assertFalse(url.contains("fixture-client-value"));
        verifyNoInteractions(google);
    }

    @Test void invalidStateDoesNotCallGoogle() {
        var error=assertThrows(ResponseStatusException.class,()->service(true).complete(auth,"invalid","code"));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        verifyNoInteractions(jdbc,google);
    }

    @Test void missingOrConsumedStateDoesNotExchangeCode() {
        when(jdbc.query(contains("SELECT verifier_ciphertext"),
            ArgumentMatchers.<RowMapper<byte[]>>any(),eq(7),any(byte[].class))).thenReturn(List.of());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->service(true).complete(auth,state,"code")).getStatusCode());
        verifyNoInteractions(google);
    }

    private void attempt() throws Exception {
        when(jdbc.query(contains("SELECT verifier_ciphertext"),
            ArgumentMatchers.<RowMapper<byte[]>>any(),eq(7),any(byte[].class)))
            .thenReturn(List.of(cipher.encrypt("fixture-verifier","drive:oauth-verifier:v1:7")));
        when(google.token(anyMap())).thenReturn(json.readTree("""
            {"access_token":"fixture-access","refresh_token":"fixture-refresh",
             "scope":"openid https://www.googleapis.com/auth/drive.file"}
            """));
    }

    @Test void rejectsWrongAccountAndConsumesAttemptWithoutStoringCredential() throws Exception {
        attempt();
        when(google.identity("fixture-access")).thenReturn(json.readTree("""
            {"sub":"123","email":"other@example.invalid","email_verified":true}
            """));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
            assertThrows(ResponseStatusException.class,()->service(true).complete(auth,state,"code")).getStatusCode());
        verify(jdbc).update("DELETE FROM platform_google_drive_oauth_attempt WHERE owner_user_id=?",7);
        verify(jdbc,never()).update(contains("INSERT INTO platform_google_drive_connection"),any(Object[].class));
    }

    @Test void acceptsVerifiedInstitutionAndStoresOnlyCiphertext() throws Exception {
        attempt();
        when(google.identity("fixture-access")).thenReturn(json.readTree("""
            {"sub":"123","email":"institution@example.invalid","email_verified":true}
            """));
        when(jdbc.query(contains("SELECT google_subject"),
            ArgumentMatchers.<RowMapper<GoogleDriveConnectionService.Connection>>any())).thenReturn(List.of());
        assertTrue(service(true).complete(auth,state,"code").connected());
        var ciphertext=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(jdbc).update(contains("INSERT INTO platform_google_drive_connection"),
            eq("123"),eq("institution@example.invalid"),eq("pilot.apps.googleusercontent.com"),ciphertext.capture(),eq(7));
        assertEquals("fixture-refresh",cipher.decrypt(ciphertext.getValue(),"drive:institutional-refresh:v1"));
        verify(google).token(argThat(form->"fixture-verifier".equals(form.get("code_verifier"))
            && "https://api.example.invalid/user/me/platform-drive/oauth/callback".equals(form.get("redirect_uri"))));
    }

    @Test void rootPickerDoesNotExposeCredentialsToNonOwner() {
        when(authz.requireOwner(auth)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->service(true).rootPickerIdentity(auth)).getStatusCode());
        verifyNoInteractions(jdbc,google);
    }

    @Test void rootPickerRequiresMatchingConnection() {
        when(jdbc.query(contains("SELECT google_subject"),
            ArgumentMatchers.<RowMapper<GoogleDriveConnectionService.Connection>>any())).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->service(true).rootPickerIdentity(auth)).getStatusCode());
        verifyNoInteractions(google);
    }

    @Test void rootPickerReturnsStoredInstitutionalSubjectWithoutRefreshingToken() {
        connected();
        var identity=service(true).rootPickerIdentity(auth);
        assertEquals("123",identity.googleSubject());
        assertEquals("institution@example.invalid",identity.googleEmail());
        assertEquals("fixture_folder",identity.folderId());
        verifyNoInteractions(google);
    }

    private void connected() {
        when(jdbc.query(contains("SELECT google_subject"),
            ArgumentMatchers.<RowMapper<GoogleDriveConnectionService.Connection>>any())).thenReturn(List.of(
            new GoogleDriveConnectionService.Connection("123","institution@example.invalid",
                "pilot.apps.googleusercontent.com",cipher.encrypt("fixture-refresh","drive:institutional-refresh:v1"))));
    }

    @Test void rootMetadataDistinguishesOwnershipAndShareCapability() throws Exception {
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("""
            {"id":"fixture_folder","mimeType":"application/vnd.google-apps.folder",
             "trashed":false,"ownedByMe":true,"capabilities":{"canShare":true}}
            """);
        assertEquals("ACCESSIBLE",GoogleDriveConnectionService.classifyRoot(root,"fixture_folder").status());
        root.put("ownedByMe",false);
        assertEquals("NOT_OWNED",GoogleDriveConnectionService.classifyRoot(root,"fixture_folder").status());
        root.put("ownedByMe",true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.get("capabilities")).put("canShare",false);
        assertEquals("CANNOT_SHARE",GoogleDriveConnectionService.classifyRoot(root,"fixture_folder").status());
        root.put("trashed",true);
        assertEquals("TRASHED",GoogleDriveConnectionService.classifyRoot(root,"fixture_folder").status());
        root.put("mimeType","text/plain");
        assertEquals("NOT_FOLDER",GoogleDriveConnectionService.classifyRoot(root,"fixture_folder").status());
        assertEquals("UNAVAILABLE",GoogleDriveConnectionService.classifyRoot(root,"other_folder").status());
    }

    @Test void rootCheckUsesFreshServerTokenAndDistinguishesProviderErrors() throws Exception {
        connected();
        when(google.token(anyMap())).thenReturn(json.readTree("{\"access_token\":\"fixture-access\"}"));
        when(google.root("fixture-access","fixture_folder"))
            .thenThrow(new GoogleDriveOAuthClient.ProviderFailure(404))
            .thenThrow(new GoogleDriveOAuthClient.ProviderFailure(403))
            .thenThrow(new GoogleDriveOAuthClient.ProviderFailure(401));
        assertEquals("NOT_AUTHORIZED",service(true).checkRoot(auth).status());
        assertEquals("ACCESS_DENIED",service(true).checkRoot(auth).status());
        assertEquals("RECONNECT_REQUIRED",service(true).checkRoot(auth).status());
        verify(google,times(3)).token(argThat(form->"refresh_token".equals(form.get("grant_type"))));
    }
}
