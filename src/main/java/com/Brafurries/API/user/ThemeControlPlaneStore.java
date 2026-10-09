package com.Brafurries.API.user;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ThemeControlPlaneStore {
    private final JdbcTemplate jdbc;

    public ThemeControlPlaneStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ThemeStored> listThemes(long guildId) {
        return jdbc.query("""
            SELECT id,guild_id,name,description,
                   icon_action,icon_asset_key,icon_asset_content_type,icon_asset_sha256,icon_asset_size_bytes,
                   banner_action,banner_asset_key,banner_asset_content_type,banner_asset_sha256,banner_asset_size_bytes,
                   created_by_discord_user_id,created_by_user_id,deleted_at,created_at,updated_at
            FROM discord_themes
            WHERE guild_id=? AND deleted_at IS NULL
            ORDER BY updated_at DESC,id DESC
            """, (rs,row) -> mapTheme(rs), guildId);
    }

    public ThemeStored findTheme(long guildId, long themeId) {
        List<ThemeStored> values = jdbc.query("""
            SELECT id,guild_id,name,description,
                   icon_action,icon_asset_key,icon_asset_content_type,icon_asset_sha256,icon_asset_size_bytes,
                   banner_action,banner_asset_key,banner_asset_content_type,banner_asset_sha256,banner_asset_size_bytes,
                   created_by_discord_user_id,created_by_user_id,deleted_at,created_at,updated_at
            FROM discord_themes
            WHERE guild_id=? AND id=? AND deleted_at IS NULL
            LIMIT 1
            """, (rs,row) -> mapTheme(rs), guildId, themeId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public ThemeStored findThemeIncludingDeleted(long guildId, long themeId) {
        List<ThemeStored> values = jdbc.query("""
            SELECT id,guild_id,name,description,
                   icon_action,icon_asset_key,icon_asset_content_type,icon_asset_sha256,icon_asset_size_bytes,
                   banner_action,banner_asset_key,banner_asset_content_type,banner_asset_sha256,banner_asset_size_bytes,
                   created_by_discord_user_id,created_by_user_id,deleted_at,created_at,updated_at
            FROM discord_themes
            WHERE guild_id=? AND id=?
            LIMIT 1
            """, (rs,row) -> mapTheme(rs), guildId, themeId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public List<ResourceChangeStored> listResourceChanges(long themeId) {
        return jdbc.query("""
            SELECT id,theme_id,resource_type,resource_discord_id,target_name
            FROM discord_theme_resource_changes
            WHERE theme_id=?
            ORDER BY resource_type,resource_discord_id,id
            """, (rs,row) -> new ResourceChangeStored(
                rs.getLong("id"),
                rs.getLong("theme_id"),
                rs.getString("resource_type"),
                rs.getLong("resource_discord_id"),
                rs.getString("target_name")
            ), themeId);
    }

    @Transactional
    public long createTheme(
        long guildId,
        String name,
        String description,
        long createdByDiscordUserId,
        Integer createdByUserId,
        String iconAction,
        String bannerAction,
        List<ResourceWrite> resources
    ) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO discord_themes
                    (guild_id,name,description,icon_action,banner_action,
                     created_by_discord_user_id,created_by_user_id)
                VALUES (?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, guildId);
            statement.setString(2, name);
            statement.setString(3, description);
            statement.setString(4, iconAction);
            statement.setString(5, bannerAction);
            statement.setLong(6, createdByDiscordUserId);
            if (createdByUserId == null) statement.setNull(7, java.sql.Types.INTEGER);
            else statement.setInt(7, createdByUserId);
            return statement;
        }, keys);
        long themeId = keys.getKey().longValue();
        replaceResources(themeId, resources);
        return themeId;
    }

    @Transactional
    public boolean updateTheme(
        long guildId,
        long themeId,
        String name,
        String description,
        String iconAction,
        String bannerAction,
        List<ResourceWrite> resources
    ) {
        int updated = jdbc.update("""
            UPDATE discord_themes
            SET name=?,description=?,icon_action=?,banner_action=?,
                icon_asset_key=CASE WHEN ?='SET' THEN icon_asset_key ELSE NULL END,
                icon_asset_content_type=CASE WHEN ?='SET' THEN icon_asset_content_type ELSE NULL END,
                icon_asset_sha256=CASE WHEN ?='SET' THEN icon_asset_sha256 ELSE NULL END,
                icon_asset_size_bytes=CASE WHEN ?='SET' THEN icon_asset_size_bytes ELSE NULL END,
                banner_asset_key=CASE WHEN ?='SET' THEN banner_asset_key ELSE NULL END,
                banner_asset_content_type=CASE WHEN ?='SET' THEN banner_asset_content_type ELSE NULL END,
                banner_asset_sha256=CASE WHEN ?='SET' THEN banner_asset_sha256 ELSE NULL END,
                banner_asset_size_bytes=CASE WHEN ?='SET' THEN banner_asset_size_bytes ELSE NULL END
            WHERE id=? AND guild_id=? AND deleted_at IS NULL
            """,
            name, description, iconAction, bannerAction,
            iconAction, iconAction, iconAction, iconAction,
            bannerAction, bannerAction, bannerAction, bannerAction,
            themeId, guildId
        );
        if (updated != 1) return false;
        replaceResources(themeId, resources);
        return true;
    }

    @Transactional
    public void replaceResources(long themeId, List<ResourceWrite> resources) {
        jdbc.update("DELETE FROM discord_theme_resource_changes WHERE theme_id=?", themeId);
        for (ResourceWrite resource : resources) {
            jdbc.update("""
                INSERT INTO discord_theme_resource_changes
                    (theme_id,resource_type,resource_discord_id,target_name)
                VALUES (?,?,?,?)
                """, themeId, resource.resourceType(), resource.resourceDiscordId(), resource.targetName());
        }
    }

    public long recordThemeAsset(
        long guildId,
        long themeId,
        String assetType,
        String key,
        String contentType,
        String sha256,
        long sizeBytes
    ) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO discord_theme_assets
                    (theme_id,guild_id,asset_type,storage_key,content_type,sha256,size_bytes)
                SELECT id,guild_id,?,?,?,?,?
                FROM discord_themes
                WHERE id=? AND guild_id=? AND deleted_at IS NULL
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, assetType);
            statement.setString(2, key);
            statement.setString(3, contentType);
            statement.setString(4, sha256);
            statement.setLong(5, sizeBytes);
            statement.setLong(6, themeId);
            statement.setLong(7, guildId);
            return statement;
        }, keys);
        Number keyValue = keys.getKey();
        return keyValue == null ? 0L : keyValue.longValue();
    }

    public List<ThemeAssetStored> listThemeAssets(long guildId, long themeId) {
        return jdbc.query("""
            SELECT id,theme_id,guild_id,asset_type,storage_key,content_type,sha256,size_bytes,created_at
            FROM discord_theme_assets
            WHERE guild_id=? AND theme_id=?
            ORDER BY id
            """, (rs,row) -> new ThemeAssetStored(
                rs.getLong("id"),
                rs.getLong("theme_id"),
                rs.getLong("guild_id"),
                rs.getString("asset_type"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getString("sha256"),
                rs.getLong("size_bytes"),
                instant(rs.getTimestamp("created_at"))
            ), guildId, themeId);
    }

    public void deleteThemeAssets(long guildId, long themeId) {
        jdbc.update(
            "DELETE FROM discord_theme_assets WHERE guild_id=? AND theme_id=?",
            guildId,
            themeId
        );
    }

    public boolean deleteThemeAsset(long guildId, long themeId, long assetId) {
        return jdbc.update(
            "DELETE FROM discord_theme_assets WHERE id=? AND guild_id=? AND theme_id=?",
            assetId,
            guildId,
            themeId
        ) == 1;
    }

    public boolean setThemeAsset(
        long guildId,
        long themeId,
        String assetType,
        String key,
        String contentType,
        String sha256,
        long sizeBytes
    ) {
        String prefix = "ICON".equals(assetType) ? "icon" : "banner";
        return jdbc.update("""
            UPDATE discord_themes
            SET %s_action='SET',
                %s_asset_key=?,
                %s_asset_content_type=?,
                %s_asset_sha256=?,
                %s_asset_size_bytes=?
            WHERE id=? AND guild_id=? AND deleted_at IS NULL
            """.formatted(prefix,prefix,prefix,prefix,prefix),
            key, contentType, sha256, sizeBytes, themeId, guildId
        ) == 1;
    }

    public boolean clearThemeAsset(long guildId, long themeId, String assetType, String action) {
        String prefix = "ICON".equals(assetType) ? "icon" : "banner";
        return jdbc.update("""
            UPDATE discord_themes
            SET %s_action=?,
                %s_asset_key=NULL,
                %s_asset_content_type=NULL,
                %s_asset_sha256=NULL,
                %s_asset_size_bytes=NULL
            WHERE id=? AND guild_id=? AND deleted_at IS NULL
            """.formatted(prefix,prefix,prefix,prefix,prefix),
            action, themeId, guildId
        ) == 1;
    }

    public boolean softDeleteTheme(long guildId, long themeId) {
        return jdbc.update("""
            UPDATE discord_themes
            SET deleted_at=UTC_TIMESTAMP(6)
            WHERE id=? AND guild_id=? AND deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM discord_theme_active_guilds active
                  JOIN discord_theme_applications app ON app.id=active.application_id
                  WHERE active.guild_id=? AND app.theme_id=?
              )
            """, themeId, guildId, guildId, themeId) == 1;
    }

    public Set<Long> vipCustomRoleIds(long guildId) {
        return Set.copyOf(jdbc.query("""
            SELECT DISTINCT role_id
            FROM user_custom_roles
            WHERE server_guild_id=? AND role_id IS NOT NULL
            """, (rs,row) -> rs.getLong(1), guildId));
    }

    public ApplicationStored findActiveApplication(long guildId) {
        List<ApplicationStored> values = jdbc.query("""
            SELECT app.id,app.theme_id,app.guild_id,app.actor_discord_user_id,app.actor_user_id,
                   app.status,app.frozen_definition_json,app.applied_at,app.restored_at,
                   app.error_summary,app.rollback_assets_cleaned_at,app.created_at,app.updated_at
            FROM discord_theme_active_guilds active
            JOIN discord_theme_applications app ON app.id=active.application_id
            WHERE active.guild_id=?
            LIMIT 1
            """, (rs,row) -> mapApplication(rs), guildId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public ApplicationStored findApplication(long guildId, long applicationId) {
        List<ApplicationStored> values = jdbc.query("""
            SELECT id,theme_id,guild_id,actor_discord_user_id,actor_user_id,status,
                   frozen_definition_json,applied_at,restored_at,error_summary,rollback_assets_cleaned_at,created_at,updated_at
            FROM discord_theme_applications
            WHERE guild_id=? AND id=?
            LIMIT 1
            """, (rs,row) -> mapApplication(rs), guildId, applicationId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public List<ApplicationStored> listApplications(long guildId) {
        return jdbc.query("""
            SELECT id,theme_id,guild_id,actor_discord_user_id,actor_user_id,status,
                   frozen_definition_json,applied_at,restored_at,error_summary,rollback_assets_cleaned_at,created_at,updated_at
            FROM discord_theme_applications
            WHERE guild_id=?
            ORDER BY id DESC
            LIMIT 50
            """, (rs,row) -> mapApplication(rs), guildId);
    }

    @Transactional
    public OperationStored createApplyApplication(
        long guildId,
        long themeId,
        long actorDiscordUserId,
        Integer actorUserId,
        String operationKey,
        String frozenDefinitionJson
    ) {
        OperationStored existing = findOperationByKey(guildId, operationKey);
        if (existing != null) return existing;

        GeneratedKeyHolder appKeys = new GeneratedKeyHolder();
        int insertedApplication = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO discord_theme_applications
                    (theme_id,guild_id,actor_discord_user_id,actor_user_id,status,frozen_definition_json)
                SELECT id,guild_id,?,?, 'APPLYING', ?
                FROM discord_themes
                WHERE id=? AND guild_id=? AND deleted_at IS NULL
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, actorDiscordUserId);
            if (actorUserId == null) statement.setNull(2, java.sql.Types.INTEGER);
            else statement.setInt(2, actorUserId);
            statement.setString(3, frozenDefinitionJson);
            statement.setLong(4, themeId);
            statement.setLong(5, guildId);
            return statement;
        }, appKeys);
        if (insertedApplication != 1 || appKeys.getKey() == null) return null;
        long applicationId = appKeys.getKey().longValue();

        jdbc.update("""
            INSERT INTO discord_theme_active_guilds (guild_id,application_id)
            VALUES (?,?)
            """, guildId, applicationId);

        long operationId = insertOperation(
            applicationId, guildId, operationKey, "APPLY",
            actorDiscordUserId, actorUserId, false
        );
        return findOperation(guildId, operationId);
    }

    @Transactional
    public OperationStored createRestoreOperation(
        long guildId,
        long applicationId,
        long actorDiscordUserId,
        Integer actorUserId,
        String operationKey,
        boolean force
    ) {
        OperationStored existing = findOperationByKey(guildId, operationKey);
        if (existing != null) return existing;

        ApplicationStored active = findActiveApplication(guildId);
        if (active == null
            || active.id() != applicationId
            || !Set.of("ACTIVE", "APPLY_PARTIAL", "RESTORE_PARTIAL").contains(active.status())) {
            return null;
        }

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO discord_theme_operations
                    (application_id,guild_id,operation_key,operation_type,
                     actor_discord_user_id,actor_user_id,force_restore,status)
                SELECT app.id,app.guild_id,?,'RESTORE',?,?,?,'PENDING'
                FROM discord_theme_active_guilds active
                JOIN discord_theme_applications app
                  ON app.id=active.application_id
                 AND app.guild_id=active.guild_id
                WHERE active.guild_id=?
                  AND active.application_id=?
                  AND app.status IN ('ACTIVE','APPLY_PARTIAL','RESTORE_PARTIAL')
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, operationKey);
            statement.setLong(2, actorDiscordUserId);
            if (actorUserId == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, actorUserId);
            statement.setBoolean(4, force);
            statement.setLong(5, guildId);
            statement.setLong(6, applicationId);
            return statement;
        }, keys);
        if (inserted != 1 || keys.getKey() == null) return null;
        return findOperation(guildId, keys.getKey().longValue());
    }

    private long insertOperation(
        long applicationId,
        long guildId,
        String operationKey,
        String operationType,
        long actorDiscordUserId,
        Integer actorUserId,
        boolean force
    ) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO discord_theme_operations
                    (application_id,guild_id,operation_key,operation_type,
                     actor_discord_user_id,actor_user_id,force_restore,status)
                VALUES (?,?,?,?,?,?,?,'PENDING')
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, applicationId);
            statement.setLong(2, guildId);
            statement.setString(3, operationKey);
            statement.setString(4, operationType);
            statement.setLong(5, actorDiscordUserId);
            if (actorUserId == null) statement.setNull(6, java.sql.Types.INTEGER);
            else statement.setInt(6, actorUserId);
            statement.setBoolean(7, force);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public OperationStored findOperationByKey(long guildId, String operationKey) {
        List<OperationStored> values = jdbc.query("""
            SELECT id,application_id,guild_id,operation_key,operation_type,
                   actor_discord_user_id,actor_user_id,force_restore,status,
                   progress_current,progress_total,current_step,result_json,error_code,
                   requested_audited_at,completion_audited_at,
                   started_at,finished_at,created_at,updated_at
            FROM discord_theme_operations
            WHERE guild_id=? AND operation_key=?
            LIMIT 1
            """, (rs,row) -> mapOperation(rs), guildId, operationKey);
        return values.isEmpty() ? null : values.getFirst();
    }

    public OperationStored findOperation(long guildId, long operationId) {
        List<OperationStored> values = jdbc.query("""
            SELECT id,application_id,guild_id,operation_key,operation_type,
                   actor_discord_user_id,actor_user_id,force_restore,status,
                   progress_current,progress_total,current_step,result_json,error_code,
                   requested_audited_at,completion_audited_at,
                   started_at,finished_at,created_at,updated_at
            FROM discord_theme_operations
            WHERE guild_id=? AND id=?
            LIMIT 1
            """, (rs,row) -> mapOperation(rs), guildId, operationId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public OperationStored latestOperation(long applicationId) {
        List<OperationStored> values = jdbc.query("""
            SELECT id,application_id,guild_id,operation_key,operation_type,
                   actor_discord_user_id,actor_user_id,force_restore,status,
                   progress_current,progress_total,current_step,result_json,error_code,
                   requested_audited_at,completion_audited_at,
                   started_at,finished_at,created_at,updated_at
            FROM discord_theme_operations
            WHERE application_id=?
            ORDER BY id DESC
            LIMIT 1
            """, (rs,row) -> mapOperation(rs), applicationId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public boolean claimOperationRequestedAudit(long guildId, long operationId) {
        return jdbc.update("""
            UPDATE discord_theme_operations
            SET requested_audited_at=UTC_TIMESTAMP(6)
            WHERE id=? AND guild_id=? AND requested_audited_at IS NULL
            """, operationId, guildId) == 1;
    }

    public boolean claimOperationCompletionAudit(long guildId, long operationId) {
        return jdbc.update("""
            UPDATE discord_theme_operations
            SET completion_audited_at=UTC_TIMESTAMP(6)
            WHERE id=? AND guild_id=?
              AND status IN ('SUCCEEDED','PARTIAL','FAILED')
              AND completion_audited_at IS NULL
            """, operationId, guildId) == 1;
    }

    public List<RollbackAssetStored> listRollbackAssets(long guildId, long applicationId) {
        return jdbc.query("""
            SELECT id,application_id,resource_type,before_asset_key,before_asset_content_type,
                   before_asset_sha256
            FROM discord_theme_application_snapshots
            WHERE application_id=?
              AND before_asset_key IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM discord_theme_applications app
                  WHERE app.id=discord_theme_application_snapshots.application_id
                    AND app.guild_id=?
              )
            ORDER BY id
            """, (rs,row) -> new RollbackAssetStored(
                rs.getLong("id"),
                rs.getLong("application_id"),
                rs.getString("resource_type"),
                rs.getString("before_asset_key"),
                rs.getString("before_asset_content_type"),
                rs.getString("before_asset_sha256")
            ), applicationId, guildId);
    }

    public RollbackAssetStored findRollbackAsset(long guildId, long applicationId, String resourceType) {
        List<RollbackAssetStored> values = jdbc.query("""
            SELECT snap.id,snap.application_id,snap.resource_type,snap.before_asset_key,
                   snap.before_asset_content_type,snap.before_asset_sha256
            FROM discord_theme_application_snapshots snap
            JOIN discord_theme_applications app ON app.id=snap.application_id
            WHERE app.guild_id=? AND app.id=? AND snap.resource_type=?
              AND snap.before_asset_key IS NOT NULL
            LIMIT 1
            """, (rs,row) -> new RollbackAssetStored(
                rs.getLong("id"),
                rs.getLong("application_id"),
                rs.getString("resource_type"),
                rs.getString("before_asset_key"),
                rs.getString("before_asset_content_type"),
                rs.getString("before_asset_sha256")
            ), guildId, applicationId, resourceType);
        return values.isEmpty() ? null : values.getFirst();
    }

    public boolean hasAppliedSnapshots(long guildId, long applicationId) {
        Boolean applied = jdbc.query(
            """
            SELECT EXISTS(
                SELECT 1
                FROM discord_theme_application_snapshots snap
                JOIN discord_theme_applications app ON app.id=snap.application_id
                WHERE app.guild_id=?
                  AND app.id=?
                  AND snap.apply_status='APPLIED'
            ) AS applied
            """,
            rs -> rs.next() && rs.getBoolean("applied"),
            guildId,
            applicationId
        );
        return Boolean.TRUE.equals(applied);
    }

    public boolean markRollbackAssetsCleaned(long guildId, long applicationId) {
        return jdbc.update("""
            UPDATE discord_theme_applications app
            SET rollback_assets_cleaned_at=COALESCE(rollback_assets_cleaned_at,UTC_TIMESTAMP(6))
            WHERE app.id=? AND app.guild_id=?
              AND (
                  app.status='RESTORED'
                  OR (
                      app.status='FAILED'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM discord_theme_application_snapshots snap
                          WHERE snap.application_id=app.id
                            AND snap.apply_status='APPLIED'
                      )
                  )
              )
            """, applicationId, guildId) == 1;
    }

    public List<OperationStepStored> listOperationSteps(long guildId, long operationId) {
        return jdbc.query("""
            SELECT id,operation_id,guild_id,step_code,step_status,message,
                   progress_current,progress_total,detail_json,created_at
            FROM discord_theme_operation_steps
            WHERE guild_id=? AND operation_id=?
            ORDER BY id
            """, (rs,row) -> new OperationStepStored(
                rs.getLong("id"),
                rs.getLong("operation_id"),
                rs.getLong("guild_id"),
                rs.getString("step_code"),
                rs.getString("step_status"),
                rs.getString("message"),
                rs.getInt("progress_current"),
                rs.getInt("progress_total"),
                rs.getString("detail_json"),
                instant(rs.getTimestamp("created_at"))
            ), guildId, operationId);
    }

    private ThemeStored mapTheme(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThemeStored(
            rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"),
            rs.getString("description"), rs.getString("icon_action"),
            rs.getString("icon_asset_key"), rs.getString("icon_asset_content_type"),
            rs.getString("icon_asset_sha256"), nullableLong(rs,"icon_asset_size_bytes"),
            rs.getString("banner_action"), rs.getString("banner_asset_key"),
            rs.getString("banner_asset_content_type"), rs.getString("banner_asset_sha256"),
            nullableLong(rs,"banner_asset_size_bytes"),
            rs.getLong("created_by_discord_user_id"),
            nullableInt(rs,"created_by_user_id"),
            instant(rs.getTimestamp("deleted_at")),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at"))
        );
    }

    private ApplicationStored mapApplication(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApplicationStored(
            rs.getLong("id"), rs.getLong("theme_id"), rs.getLong("guild_id"),
            rs.getLong("actor_discord_user_id"), nullableInt(rs,"actor_user_id"),
            rs.getString("status"), rs.getString("frozen_definition_json"),
            instant(rs.getTimestamp("applied_at")), instant(rs.getTimestamp("restored_at")),
            rs.getString("error_summary"),
            instant(rs.getTimestamp("rollback_assets_cleaned_at")),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at"))
        );
    }

    private OperationStored mapOperation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OperationStored(
            rs.getLong("id"), rs.getLong("application_id"), rs.getLong("guild_id"),
            rs.getString("operation_key"), rs.getString("operation_type"),
            rs.getLong("actor_discord_user_id"), nullableInt(rs,"actor_user_id"),
            rs.getBoolean("force_restore"), rs.getString("status"),
            rs.getInt("progress_current"), rs.getInt("progress_total"),
            rs.getString("current_step"), rs.getString("result_json"),
            rs.getString("error_code"),
            instant(rs.getTimestamp("requested_audited_at")),
            instant(rs.getTimestamp("completion_audited_at")),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Object value = rs.getObject(name);
        return value == null ? null : ((Number)value).intValue();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Object value = rs.getObject(name);
        return value == null ? null : ((Number)value).longValue();
    }

    public record ThemeStored(
        long id,long guildId,String name,String description,
        String iconAction,String iconAssetKey,String iconAssetContentType,String iconAssetSha256,Long iconAssetSizeBytes,
        String bannerAction,String bannerAssetKey,String bannerAssetContentType,String bannerAssetSha256,Long bannerAssetSizeBytes,
        long createdByDiscordUserId,Integer createdByUserId,Instant deletedAt,Instant createdAt,Instant updatedAt
    ) {}
    public record ThemeAssetStored(
        long id,long themeId,long guildId,String assetType,String storageKey,
        String contentType,String sha256,long sizeBytes,Instant createdAt
    ) {}
    public record ResourceChangeStored(long id,long themeId,String resourceType,long resourceDiscordId,String targetName) {}
    public record ResourceWrite(String resourceType,long resourceDiscordId,String targetName) {}
    public record ApplicationStored(
        long id,long themeId,long guildId,long actorDiscordUserId,Integer actorUserId,
        String status,String frozenDefinitionJson,Instant appliedAt,Instant restoredAt,
        String errorSummary,Instant rollbackAssetsCleanedAt,Instant createdAt,Instant updatedAt
    ) {}
    public record OperationStored(
        long id,long applicationId,long guildId,String operationKey,String operationType,
        long actorDiscordUserId,Integer actorUserId,boolean forceRestore,String status,
        int progressCurrent,int progressTotal,String currentStep,String resultJson,String errorCode,
        Instant requestedAuditedAt,Instant completionAuditedAt,
        Instant startedAt,Instant finishedAt,Instant createdAt,Instant updatedAt
    ) {}
    public record OperationStepStored(
        long id,long operationId,long guildId,String code,String status,String message,
        int progressCurrent,int progressTotal,String detailJson,Instant createdAt
    ) {}
    public record RollbackAssetStored(
        long id,long applicationId,String resourceType,String key,String contentType,String sha256
    ) {}
}
