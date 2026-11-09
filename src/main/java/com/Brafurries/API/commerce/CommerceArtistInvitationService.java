package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;

import com.Brafurries.API.auth.common.AuthService;
import com.Brafurries.API.auth.social.SparseAccountOwnershipGuard;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Artist ownership is established ONLY through an admin-generated invitation,
 * a separate out-of-band secret, and identity proof:
 *  - new artist: local e-mail/password activation + e-mail verification
 *  - existing user: authenticated session + confirmed local e-mail + password
 *
 * All original commercial ownership references are transferred to a single
 * canonical users.id on an existing-account redemption. Historical signed
 * contract provenance remains immutable in signed_artist_user_id.
 * No Community selector, no self-service search/claim by numeric artist ID.
 */
@Service
public class CommerceArtistInvitationService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final HexFormat HEX=HexFormat.of();
    private static final int MAX_FAILED_ATTEMPTS=5;
    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final CommerceFinanceSupport finance;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthService localAuth;
    private final UserRefreshTokenRepository refreshTokens;
    private final SparseAccountOwnershipGuard ownership;

    public CommerceArtistInvitationService(
        PlatformAuthorizationService authorization,JdbcTemplate jdbc,
        CommerceFinanceSupport finance,UserRepository users,
        PasswordEncoder passwords,AuthService localAuth,
        UserRefreshTokenRepository refreshTokens,SparseAccountOwnershipGuard ownership
    ) {
        this.authorization=authorization;
        this.jdbc=jdbc;
        this.finance=finance;
        this.users=users;
        this.passwords=passwords;
        this.localAuth=localAuth;
        this.refreshTokens=refreshTokens;
        this.ownership=ownership;
    }

    // Raised ONLY for a failed second factor and incorrect current-account
    // password. Its single row attempt counter intentionally commits.
    static final class InvalidProof extends ResponseStatusException {
        InvalidProof() { super(HttpStatus.BAD_REQUEST,
            "Convite ou confirmação inválidos; solicite um novo convite à equipe"); }
    }

    @Transactional(isolation=Isolation.SERIALIZABLE)
    public IssuedInvitation issue(Authentication auth,IssueInvitation input) {
        User owner=authorization.requireOwner(auth);
        if(input==null || input.artistUserId()==null || input.artistUserId()<=0) {
            throw invalid("Selecione uma artista cadastrada");
        }
        int id=input.artistUserId();
        User sparse=users.findByIdForUpdate(id)
            .orElseThrow(()->missing("Artista não encontrada"));
        requireSparse(sparse);
        List<Map<String,Object>> profile=jdbc.queryForList("""
            SELECT user_id FROM commerce_artist_profiles WHERE user_id=? FOR UPDATE
            """,id);
        if(profile.isEmpty()) throw missing("Artista externa não encontrada");
        // Never issue a link that could claim a legacy Discord/Community/OAuth
        // account or a sparse row with unclassified user-owned data.
        ownership.assertOnlyArtistCommerceReferences(id);

        // Explicit reissue revokes any previous active invitation atomically.
        jdbc.update("""
            UPDATE commerce_artist_invitations
               SET status='REVOKED',revoked_by_user_id=?,revoked_at=NOW(6)
             WHERE artist_user_id=? AND status='ACTIVE'
            """,owner.getId(),id);
        String token=randomHex(32);
        String code=randomHex(6).toUpperCase(java.util.Locale.ROOT);
        long inviteId=finance.insert("""
            INSERT INTO commerce_artist_invitations
                (artist_user_id,token_hash,code_hash,expires_at,issued_by_user_id)
            VALUES (?,?,?,DATE_ADD(NOW(6), INTERVAL 48 HOUR),?)
            """,id,hash(token),hash(code),owner.getId());
        String expiresAt=jdbc.queryForObject("""
            SELECT DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%s')
              FROM commerce_artist_invitations WHERE id=?
            """,String.class,inviteId);
        audit(owner.getId(),"ARTIST_INVITE",inviteId,"ISSUED");
        // The raw token and code are never logged/persisted. Display once.
        return new IssuedInvitation(inviteId,token,code,expiresAt);
    }

    @Transactional
    public void revoke(Authentication auth,long invitationId) {
        User owner=authorization.requireOwner(auth);
        int changed=jdbc.update("""
            UPDATE commerce_artist_invitations
               SET status='REVOKED',revoked_by_user_id=?,revoked_at=NOW(6)
             WHERE id=? AND status='ACTIVE'
            """,owner.getId(),invitationId);
        if(changed!=1) throw conflict("Convite expirado, utilizado ou inexistente");
        audit(owner.getId(),"ARTIST_INVITE",invitationId,"REVOKED");
    }

    /**
     * New site account: the commercial users.id is activated IN PLACE.
     * A password and independently chosen local e-mail are never derived
     * from Telegram, Google or a contact_email recorded by staff.
     * No site access until the standard email confirmation is complete.
     */
    @Transactional(isolation=Isolation.SERIALIZABLE,
        noRollbackFor=InvalidProof.class)
    public InvitationResult activate(ActivateInvitation input) {
        if(input==null || !Boolean.TRUE.equals(input.termsAgreement())) {
            throw invalid("Aceite os termos para criar sua conta");
        }
        String chosenEmail=normalizedEmail(input.email());
        if(input.password()==null || input.password().length()<8
            || !input.password().equals(input.passwordConfirmation())) {
            throw invalid("Confirme uma senha BraFurries de pelo menos oito caracteres");
        }
        Map<String,Object> invite=lockedVerifiedInvite(input.token(),input.confirmationCode());
        int artist=Math.toIntExact(number(invite,"artist_user_id"));
        User sparse=users.findByIdForUpdate(artist)
            .orElseThrow(()->conflict("Cadastro comercial alterado"));
        requireSparse(sparse);
        ownership.assertOnlyArtistCommerceReferences(artist);
        if(users.findByEmail(chosenEmail).isPresent()) {
            throw conflict("Este e-mail já possui conta. Entre com ela e use o convite.");
        }
        sparse.setEmail(chosenEmail);
        sparse.setPasswordHash(passwords.encode(input.password()));
        sparse.setEmailVerifiedAt(null);
        users.saveAndFlush(sparse);
        localAuth.requireLocalEmailConfirmation(sparse);
        consume(invite,artist);
        finance.insert("""
            INSERT INTO commerce_artist_consolidations
                (invitation_id,original_user_id,canonical_user_id,consolidation_kind)
            VALUES (?,?,?,'ACTIVATION')
            """,number(invite,"id"),artist,artist);
        audit(artist,"ARTIST_INVITE",number(invite,"id"),"ACTIVATED");
        return new InvitationResult("VERIFY_EMAIL",
            "Confirme o e-mail escolhido. Depois entre para visualizar suas artes e royalties.");
    }

    /**
     * Existing verified main account. The invitation URL alone is not proof:
     * a separately delivered confirmation code and a fresh local password are
     * mandatory. No automatic role/Community transfer or trust by matched name.
     */
    @Transactional(isolation=Isolation.SERIALIZABLE,
        noRollbackFor=InvalidProof.class)
    public InvitationResult redeem(Authentication auth,RedeemInvitation input) {
        if(input==null) throw invalid("Informe o convite");
        User actor=authorization.currentUser(auth);
        Map<String,Object> invite=lockedVerifiedInvite(input.token(),input.confirmationCode());
        int sourceId=Math.toIntExact(number(invite,"artist_user_id"));
        int targetId=actor.getId();
        if(sourceId==targetId) throw conflict("Este já é o cadastro comercial da sua conta");
        // Stable user lock ordering. Same issuer account cannot be merged
        // concurrently or unexpectedly acquire rights through an old JWT.
        int first=Math.min(sourceId,targetId),second=Math.max(sourceId,targetId);
        User a=users.findByIdForUpdate(first).orElseThrow(()->conflict("Conta alterada"));
        User b=users.findByIdForUpdate(second).orElseThrow(()->conflict("Conta alterada"));
        User source=sourceId==first?a:b;
        User target=targetId==first?a:b;
        requireSparse(source);
        if(target.getMergedIntoUser()!=null || target.getEmail()==null
            || target.getEmailVerifiedAt()==null || target.getPasswordHash()==null) {
            throw conflict("Entre numa conta ativa com e-mail confirmado");
        }
        if(input.accountPassword()==null
            || !passwords.matches(input.accountPassword(),target.getPasswordHash())) {
            badProof(invite);
        }
        if(target.getAuthVersion()==null || target.getAuthVersion()==Long.MAX_VALUE
            || source.getAuthVersion()==null || source.getAuthVersion()==Long.MAX_VALUE) {
            throw conflict("Versão de autenticação inválida");
        }
        ownership.assertOnlyArtistCommerceReferences(sourceId);
        ensureCompatibleFinancialOwnership(sourceId,targetId);
        List<Map<String,Object>> profiles=jdbc.queryForList("""
            SELECT user_id FROM commerce_artist_profiles
             WHERE user_id IN (?,?) ORDER BY user_id FOR UPDATE
            """,sourceId,targetId);
        if(profiles.size()!=1 || number(profiles.get(0),"user_id")!=sourceId) {
            throw conflict("Cadastro comercial já vinculado ou conflitante");
        }

        // Owner row locks + SERIALIZABLE reference scan reject unknown
        // domains and block new source-owned financial FKs until commit.
        // The FK on signed contracts references artwork_id alone as of
        // V20261109_003. Its signed_artist_user_id remains unchanged.
        int contracts=jdbc.update("""
            UPDATE commerce_artwork_contracts SET artist_user_id=?
             WHERE artist_user_id=?
            """,targetId,sourceId);
        int artworks=jdbc.update("""
            UPDATE commerce_artworks SET artist_user_id=?
             WHERE artist_user_id=?
            """,targetId,sourceId);
        int accruals=jdbc.update("""
            UPDATE commerce_royalty_accruals SET artist_user_id=?
             WHERE artist_user_id=?
            """,targetId,sourceId);
        int settlements=jdbc.update("""
            UPDATE commerce_settlements SET artist_user_id=?
             WHERE artist_user_id=?
            """,targetId,sourceId);
        // Lock records can overlap even when accounting ledgers do not;
        // combine the mutex rows (no monetary amount is duplicated).
        jdbc.update("""
            INSERT IGNORE INTO commerce_period_locks
                (artist_user_id,period_year,period_month)
            SELECT ?,period_year,period_month
              FROM commerce_period_locks WHERE artist_user_id=?
            """,targetId,sourceId);
        jdbc.update("""
            DELETE FROM commerce_period_locks WHERE artist_user_id=?
            """,sourceId);
        int profileMoved=jdbc.update("""
            UPDATE commerce_artist_profiles SET user_id=?
             WHERE user_id=?
            """,targetId,sourceId);
        if(profileMoved!=1) throw conflict("Falha ao transferir cadastro comercial");

        // Original user is an inert provenance alias, not a second active
        // account. Only the canonical user can now access artwork/royalties.
        source.setMergedIntoUser(target);
        source.setAuthVersion(source.getAuthVersion()+1);
        target.setAuthVersion(target.getAuthVersion()+1);
        users.saveAndFlush(source);
        users.saveAndFlush(target);
        refreshTokens.deleteByUser(target);
        consume(invite,targetId);
        finance.insert("""
            INSERT INTO commerce_artist_consolidations
                (invitation_id,original_user_id,canonical_user_id,
                 consolidation_kind,artworks_moved,contracts_moved,
                 accruals_moved,settlements_moved)
            VALUES (?,?,?,'MERGE',?,?,?,?)
            """,number(invite,"id"),sourceId,targetId,
            artworks,contracts,accruals,settlements);
        audit(targetId,"ARTIST_INVITE",number(invite,"id"),"CONSOLIDATED");
        return new InvitationResult("RELOGIN_REQUIRED",
            "Identidade comercial consolidada. Entre novamente para consultar suas artes.");
    }

    private void ensureCompatibleFinancialOwnership(int sourceId,int targetId) {
        // Locking reads on indexed month ranges serialize ongoing commerce
        // operations with this user identity consolidation.
        jdbc.queryForList("""
            SELECT artist_user_id,period_year,period_month
              FROM commerce_period_locks
             WHERE artist_user_id IN (?,?)
             ORDER BY artist_user_id,period_year,period_month FOR UPDATE
            """,sourceId,targetId);
        List<Map<String,Object>> ledger=jdbc.queryForList("""
            SELECT artist_user_id,period_year,period_month
              FROM commerce_royalty_accruals
             WHERE artist_user_id IN (?,?)
            UNION
            SELECT artist_user_id,period_year,period_month
              FROM commerce_settlements
             WHERE artist_user_id IN (?,?)
            """,sourceId,targetId,sourceId,targetId);
        Set<String> sourcePeriods=new HashSet<>();
        Set<String> targetPeriods=new HashSet<>();
        for(Map<String,Object> row:ledger) {
            String period=number(row,"period_year")+"-"+number(row,"period_month");
            int owner=Math.toIntExact(number(row,"artist_user_id"));
            (owner==sourceId?sourcePeriods:targetPeriods).add(period);
        }
        sourcePeriods.retainAll(targetPeriods);
        if(!sourcePeriods.isEmpty()) {
            throw conflict("As duas contas possuem registros financeiros na mesma competência. "
                +"A equipe precisa conciliar estes meses antes da fusão.");
        }
    }

    private Map<String,Object> lockedVerifiedInvite(String token,String code) {
        if(token==null || !token.matches("[a-f0-9]{64}")
            || code==null || !code.matches("[A-Fa-f0-9]{12}")) {
            throw new InvalidProof();
        }
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT id,artist_user_id,code_hash,status,failed_attempts,
                   (expires_at>NOW(6)) AS not_expired
              FROM commerce_artist_invitations
             WHERE token_hash=? FOR UPDATE
            """,hash(token));
        if(rows.isEmpty()) throw new InvalidProof();
        Map<String,Object> invite=rows.get(0);
        if(!"ACTIVE".equals(invite.get("status"))
            || number(invite,"not_expired")!=1
            || number(invite,"failed_attempts")>=MAX_FAILED_ATTEMPTS) {
            throw new InvalidProof();
        }
        boolean match=MessageDigest.isEqual(
            hash(code.toUpperCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.US_ASCII),
            String.valueOf(invite.get("code_hash")).getBytes(StandardCharsets.US_ASCII));
        if(!match) badProof(invite);
        return invite;
    }

    private void badProof(Map<String,Object> invite) {
        jdbc.update("""
            UPDATE commerce_artist_invitations
               SET failed_attempts=failed_attempts+1,
                   status=CASE WHEN failed_attempts>=4 THEN 'BLOCKED' ELSE 'ACTIVE' END
             WHERE id=? AND status='ACTIVE' AND failed_attempts<5
            """,number(invite,"id"));
        throw new InvalidProof();
    }

    private void consume(Map<String,Object> invite,int canonicalId) {
        int updated=jdbc.update("""
            UPDATE commerce_artist_invitations
               SET status='USED',consumed_by_user_id=?,consumed_at=NOW(6)
             WHERE id=? AND status='ACTIVE' AND expires_at>NOW(6)
            """,canonicalId,number(invite,"id"));
        if(updated!=1) throw conflict("O convite não está mais disponível");
    }

    private void requireSparse(User user) {
        if(user.getId()==null || user.getMergedIntoUser()!=null
            || user.getEmail()!=null || user.getPasswordHash()!=null) {
            throw conflict("Esta identidade já possui conta. Conciliação necessária.");
        }
    }

    private String normalizedEmail(String input) {
        if(input==null) throw invalid("E-mail obrigatório");
        String email=input.trim().toLowerCase(java.util.Locale.ROOT);
        if(email.length()>255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw invalid("E-mail inválido");
        }
        return email;
    }

    private void audit(int actor,String type,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit
                (actor_user_id,entity_type,entity_id,action)
            VALUES (?,?,?,?)
            """,actor,type,id,action);
    }

    private static String randomHex(int bytes) {
        byte[] data=new byte[bytes];
        RANDOM.nextBytes(data);
        return HEX.formatHex(data);
    }

    private static String hash(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível",e);
        }
    }

    private static long number(Map<String,Object> row,String key) {
        Object value=row.get(key);
        return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value));
    }
}
