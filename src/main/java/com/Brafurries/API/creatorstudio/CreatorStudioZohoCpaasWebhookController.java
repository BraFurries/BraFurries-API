package com.Brafurries.API.creatorstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Zoho CPaaS webhook, activated only when the deployment explicitly opts in.
 * This is NOT a generic admin endpoint or an assertion of inbox delivery.
 *
 * The public route is protected by Zoho's signed, timestamped raw payload.
 * Unknown/ambiguous events fail closed without setting delivery evidence.
 */
@RestController
@RequestMapping("/webhooks/zoho-cpaas/creator-studio-retention")
@ConditionalOnProperty(prefix="app.creator-studio.retention",
    name="cpaas-webhook-enabled", havingValue="true")
public class CreatorStudioZohoCpaasWebhookController {
    private static final int MAX_BODY_BYTES = 32768;
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private static final String PROVIDER = "ZOHO_CPAAS";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String secret;
    private final String expectedAgent;

    public CreatorStudioZohoCpaasWebhookController(JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${app.creator-studio.retention.cpaas-webhook-secret:}") String secret,
            @Value("${app.creator-studio.retention.cpaas-agent-key:}") String expectedAgent) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.secret = secret;
        this.expectedAgent = expectedAgent;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Void> event(@RequestHeader(value="producer-signature",required=false)
                                      String signature, @RequestBody byte[] rawBody) {
        verify(signature, rawBody);
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed event");
        }
        if (root == null || !root.isObject() || expectedAgent == null ||
            expectedAgent.isBlank() || !expectedAgent.equals(root.path("mailagent_key").asText())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unrecognized mail agent");
        }
        // Zoho CPaaS sends event_name and event_message as arrays, even
        // for a single event. Never infer event meaning from a different
        // array entry; ignore ambiguous batches until explicitly supported.
        JsonNode types = root.path("event_name");
        JsonNode messages = root.path("event_message");
        if (!types.isArray() || types.size()!=1 ||
            !messages.isArray() || messages.size()!=1 ||
            !types.get(0).isTextual() || !messages.get(0).isObject()) {
            return ResponseEntity.ok().build();
        }
        String kind = types.get(0).asText();
        JsonNode detail = messages.get(0);
        // Only SMTP events originating from our trackable sender can
        // become retention evidence. Reject unrelated CPaaS API events.
        if (!detail.at("/email_info/is_smtp_trigger").asBoolean(false)) {
            return ResponseEntity.ok().build();
        }
        String eventRef = root.path("webhook_request_id").asText("");
        String clientRef = detail.at("/email_info/client_reference").asText("");
        if (eventRef.isBlank() || eventRef.length() > 190 ||
            !clientRef.matches("cs-ret-[1-9][0-9]{0,17}")) {
            // A valid signed event from an unrelated mail must not affect retention.
            return ResponseEntity.ok().build();
        }
        long noticeId;
        try {
            noticeId = Long.parseLong(clientRef.substring("cs-ret-".length()));
        } catch (NumberFormatException invalid) {
            return ResponseEntity.ok().build();
        }
        // Correlate to the original locally verified address captured
        // atomically at claim time. A later account email change must never
        // make a bounce for the original address invisible.
        JsonNode recipients = detail.at("/email_info/to");
        if (!recipients.isArray() || recipients.size()!=1) return ResponseEntity.ok().build();
        String destination = recipients.get(0).at("/email_address/address").asText("");
        if (destination.isBlank() || destination.length()>254) return ResponseEntity.ok().build();

        if ("delivered".equals(kind) || "email_delivered".equals(kind)) {
            jdbc.update("""
                UPDATE creator_studio_retention_notices n
                JOIN creator_studio_commissions c
                  ON c.owner_user_id=n.owner_user_id AND c.id=n.commission_id
                   SET n.delivery_state='SERVER_ACCEPTED',
                       n.delivery_provider=?,
                       n.delivery_event_ref=?,
                       n.delivery_evidence_at=UTC_TIMESTAMP(6)
                 WHERE n.id=? AND n.status='SENT' AND n.sent_at IS NOT NULL
                   AND n.delivery_state='UNCONFIRMED'
                   AND c.status='COMPLETED' AND c.completed_at=n.completed_at
                   AND n.recipient_email=?
                """, PROVIDER, eventRef, noticeId, destination);
        } else if ("hardbounce".equals(kind) || "softbounce".equals(kind)
                || "hard_bounce".equals(kind) || "soft_bounce".equals(kind)
                || "fbl_complaint".equals(kind)) {
            String targetState = "fbl_complaint".equals(kind) ? "COMPLAINT" : "BOUNCED";
            jdbc.update("""
                UPDATE creator_studio_retention_notices n
                   SET n.delivery_state=?,
                       n.delivery_provider=?,
                       n.delivery_event_ref=?,
                       n.delivery_evidence_at=NULL
                 WHERE n.id=? AND n.status='SENT'
                   AND n.delivery_state<>'COMPLAINT'
                   AND n.recipient_email=?
                """, targetState, PROVIDER, eventRef, noticeId, destination);
        }
        return ResponseEntity.ok().build();
    }

    private void verify(String header, byte[] body) {
        if (secret == null || secret.isBlank() || header == null || body == null ||
            body.length==0 || body.length>MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook not authorized");
        }
        Map<String,String> fields=Arrays.stream(header.split(";"))
            .map(String::trim)
            .filter(s -> s.contains("="))
            .map(s -> s.split("=",2))
            .collect(Collectors.toMap(p -> p[0],p -> p[1],(a,b)->a));
        if (!"HmacSHA256".equals(fields.get("s-algorithm"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid signature algorithm");
        }
        try {
            long ts=Long.parseLong(fields.getOrDefault("ts",""));
            long diff=System.currentTimeMillis()-ts;
            if (diff > MAX_SKEW.toMillis() || diff < -MAX_SKEW.toMillis()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Expired signature");
            }
            byte[] sent=Base64.getDecoder().decode(
                URLDecoder.decode(fields.getOrDefault("s",""),StandardCharsets.UTF_8));
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] expected=mac.doFinal(body);
            if (!MessageDigest.isEqual(expected,sent)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid signature");
            }
        } catch (ResponseStatusException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid webhook authentication");
        }
    }
}
