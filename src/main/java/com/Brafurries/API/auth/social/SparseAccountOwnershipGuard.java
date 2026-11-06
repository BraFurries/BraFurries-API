package com.Brafurries.API.auth.social;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fail-closed transfer whitelist for a sparse web user.
 *
 * Only OAuth subjects, temporary login credentials and the proof challenge
 * may be owned by the absorbed user. Any other user reference (including
 * audit authorship) must be reviewed before a merge is permitted.
 * Table/column identifiers are discovered from the live Database schema,
 * validated and never supplied by a client.
 */
@Component
public class SparseAccountOwnershipGuard {
    private static final int MAX_REFERENCE_COLUMNS = 400;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> ALLOWED = Set.of(
        "user_social_identities.user_id",
        "user_refresh_tokens.user_id",
        "user_tokens.user_id",
        "user_discord_link_challenges.requesting_user_id"
    );
    private static final String REFERENCES = """
        SELECT c.TABLE_NAME AS table_name, c.COLUMN_NAME AS column_name,
               EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE fk
                      WHERE fk.TABLE_SCHEMA = c.TABLE_SCHEMA
                        AND fk.TABLE_NAME = c.TABLE_NAME
                        AND fk.COLUMN_NAME = c.COLUMN_NAME
                        AND fk.REFERENCED_TABLE_SCHEMA = DATABASE()
                        AND fk.REFERENCED_TABLE_NAME = 'users'
                        AND fk.REFERENCED_COLUMN_NAME = 'id') AS has_user_fk
        FROM information_schema.COLUMNS c
        JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA
             AND t.TABLE_NAME=c.TABLE_NAME
        WHERE c.TABLE_SCHEMA=DATABASE() AND t.TABLE_TYPE='BASE TABLE'
          AND (c.COLUMN_NAME='user_id'
               OR RIGHT(c.COLUMN_NAME, 8)='_user_id'
               OR EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE fk
                         WHERE fk.TABLE_SCHEMA=c.TABLE_SCHEMA
                           AND fk.TABLE_NAME=c.TABLE_NAME
                           AND fk.COLUMN_NAME=c.COLUMN_NAME
                           AND fk.REFERENCED_TABLE_SCHEMA=DATABASE()
                           AND fk.REFERENCED_TABLE_NAME='users'
                           AND fk.REFERENCED_COLUMN_NAME='id'))
          -- OAuth account IDs are INT users.id. These columns store IDs from
          -- external networks (or user_discord.id), not users.id.
          AND c.COLUMN_NAME NOT IN ('discord_user_id', 'patreon_user_id',
                  'telegram_user_id', 'disc_user_id')
          AND RIGHT(c.COLUMN_NAME, 16) <> '_discord_user_id'
          AND NOT (c.TABLE_NAME IN ('form_submissions', 'portaria_account_release')
                   AND c.COLUMN_NAME='user_id')
        ORDER BY c.TABLE_NAME, c.COLUMN_NAME
        """;

    private final JdbcTemplate jdbc;

    public SparseAccountOwnershipGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void assertOnlyTransferableReferences(Integer absorbedUserId) {
        if (absorbedUserId == null || absorbedUserId <= 0) {
            throw new IllegalArgumentException("Invalid absorbed user");
        }
        // This assertion is transaction-scoped. SERIALIZABLE + FOR UPDATE
        // locking reads also protect FK-less legacy references from a phantom
        // insert while the user row is being absorbed. If the caller has a
        // weaker isolation level, no ownership transfer is allowed.
        String isolation = jdbc.queryForObject("SELECT @@tx_isolation", String.class);
        if (!"SERIALIZABLE".equalsIgnoreCase(isolation)) throw blocked();

        List<Map<String, Object>> refs = jdbc.queryForList(REFERENCES);
        if (refs.isEmpty() || refs.size() > MAX_REFERENCE_COLUMNS) {
            throw blocked();
        }
        for (Map<String, Object> ref : refs) {
            if (!(ref.get("table_name") instanceof String table)
                || !(ref.get("column_name") instanceof String column)
                || !IDENTIFIER.matcher(table).matches()
                || !IDENTIFIER.matcher(column).matches()) {
                throw blocked();
            }

            String name = table + "." + column;
            if (ALLOWED.contains(name)) {
                // Only known, Database-FK-backed references may be transferred
                // or revoked by the executor.
                if (!hasParentFk(ref.get("has_user_fk"))) throw blocked();
                continue;
            }
            // An indexed equality query only locks its narrow key range.
            // FK-less unindexed legacy fields can need wider locks; this
            // remains an explicit user-initiated operation, not background DML.
            String sql = "SELECT COUNT(*) FROM `" + table + "` WHERE `"
                + column + "`=? FOR UPDATE";
            Long present = jdbc.queryForObject(sql, Long.class, absorbedUserId);
            if (present == null || present != 0L) {
                throw blocked();
            }
        }
    }

    private boolean hasParentFk(Object raw) { return Boolean.TRUE.equals(raw) || (raw instanceof Number n && n.intValue() == 1); }

    private ResponseStatusException blocked() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "ACCOUNT_DATA_RECONCILIATION_REQUIRED");
    }
}
