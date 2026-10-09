package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityMemberNoteDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityMemberNote;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityMemberNoteRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityMemberNoteService {
    private final CommunityAuthorizationService authorization;
    private final CommunityMemberNoteRepository notes;
    private final UserCommunityStatusRepository memberships;
    private final CommunityAuditLogRepository auditLogs;

    public CommunityMemberNoteService(
        CommunityAuthorizationService authorization,
        CommunityMemberNoteRepository notes,
        UserCommunityStatusRepository memberships,
        CommunityAuditLogRepository auditLogs
    ) {
        this.authorization = authorization;
        this.notes = notes;
        this.memberships = memberships;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public List<CommunityMemberNoteResponse> list(
        Authentication auth,
        Integer communityId,
        Integer userId,
        boolean includeArchived
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            authorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.MEMBER_NOTES_VIEW
            );
        requireTargetMembership(actor.community(), userId);

        return notes.findMemberNotes(communityId, userId, includeArchived)
            .stream()
            .map(this::response)
            .toList();
    }

    @Transactional
    public CommunityMemberNoteResponse create(
        Authentication auth,
        Integer communityId,
        Integer userId,
        SaveCommunityMemberNoteRequest request
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            authorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.MEMBER_NOTES_MANAGE
            );
        requireTargetMembership(actor.community(), userId);

        CommunityMemberNote note = new CommunityMemberNote();
        note.setCommunity(actor.community());
        note.setUserId(userId);
        note.setAuthorUser(actor.user());
        note.setUpdatedByUser(actor.user());
        note.setContent(normalizeContent(request.content()));
        note = notes.saveAndFlush(note);

        audit(actor, "MEMBER_NOTE_CREATED", note);
        return response(note);
    }

    @Transactional
    public CommunityMemberNoteResponse update(
        Authentication auth,
        Integer communityId,
        Integer userId,
        Long noteId,
        SaveCommunityMemberNoteRequest request
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            authorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.MEMBER_NOTES_MANAGE
            );
        requireTargetMembership(actor.community(), userId);

        CommunityMemberNote note = requireNoteForUpdate(noteId, communityId, userId);
        if (note.getArchivedAt() != null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Nota arquivada não pode ser editada"
            );
        }

        note.setContent(normalizeContent(request.content()));
        note.setUpdatedByUser(actor.user());
        note.setUpdatedAt(LocalDateTime.now());
        note = notes.saveAndFlush(note);

        audit(actor, "MEMBER_NOTE_UPDATED", note);
        return response(note);
    }

    @Transactional
    public CommunityMemberNoteResponse archive(
        Authentication auth,
        Integer communityId,
        Integer userId,
        Long noteId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            authorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.MEMBER_NOTES_MANAGE
            );
        requireTargetMembership(actor.community(), userId);

        CommunityMemberNote note = requireNoteForUpdate(noteId, communityId, userId);
        if (note.getArchivedAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            note.setArchivedAt(now);
            note.setUpdatedAt(now);
            note.setUpdatedByUser(actor.user());
            note = notes.saveAndFlush(note);
            audit(actor, "MEMBER_NOTE_ARCHIVED", note);
        }
        return response(note);
    }

    private CommunityMemberNote requireNoteForUpdate(
        Long noteId,
        Integer communityId,
        Integer userId
    ) {
        return notes.findForUpdate(noteId, communityId, userId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Nota não encontrada para este membro nesta Community"
            ));
    }

    private void requireTargetMembership(Community community, Integer userId) {
        List<UserCommunityStatus> targetMemberships =
            memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(
                userId,
                community.getId()
            );
        if (targetMemberships.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Membro não encontrado nesta Community"
            );
        }
        if (targetMemberships.size() != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Membership do membro está ambígua nesta Community"
            );
        }
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Conteúdo da nota é obrigatório"
            );
        }
        String normalized = content.trim();
        if (normalized.length() > 4000) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Conteúdo da nota excede 4000 caracteres"
            );
        }
        return normalized;
    }

    private CommunityMemberNoteResponse response(CommunityMemberNote note) {
        return new CommunityMemberNoteResponse(
            note.getId(),
            note.getUserId(),
            note.getAuthorUser().getId(),
            displayName(note.getAuthorUser()),
            note.getUpdatedByUser().getId(),
            displayName(note.getUpdatedByUser()),
            note.getContent(),
            note.getCreatedAt(),
            note.getUpdatedAt(),
            note.getArchivedAt()
        );
    }

    private String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return "Usuário " + user.getId();
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action,
        CommunityMemberNote note
    ) {
        CommunityAuditLog audit = new CommunityAuditLog();
        audit.setCommunity(actor.community());
        audit.setActorUser(actor.user());
        audit.setAction(action);
        audit.setTargetType("MEMBER_NOTE");
        audit.setTargetId(String.valueOf(note.getId()));
        audit.setMetadata("{\"memberUserId\":" + note.getUserId() + "}");
        auditLogs.save(audit);
    }
}
