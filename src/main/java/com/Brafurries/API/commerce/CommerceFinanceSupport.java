package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Reused financial invariants; DB authorization remains PlatformAuthorizationService. */
@Component
public class CommerceFinanceSupport {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");
    private final JdbcTemplate jdbc;

    public CommerceFinanceSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    public static ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,message);
    }
    public static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }
    public static YearMonth currentMonth() {
        return YearMonth.now(BUSINESS_ZONE);
    }
    public static BigDecimal money(BigDecimal value,String label) {
        if (value == null || value.signum()<0 || value.scale()>2
            || value.compareTo(MAX)>0) throw invalid(label+" inválido: use reais e centavos");
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    public static BigDecimal signedMoney(BigDecimal value,String label) {
        if (value == null || value.abs().compareTo(MAX)>0 || value.scale()>2) {
            throw invalid(label+" inválido: valor fora dos limites em reais");
        }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    public static String text(String value,int max,String label,boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw invalid(label+" obrigatório");
            return null;
        }
        String normalized=value.trim();
        if (normalized.length()>max) throw invalid(label+" ultrapassa "+max+" caracteres");
        return normalized;
    }
    public static String requestId(String raw) {
        if (raw == null || raw.length()!=36) throw invalid("requestKey precisa ser UUID");
        try {
            UUID uuid=UUID.fromString(raw);
            if (!uuid.toString().equals(raw.toLowerCase(java.util.Locale.ROOT))) {
                throw invalid("requestKey precisa ser UUID canônico");
            }
            return uuid.toString();
        } catch (IllegalArgumentException ex) {
            throw invalid("requestKey precisa ser UUID");
        }
    }
    public static String fingerprint(List<String> normalizedParts) {
        StringBuilder canonical=new StringBuilder();
        for (String part:normalizedParts) {
            if (part==null) canonical.append("-1:");
            else canonical.append(part.length()).append(':').append(part);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
    public long insert(String sql,Object...args) {
        KeyHolder generated=new GeneratedKeyHolder();
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },generated);
        if (generated.getKey()==null) throw new IllegalStateException("O banco não retornou ID");
        return generated.getKey().longValue();
    }
    public void audit(int actor,String entity,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_finance_audit
                (actor_user_id,entity_type,entity_id,action) VALUES (?,?,?,?)
            """,actor,entity,id,action);
    }
    public void lockPeriod(int artist,int year,int month) {
        if (artist<1 || year<2000 || year>2200 || month<1 || month>12) {
            throw invalid("Artista ou período inválido");
        }
        jdbc.update("""
            INSERT IGNORE INTO commerce_period_locks
                (artist_user_id,period_year,period_month)
            VALUES (?,?,?)
            """,artist,year,month);
        List<Integer> locks=jdbc.queryForList("""
            SELECT artist_user_id FROM commerce_period_locks
             WHERE artist_user_id=? AND period_year=? AND period_month=? FOR UPDATE
            """,Integer.class,artist,year,month);
        if (locks.isEmpty()) throw new IllegalStateException("Falha de lock do período");
    }
    public void requirePeriodNotSettled(int artist,int year,int month) {
        // Locking current read: COUNT(*) using the earlier REPEATABLE READ
        // snapshot could miss a just-committed settlement while we waited
        // on the period mutex.
        List<Long> existing=jdbc.queryForList("""
            SELECT id FROM commerce_settlements
             WHERE artist_user_id=? AND period_year=? AND period_month=?
             FOR UPDATE
            """,Long.class,artist,year,month);
        if (!existing.isEmpty()) {
            throw conflict("O mês deste artista já possui fechamento. Remova o rascunho antes de corrigir vendas.");
        }
    }
}
