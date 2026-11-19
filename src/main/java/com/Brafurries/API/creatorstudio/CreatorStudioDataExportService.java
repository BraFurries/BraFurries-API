package com.Brafurries.API.creatorstudio;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Access/export rights survive entitlement revocation, provided the user can
 * authenticate into their canonical account. Never accepts an owner ID.
 * No client files are fetched; references remain URLs in the JSON archive.
 */
@Service
public class CreatorStudioDataExportService {
    private static final int PAGE_SIZE = 100;
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CreatorStudioDataExportService(CreatorStudioAccessService access,
                                         JdbcTemplate jdbc, ObjectMapper mapper) {
        this.access = access;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public StreamingResponseBody export(Authentication auth) {
        // Authorization occurs before response headers are sent, not in the stream.
        int userId = access.actorId(auth);
        return output -> {
            try (JsonGenerator json = mapper.getFactory().createGenerator(output)) {
                json.writeStartObject();
                json.writeNumberField("schemaVersion", 1);
                json.writeStringField("exportedAtUtc", LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
                json.writeStringField("scope", "current-account-creator-studio");
                json.writeFieldName("catalog");
                json.writeStartObject();
                writeDataset(json, "artTypes", """
                    SELECT id,name,workflow_id,created_at FROM creator_studio_art_types
                     WHERE owner_user_id=? ORDER BY id
                    """, userId);
                writeDataset(json, "stageDefinitions", """
                    SELECT id,name,created_at FROM creator_studio_stage_definitions
                     WHERE owner_user_id=? ORDER BY id
                    """, userId);
                writeDataset(json, "workflows", """
                    SELECT id,name,is_default,created_at,updated_at
                      FROM creator_studio_workflows WHERE owner_user_id=? ORDER BY id
                    """, userId);
                writeDataset(json, "workflowSteps", """
                    SELECT id,workflow_id,stage_definition_id,name,position
                      FROM creator_studio_workflow_steps WHERE owner_user_id=?
                     ORDER BY workflow_id,position,id
                    """, userId);
                json.writeEndObject();

                json.writeArrayFieldStart("commissions");
                long lastId = 0;
                while (true) {
                    List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT id,art_type_id,title,client_name,description,
                               private_notes,requested_on,due_on,planned_on,status,
                               created_at,updated_at,completed_at
                          FROM creator_studio_commissions
                         WHERE owner_user_id=? AND id>?
                         ORDER BY id LIMIT 100
                        """, userId, lastId);
                    if (rows.isEmpty()) break;
                    for (Map<String, Object> row : rows) {
                        long commissionId = ((Number) row.get("id")).longValue();
                        json.writeStartObject();
                        for (var entry : row.entrySet()) {
                            json.writeFieldName(entry.getKey());
                            json.writeObject(exportValue(entry.getValue()));
                        }
                        writeDataset(json, "steps", """
                            SELECT id,stage_definition_id,name,position,status,notes,completed_at,planned_on
                              FROM creator_studio_commission_steps
                             WHERE owner_user_id=? AND commission_id=?
                             ORDER BY position,id
                            """, userId, commissionId);
                        writeDataset(json, "references", """
                            SELECT id,label,url,position FROM creator_studio_reference_links
                             WHERE owner_user_id=? AND commission_id=?
                             ORDER BY position,id
                            """, userId, commissionId);
                        writeDataset(json, "activity", """
                            SELECT id,event_kind,description,occurred_on,created_at
                              FROM creator_studio_activity
                             WHERE owner_user_id=? AND commission_id=?
                             ORDER BY occurred_on,id
                            """, userId, commissionId);
                        json.writeEndObject();
                        lastId = commissionId;
                    }
                    if (rows.size() < PAGE_SIZE) break;
                }
                json.writeEndArray();
                json.writeEndObject();
            }
        };
    }

    private void writeDataset(JsonGenerator json, String name, String sql, Object... params)
        throws IOException {
        json.writeArrayFieldStart(name);
        // Each query is owner scoped, and no query is left open while another runs.
        for (Map<String, Object> row : jdbc.queryForList(sql, params)) {
            json.writeStartObject();
            for (var entry : row.entrySet()) {
                json.writeFieldName(entry.getKey());
                json.writeObject(exportValue(entry.getValue()));
            }
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private static Object exportValue(Object value) {
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof Timestamp timestamp)
            return timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        if (value instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
        return value;
    }
}
