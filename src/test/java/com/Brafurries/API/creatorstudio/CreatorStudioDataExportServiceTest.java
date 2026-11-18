package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CreatorStudioDataExportServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    ObjectMapper mapper = new ObjectMapper();
    CreatorStudioDataExportService service;

    @BeforeEach void init() {
        service = new CreatorStudioDataExportService(access,jdbc,mapper);
    }

    @Test
    void streamsPrivateArchiveForOwnerEvenWhenTheirProEntitlementWasRevoked() throws Exception {
        when(access.actorId(auth)).thenReturn(31);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM creator_studio_commissions")) {
                return List.of(Map.of("id",101L,"title","Rascunho privado"));
            }
            return List.of();
        }).when(jdbc).queryForList(anyString(),any(Object[].class));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        service.export(auth).writeTo(bytes);
        JsonNode root = mapper.readTree(bytes.toByteArray());
        assertEquals(1,root.get("schemaVersion").asInt());
        assertEquals("Rascunho privado",root.get("commissions").get(0).get("title").asText());
        assertTrue(root.get("commissions").get(0).get("steps").isArray());
        assertTrue(root.get("commissions").get(0).get("references").isArray());
        assertTrue(root.get("commissions").get(0).get("activity").isArray());
        assertTrue(root.get("catalog").get("workflows").isArray());
        verify(access).actorId(auth);
        verify(access,never()).requirePro(any());
        verify(jdbc).queryForList(contains("FROM creator_studio_commissions"),
            eq(31),eq(0L));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void streamingBodyDoesNotReadAnotherArtistsCommissions() throws Exception {
        when(access.actorId(auth)).thenReturn(88);
        service.export(auth).writeTo(new ByteArrayOutputStream());
        verify(jdbc).queryForList(argThat(sql ->
            sql.contains("WHERE owner_user_id=? AND id>?")
            && sql.contains("ORDER BY id LIMIT 100")),eq(88),eq(0L));
    }
}
