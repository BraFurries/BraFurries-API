package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioZohoCpaasWebhookControllerTest {
    @Mock JdbcTemplate jdbc;
    private static final String SECRET = "test-secret-not-for-production";
    private static final String AGENT = "agent-test";

    private CreatorStudioZohoCpaasWebhookController endpoint() {
        return new CreatorStudioZohoCpaasWebhookController(
            jdbc, new ObjectMapper(), SECRET, AGENT);
    }

    private static byte[] payload(String event, String clientRef) {
        String json = """
            {"event_name":["%s"],"webhook_request_id":"evt-123",
             "mailagent_key":"agent-test",
             "event_message":[{"email_info":{
               "client_reference":"%s",
               "is_smtp_trigger":true,
               "to":[{"email_address":{"address":"artist@example.invalid"}}]
             }}]}""".formatted(event,clientRef);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Stub the same lock order used by the destructive retention worker. */
    private void existingArtistCommission() {
        when(jdbc.query(contains("SELECT owner_user_id,commission_id"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(42L), eq("artist@example.invalid")))
            .thenReturn(java.util.List.of(
                new CreatorStudioZohoCpaasWebhookController.NoticeScope(7, 51L)));
        when(jdbc.queryForList(contains("SELECT id FROM creator_studio_commissions"),
                eq(Long.class), eq(7), eq(51L)))
            .thenReturn(java.util.List.of(51L));
    }

    private static String signature(byte[] body, long timestamp) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String encoded=URLEncoder.encode(Base64.getEncoder().encodeToString(
            mac.doFinal(body)), StandardCharsets.UTF_8);
        return "ts="+timestamp+";s="+encoded+";s-algorithm=HmacSHA256";
    }

    @Test void authenticatedDeliveryCanOnlyMatchCurrentVerifiedArtistAndCompletionEpoch()
            throws Exception {
        existingArtistCommission();
        byte[] body=payload("delivered","cs-ret-42");
        var response=endpoint().event(signature(body,System.currentTimeMillis()),body);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        verify(jdbc).update(argThat(sql ->
            sql.contains("n.delivery_state='SERVER_ACCEPTED'")
            && sql.contains("n.delivery_evidence_at=UTC_TIMESTAMP(6)")
            && sql.contains("n.delivery_state='UNCONFIRMED'")
            && sql.contains("n.status IN ('SENDING','SENT')")
            && sql.contains("c.completed_at=n.completed_at")
            && sql.contains("n.recipient_email=?")),
            eq("ZOHO_CPAAS"),eq("evt-123"),eq(42L),eq("artist@example.invalid"));
    }

    @Test void missingArtistCommissionNeverUpdatesDeliveryEvidence() throws Exception {
        byte[] body=payload("hardbounce","cs-ret-42");
        when(jdbc.query(contains("SELECT owner_user_id,commission_id"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(42L), eq("artist@example.invalid")))
            .thenReturn(java.util.List.of(
                new CreatorStudioZohoCpaasWebhookController.NoticeScope(7,51L)));
        // Parent row was concurrently purged or never belonged to this artist.
        assertEquals(HttpStatus.OK,
            endpoint().event(signature(body,System.currentTimeMillis()),body).getStatusCode());
        verify(jdbc).queryForList(
            argThat((String sql) -> sql.contains("owner_user_id=? AND id=? FOR UPDATE")),
            eq(Long.class),eq(7),eq(51L));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void locksParentBeforeRevokingPreviouslyConfirmedDelivery() throws Exception {
        existingArtistCommission();
        byte[] body=payload("hardbounce","cs-ret-42");
        endpoint().event(signature(body,System.currentTimeMillis()),body);
        var ordered=inOrder(jdbc);
        ordered.verify(jdbc).query(contains("SELECT owner_user_id,commission_id"),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(42L),eq("artist@example.invalid"));
        ordered.verify(jdbc).queryForList(contains("FOR UPDATE"),
            eq(Long.class),eq(7),eq(51L));
        ordered.verify(jdbc).update(
            contains("SET n.delivery_state=?"),eq("BOUNCED"),eq("ZOHO_CPAAS"),
            eq("evt-123"),eq(42L),eq("artist@example.invalid"));
    }

    @Test void rejectsOversizedPublicBodyBeforeReadingIt() throws Exception {
        MockHttpServletRequest req=new MockHttpServletRequest();
        req.setContent(new byte[32769]);
        var denied=assertThrows(ResponseStatusException.class,
            () -> endpoint().receive(null,req));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,denied.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void refusesUnboundedChunkedBodyEvenWithoutContentLength() throws Exception {
        HttpServletRequest req=mock(HttpServletRequest.class);
        when(req.getContentLengthLong()).thenReturn(-1L);
        when(req.getInputStream()).thenReturn(
            new DelegatingServletInputStream(new ByteArrayInputStream(new byte[32769])));
        var denied=assertThrows(ResponseStatusException.class,
            () -> endpoint().receive(null,req));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,denied.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsInvalidOrExpiredSignatureWithoutDatabaseAccess() throws Exception {
        byte[] body=payload("delivered","cs-ret-42");
        ResponseStatusException tampered=assertThrows(ResponseStatusException.class,
            () -> endpoint().event(signature(body,System.currentTimeMillis()),
                payload("delivered","cs-ret-43")));
        assertEquals(HttpStatus.FORBIDDEN,tampered.getStatusCode());
        ResponseStatusException expired=assertThrows(ResponseStatusException.class,
            () -> endpoint().event(signature(body,System.currentTimeMillis()-3600000),body));
        assertEquals(HttpStatus.FORBIDDEN,expired.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsEventsFromUnexpectedMailAgent() throws Exception {
        byte[] body=payload("delivered","cs-ret-42");
        String foreign=new String(body,StandardCharsets.UTF_8).replace(
            "\"mailagent_key\":\"agent-test\"","\"mailagent_key\":\"different\"");
        byte[] foreignBytes=foreign.getBytes(StandardCharsets.UTF_8);
        var denied=assertThrows(ResponseStatusException.class,
            () -> endpoint().event(signature(foreignBytes,System.currentTimeMillis()),foreignBytes));
        assertEquals(HttpStatus.FORBIDDEN,denied.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void unrelatedSignedMailDoesNotTouchRetentionLedger() throws Exception {
        byte[] body=payload("delivered","marketing-42");
        assertEquals(HttpStatus.OK,endpoint().event(
            signature(body,System.currentTimeMillis()),body).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void cpaaSApiEventCannotMasqueradeAsTrackedSmtpNotice() throws Exception {
        byte[] body = new String(payload("delivered", "cs-ret-42"),
            StandardCharsets.UTF_8).replace("\"is_smtp_trigger\":true",
            "\"is_smtp_trigger\":false").getBytes(StandardCharsets.UTF_8);
        assertEquals(HttpStatus.OK, endpoint().event(
            signature(body, System.currentTimeMillis()), body).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void ambiguousZohoBatchWithMultipleEventsIsIgnoredFailClosed() throws Exception {
        byte[] body=("{\"event_name\":[\"delivered\",\"hardbounce\"],"
            +"\"mailagent_key\":\"agent-test\",\"webhook_request_id\":\"evt-123\","
            +"\"event_message\":[{\"email_info\":{\"client_reference\":\"cs-ret-42\"}}]}")
            .getBytes(StandardCharsets.UTF_8);
        assertEquals(HttpStatus.OK,endpoint().event(
            signature(body,System.currentTimeMillis()),body).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void signedBounceRevokesEvidenceAndNeverResendsMail() throws Exception {
        existingArtistCommission();
        byte[] body=payload("hardbounce","cs-ret-42");
        assertEquals(HttpStatus.OK,endpoint().event(
            signature(body,System.currentTimeMillis()),body).getStatusCode());
        verify(jdbc).update(argThat(sql ->
            sql.contains("n.delivery_state=?")
            && sql.contains("n.delivery_evidence_at=NULL")
            && sql.contains("n.delivery_state<>'COMPLAINT'")
            && sql.contains("n.status IN ('SENDING','SENT','UNKNOWN')")),
            eq("BOUNCED"),eq("ZOHO_CPAAS"),eq("evt-123"),eq(42L),
            eq("artist@example.invalid"));
    }
}
