package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentCandidateDtos.*;
import static com.Brafurries.API.user.recruitment.RecruitmentPublicationDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.user.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Candidate intake V1: only the APPLICATION form at first stage is submitted.
 * The remaining stages stay PENDING for future human/automatic assessment.
 */
@Service
public class RecruitmentCandidateService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentCandidateRepository candidates;
    private final RecruitmentPublicationRepository publications;
    private final RecruitmentFormValidator forms;
    private final RecruitmentCandidateAnswerValidator answers;
    private final UserCommunityStatusRepository memberships;
    private final CommunityMembershipStatusResolver membershipStatus;
    private final CommunityNetworkAvailabilityService availability;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper json;

    public RecruitmentCandidateService(
        RecruitmentFeatureGate gate,
        CommunityAuthorizationService authorization,
        RecruitmentCandidateRepository candidates,
        RecruitmentPublicationRepository publications,
        RecruitmentFormValidator forms,
        RecruitmentCandidateAnswerValidator answers,
        UserCommunityStatusRepository memberships,
        CommunityMembershipStatusResolver membershipStatus,
        CommunityNetworkAvailabilityService availability,
        CommunityAuditLogRepository audits,
        ObjectMapper json
    ) {
        this.gate=gate;
        this.authorization=authorization;
        this.candidates=candidates;
        this.publications=publications;
        this.forms=forms;
        this.answers=answers;
        this.memberships=memberships;
        this.membershipStatus=membershipStatus;
        this.availability=availability;
        this.audits=audits;
        this.json=json;
    }

    @Transactional(readOnly = true)
    public List<OpenProcess> list(Authentication auth, Integer communityId) {
        requireActiveMember(auth, communityId);
        return candidates.listOpen(communityId);
    }

    @Transactional(readOnly = true)
    public ApplicationForm form(Authentication auth, Integer communityId, Long processId) {
        var actor = requireActiveMember(auth, communityId);
        var selection = candidates.publishedSelection(communityId, processId, true, false);
        if (selection == null) throw unavailable();
        var membership = memberships.findByUserAndCommunity(actor.user(), actor.community())
            .orElse(null);
        return toPublicForm(selection, membership);
    }

    @Transactional
    public Applied apply(Authentication auth, Integer communityId, Long processId, ApplyRequest request) {
        var actor = requireActiveMember(auth, communityId);
        if (request == null || request.answers() == null
            || request.termsHash() == null || !request.termsHash().matches("[0-9a-f]{64}")) {
            throw invalid("Respostas e aceite dos termos obrigatórios");
        }
        // Lock membership to serialize with regular community membership mutations.
        List<UserCommunityStatus> rows = memberships.findAllForAssignmentByUserIdAndCommunityId(
            actor.user().getId(), communityId
        );
        if (rows.size() != 1
            || membershipStatus.resolve(rows.get(0), availability.isPortariaEnabled(actor.community()))
                != CommunityMembershipStatus.ACTIVE) {
            throw unavailable();
        }
        var selection = candidates.publishedSelection(communityId, processId, true, true);
        if (selection == null) throw unavailable();
        var presentation = toPublicForm(selection, rows.get(0));
        if (!presentation.eligible()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Não atende aos requisitos de inscrição");
        }
        if (!MessageDigest.isEqual(
            presentation.termsHash().getBytes(StandardCharsets.US_ASCII),
            request.termsHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Os termos foram atualizados. Leia e aceite a versão atual");
        }

        var stageForm = parseForm(selection.candidateFormJson());
        var cleaned = answers.validate(stageForm, request.answers());
        final String answerJson;
        try {
            answerJson = json.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw invalid("Respostas inválidas");
        }
        if (answerJson.length() > 100000) throw invalid("Respostas excedem o limite");

        List<Long> stageIds = candidates.stageIds(
            communityId, selection.processId(), selection.versionId()
        );
        if (stageIds.isEmpty() || !Objects.equals(stageIds.get(0), selection.firstStageId())) {
            throw new IllegalStateException("A versão da seleção mudou durante a inscrição");
        }
        try {
            Applied result = candidates.submit(
                communityId, selection.processId(), selection.versionId(),
                actor.user().getId(), selection.firstStageId(), stageIds, answerJson,
                presentation.termsHash()
            );
            audit(actor, "RECRUITMENT_APPLICATION_SUBMITTED", result.applicationId());
            return result;
        } catch (DataIntegrityViolationException duplicateOrRace) {
            // Includes the unique (community, process, candidate) guard.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Candidatura já registrada ou alterada durante o envio", duplicateOrRace);
        }
    }

    @Transactional
    public void open(
        Authentication auth, Integer communityId, Long processId,
        long expectedRevision, Instant endsAt
    ) {
        var actor = requirePublisher(auth, communityId);
        if (endsAt == null || endsAt.isBefore(Instant.now().plus(15, ChronoUnit.MINUTES))
            || endsAt.isAfter(Instant.now().plus(90, ChronoUnit.DAYS))) {
            throw invalid("Prazo deve estar entre 15 minutos e 90 dias");
        }
        var locked = publications.lockProcess(communityId, processId);
        if (locked == null || !"ACTIVE".equals(locked.status())
            || locked.revision() != expectedRevision) {
            throw conflict("Publicação desatualizada ou processo não publicado");
        }
        var selection = candidates.publishedSelection(communityId, processId, false, false);
        if (selection == null) throw conflict("É necessária uma primeira etapa de inscrição");
        // Fail closed: never open an invalid or empty candidate form or missing terms.
        toPublicForm(selection, null);
        if (!candidates.openEnrollment(
            communityId, processId,
            LocalDateTime.ofInstant(endsAt, ZoneOffset.UTC), expectedRevision
        )) throw conflict("O processo não está privado e com inscrições fechadas");
        audit(actor, "RECRUITMENT_ENROLLMENT_OPENED", processId);
    }

    private ApplicationForm toPublicForm(
        RecruitmentCandidateRepository.CandidateSelection selection,
        UserCommunityStatus membership
    ) {
        final EligibilityRules rules;
        final TermsText terms;
        try {
            rules = json.readValue(selection.eligibilityJson(), EligibilityRules.class);
            terms = json.readValue(selection.termsJson(), TermsText.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw conflict("Definição publicada inválida");
        }
        if (rules == null || rules.minMembershipDays() < 0 || rules.minMembershipDays() > 3650
            || terms == null || terms.text() == null || terms.text().strip().length() < 20) {
            throw conflict("Definição publicada incompleta");
        }

        var candidateForm = parseForm(selection.candidateFormJson());
        forms.validate(candidateForm, false, RecruitmentDraftDtos.StageType.APPLICATION);
        if (candidateForm == null || candidateForm.questions().isEmpty()) {
            throw conflict("Formulário de inscrição vazio");
        }
        List<PublicQuestion> questions = new ArrayList<>();
        for (var question : candidateForm.questions()) {
            var publicOptions = (question.options()==null ? List.<RecruitmentFormDefinition.Option>of() : question.options())
                .stream().map(option->new PublicOption(option.id(),option.label())).toList();
            questions.add(new PublicQuestion(
                question.id(),question.label(),question.type().name(),question.required(),
                publicOptions,question.minValue(),question.maxValue()
            ));
        }
        List<String> reasons = new ArrayList<>();
        if (membership != null) {
            if (rules.requireVip() && !Boolean.TRUE.equals(membership.getIsVip())) {
                reasons.add("Membro VIP obrigatório");
            }
            if (rules.requirePartner() && !Boolean.TRUE.equals(membership.getIsPartner())) {
                reasons.add("Membro parceiro obrigatório");
            }
            LocalDateTime joined = membership.getLastJoinDate() == null
                ? membership.getMemberSince() : membership.getLastJoinDate();
            if (joined == null ||
                joined.isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(rules.minMembershipDays()))) {
                reasons.add("Tempo mínimo de associação não atendido");
            }
        }
        return new ApplicationForm(
            new OpenProcess(selection.processId(),selection.title(),selection.description(),
                selection.closesAt()),
            selection.versionId(),selection.firstStageId(),
            new PublicForm(List.copyOf(questions)),
            terms.text(), sha256(selection.termsJson()),
            reasons.isEmpty(),List.copyOf(reasons)
        );
    }

    private RecruitmentFormDefinition parseForm(String source) {
        if (source == null) throw conflict("Formulário de inscrição não configurado");
        try { return json.readValue(source,RecruitmentFormDefinition.class); }
        catch (JsonProcessingException error) { throw conflict("Formulário publicado inválido"); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private CommunityAuthorizationService.CommunityAccessContext requireActiveMember(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor = authorization.resolve(auth, communityId);
        if (!actor.activeMember()) throw unavailable(); // owner is not an eligibility bypass
        return actor;
    }

    private CommunityAuthorizationService.CommunityAccessContext requirePublisher(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        return authorization.requireCapability(
            auth,communityId,CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES
        );
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action, Long identifier
    ) {
        var row = new CommunityAuditLog();
        row.setCommunity(actor.community());row.setActorUser(actor.user());
        row.setAction(action);row.setTargetType("RECRUITMENT");
        row.setTargetId(identifier.toString());audits.save(row);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Inscrição indisponível");
    }
    private ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT,text);
    }
    private ResponseStatusException invalid(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);
    }
}
