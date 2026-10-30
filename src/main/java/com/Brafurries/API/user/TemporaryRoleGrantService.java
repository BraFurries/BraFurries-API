package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The API controls a tenant-scoped desired state; Coddy alone changes Discord.
 * This table is deliberately independent of legacy user_temp_roles rewards.
 */
@Service
public class TemporaryRoleGrantService {
    private final GuildManagementAccessService guildAccess;
    private final CommunityAuthorizationService authorization;
    private final CommunityDiscordRepository links;
    private final CommunityAuditLogRepository audits;
    private final JdbcTemplate jdbc;

    public TemporaryRoleGrantService(GuildManagementAccessService guildAccess,
        CommunityAuthorizationService authorization, CommunityDiscordRepository links,
        CommunityAuditLogRepository audits, JdbcTemplate jdbc) {
        this.guildAccess=guildAccess;this.authorization=authorization;
        this.links=links;this.audits=audits;this.jdbc=jdbc;
    }

    public record Grant(long id,String guildId,String discordUserId,String roleId,
        String grantSource,Instant expiresAt,String reason,String status,String syncStatus,
        String lastErrorCode,boolean roleAddedByBot,long version,
        Instant createdAt,Instant appliedAt) {}
    public record Create(String requestId,String discordUserId,String roleId,
        Instant expiresAt,String reason) {}
    public record Renew(long expectedVersion,Instant expiresAt,String reason) {}

    private record Access(int communityId,long guildId,
        CommunityAuthorizationService.CommunityAccessContext actor,
        GuildResources resources) {}

    private Access access(Authentication auth,String guildId) {
        AuthorizedGuild guild=guildAccess.authorize(auth,guildId);
        long id;
        try {id=Long.parseLong(guildId);} catch(NumberFormatException ex){throw missing();}
        var link=links.findByGuildId(id).orElseThrow(this::missing);
        if(!Boolean.TRUE.equals(link.getActive())) throw missing();
        var actor=authorization.requireCapability(auth,link.getCommunity().getId(),
            CommunityCapability.COMMUNITY_ADMIN);
        if(!actor.activeMember()) throw forbidden("Vínculo ativo com a Community obrigatório");
        return new Access(link.getCommunity().getId(),id,actor,guild.resources());
    }

    private static Instant instant(java.sql.ResultSet rs,String key) throws java.sql.SQLException {
        LocalDateTime value=rs.getObject(key,LocalDateTime.class);
        return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value,ZoneOffset.UTC);
    }
    private Grant row(java.sql.ResultSet rs,int ignored) throws java.sql.SQLException {
        return new Grant(rs.getLong("id"),String.valueOf(rs.getLong("guild_id")),
            String.valueOf(rs.getLong("discord_user_id")),String.valueOf(rs.getLong("role_id")),
            rs.getString("grant_source"),instant(rs,"expires_at"),rs.getString("reason"),rs.getString("status"),
            rs.getString("sync_status"),rs.getString("last_error_code"),
            rs.getBoolean("role_added_by_bot"),rs.getLong("version"),
            instant(rs,"created_at"),instant(rs,"applied_at"));
    }
    private static final String COLUMNS =
        "id,guild_id,discord_user_id,role_id,grant_source,expiring_date AS expires_at,reason,status,sync_status,"+
        "last_error_code,role_added_by_bot,version,created_at,applied_at";

    @Transactional(readOnly=true)
    public List<Grant> list(Authentication auth,String guildId) {
        var a=access(auth,guildId);
        return jdbc.query("SELECT "+COLUMNS+" FROM user_temp_roles "+
                "WHERE community_id=? AND guild_id=? ORDER BY id DESC LIMIT 100",
            this::row,a.communityId(),a.guildId());
    }

    private Grant get(Access a,long id) {
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM user_temp_roles "+
                "WHERE community_id=? AND guild_id=? AND id=?",
            this::row,a.communityId(),a.guildId(),id);
        if(rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    private static long snowflake(String value) {
        if(value==null || !value.matches("[1-9]\\d{0,18}")) throw invalid("ID Discord inválido");
        try {return Long.parseLong(value);} catch(NumberFormatException ex) {
            throw invalid("ID Discord inválido");
        }
    }
    private static String reason(String value) {
        String s=value==null?"":value.trim();
        if(s.length()<10 || s.length()>200) throw invalid("Justificativa de 10 a 200 caracteres obrigatória");
        return s;
    }
    private static LocalDateTime expiry(Instant value) {
        Instant now=Instant.now();
        if(value==null || !value.isAfter(now) || value.isAfter(now.plusSeconds(366L*86400)))
            throw invalid("O prazo deve estar no futuro e ser de até 366 dias");
        return utc(value.truncatedTo(ChronoUnit.MICROS));
    }
    private static void roleAllowed(Access a,String roleId) {
        if(a.resources().botCapabilities()==null ||
           !a.resources().botCapabilities().canManageRoles())
            throw conflict("Coddy não pode gerenciar cargos neste servidor");
        boolean ok=a.resources().roles()!=null && a.resources().roles().stream()
            .anyMatch(role->Objects.equals(role.id(),roleId) &&
                role.editableByBot() && !role.managed());
        if(!ok) throw invalid("Cargo desconhecido ou acima da hierarquia do Coddy");
    }

    @Transactional
    public Grant create(Authentication auth,String guildId,Create request) {
        var a=access(auth,guildId);
        if(request==null) throw invalid("Dados da concessão obrigatórios");
        String uuid;
        try {uuid=UUID.fromString(request.requestId()).toString();}
        catch(Exception ex){throw invalid("Identificador idempotente UUID obrigatório");}
        if(!uuid.equals(request.requestId())) throw invalid("UUID não canônico");
        long user=snowflake(request.discordUserId());
        long role=snowflake(request.roleId());
        String reason=reason(request.reason());
        LocalDateTime expires=expiry(request.expiresAt());
        roleAllowed(a,request.roleId());
        var existing=jdbc.query("SELECT "+COLUMNS+" FROM user_temp_roles "+
            "WHERE community_id=? AND request_id=?",this::row,a.communityId(),uuid);
        if(!existing.isEmpty()) {
            Grant found=existing.get(0);
            if(!found.guildId().equals(guildId) || !found.discordUserId().equals(request.discordUserId())
                || !found.roleId().equals(request.roleId()) ||
                !found.reason().equals(reason) || !found.expiresAt().equals(request.expiresAt().truncatedTo(ChronoUnit.MICROS)))
                throw conflict("Identificador de solicitação já utilizado com outros dados");
            return found;
        }
        // Release a timed-out unique active slot; Coddy will reconcile the old
        // Discord effect and preserve any newer active grant for this target.
        jdbc.update("UPDATE user_temp_roles SET status='EXPIRED',"+
            "sync_status='PENDING',version=version+1 WHERE community_id=? AND guild_id=? "+
            "AND discord_user_id=? AND role_id=? AND grant_source='MANUAL' AND status='ACTIVE' "+
            "AND expires_at<=UTC_TIMESTAMP(6)",a.communityId(),a.guildId(),user,role);
        var keys=new GeneratedKeyHolder();
        try {
            jdbc.update(conn->{
                var ps=conn.prepareStatement("INSERT INTO user_temp_roles "+
                    "(disc_community_id,disc_user_id,community_id,guild_id,discord_user_id,role_id,"+
                    "expiring_date,reason,request_id,grant_source,sync_status,"+
                    "created_by_user_id,updated_by_user_id) VALUES ("+
                    "(SELECT id FROM community_discord WHERE community_id=? AND guild_id=? AND active=1),"+
                    "NULL,?,?,?,?,?,?,?,'MANUAL','PENDING',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1,a.communityId());ps.setLong(2,a.guildId());
                ps.setInt(3,a.communityId());ps.setLong(4,a.guildId());
                ps.setLong(5,user);ps.setLong(6,role);
                ps.setObject(7,expires);ps.setString(8,reason);ps.setString(9,uuid);
                ps.setInt(10,a.actor().user().getId());ps.setInt(11,a.actor().user().getId());
                return ps;
            },keys);
        } catch(DataIntegrityViolationException ex) {throw conflict("Concessão já existe ou solicitação duplicada");}
        long id=Objects.requireNonNull(keys.getKey()).longValue();
        audit(a,"TEMPORARY_ROLE_GRANTED",id);
        return get(a,id);
    }

    @Transactional
    public Grant renew(Authentication auth,String guildId,long grantId,Renew request) {
        var a=access(auth,guildId);
        if(grantId<=0 || request==null) throw invalid("Concessão inválida");
        Grant old=get(a,grantId);
        if(!"MANUAL".equals(old.grantSource())) throw conflict("Recompensas automáticas não são administradas por este formulário");
        roleAllowed(a,old.roleId());
        LocalDateTime expires=expiry(request.expiresAt());
        String reason=reason(request.reason());
        if(!"ACTIVE".equals(old.status()) || !old.expiresAt().isAfter(Instant.now()))
            throw conflict("Concessão não está ativa");
        if(!request.expiresAt().isAfter(old.expiresAt())) throw invalid("Renovação deve aumentar o prazo");
        if(jdbc.update("UPDATE user_temp_roles SET expiring_date=?,"+
            "reason=?,sync_status='PENDING',last_error_code=NULL,updated_by_user_id=?,"+
            "version=version+1 WHERE community_id=? AND guild_id=? AND id=? "+
            "AND grant_source='MANUAL' AND status='ACTIVE' AND version=? AND expiring_date>UTC_TIMESTAMP(6)",
            expires,reason,a.actor().user().getId(),a.communityId(),a.guildId(),grantId,
            request.expectedVersion())!=1) throw conflict("Concessão alterada; atualize antes de renovar");
        audit(a,"TEMPORARY_ROLE_RENEWED",grantId);
        return get(a,grantId);
    }

    @Transactional
    public Grant cancel(Authentication auth,String guildId,long grantId,long expectedVersion) {
        var a=access(auth,guildId);
        if(grantId<=0) throw invalid("Concessão inválida");
        Grant old=get(a,grantId);
        if(!"MANUAL".equals(old.grantSource())) throw conflict("Recompensas automáticas não são administradas por este formulário");
        if("CANCELLED".equals(old.status())) return old;
        if(!"ACTIVE".equals(old.status())) throw conflict("Concessão já expirada");
        if(jdbc.update("UPDATE user_temp_roles SET status='CANCELLED',"+
            "sync_status='PENDING',last_error_code=NULL,updated_by_user_id=?,"+
            "version=version+1 WHERE community_id=? AND guild_id=? AND id=? "+
            "AND grant_source='MANUAL' AND status='ACTIVE' AND version=?",
            a.actor().user().getId(),a.communityId(),a.guildId(),grantId,
            expectedVersion)!=1) throw conflict("Concessão alterada; atualize antes de cancelar");
        audit(a,"TEMPORARY_ROLE_CANCELLED",grantId);
        return get(a,grantId);
    }

    private void audit(Access a,String action,long id) {
        var e=new CommunityAuditLog();
        e.setCommunity(a.actor().community());e.setActorUser(a.actor().user());
        e.setAction(action);e.setTargetType("TEMPORARY_ROLE_GRANT");
        e.setTargetId(String.valueOf(id));audits.save(e);
    }
    private static ResponseStatusException invalid(String s) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);
    }
    private static ResponseStatusException conflict(String s) {
        return new ResponseStatusException(HttpStatus.CONFLICT,s);
    }
    private static ResponseStatusException forbidden(String s) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,s);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Concessão ou servidor indisponível");
    }
}
