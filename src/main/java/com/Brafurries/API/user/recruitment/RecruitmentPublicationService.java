package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentPublicationDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecruitmentPublicationService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentPublicationRepository repository;
    private final RecruitmentFormValidator validator;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper mapper;

    public RecruitmentPublicationService(
        RecruitmentFeatureGate gate,
        CommunityAuthorizationService authorization,
        RecruitmentPublicationRepository repository,
        RecruitmentFormValidator validator,
        CommunityAuditLogRepository audits,
        ObjectMapper mapper
    ) {
        this.gate = gate;
        this.authorization = authorization;
        this.repository = repository;
        this.validator = validator;
        this.audits = audits;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PublicationCheck preview(Authentication auth, Integer communityId, Long processId) {
        requireOneOf(auth, communityId, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES,
            CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES);
        var process = repository.findProcess(communityId, processId);
        if (process == null) throw missing();
        return evaluate(communityId, processId, process);
    }

    @Transactional
    public PublicationCheck configureEligibility(
        Authentication auth, Integer communityId, Long processId,
        UpdateEligibilityRequest request
    ) {
        var actor = require(auth, communityId, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if (request == null || request.eligibility() == null) {
            throw invalid("Regras de elegibilidade são obrigatórias");
        }
        long expectedRevision = requireRevision(request.expectedRevision());
        validateEligibility(request.eligibility());

        var locked = repository.lockProcess(communityId, processId);
        if (locked == null) throw missing();
        if (!"DRAFT".equals(locked.status()) || locked.revision() != expectedRevision) {
            throw conflict("Rascunho alterado ou já publicado");
        }
        var versions = repository.drafts(communityId, processId);
        if (versions.size() != 1) throw conflict("Deve existir uma única versão editável");
        final String json;
        try {
            json = mapper.writeValueAsString(request.eligibility());
        } catch (JsonProcessingException exception) {
            throw invalid("Regras de elegibilidade inválidas");
        }
        if (!repository.updateEligibility(
            communityId, processId, versions.get(0).id(), expectedRevision, json
        )) throw conflict("Rascunho alterado por outro gestor");

        audit(actor, "RECRUITMENT_ELIGIBILITY_UPDATED", processId);
        var fresh = repository.findProcess(communityId, processId);
        return evaluate(communityId, processId, fresh);
    }

    @Transactional
    public PublicationCheck configureTerms(
        Authentication auth, Integer communityId, Long processId, UpdateTermsRequest request
    ) {
        var actor = require(auth, communityId, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        long revision = requireRevision(request == null ? null : request.expectedRevision());
        String normalized = request.termsText() == null ? "" : request.termsText().strip();
        if (normalized.length() < 20 || normalized.length() > 12000) {
            throw invalid("Os termos devem conter entre 20 e 12000 caracteres");
        }
        var locked = repository.lockProcess(communityId, processId);
        if (locked == null) throw missing();
        if (!"DRAFT".equals(locked.status()) || locked.revision() != revision) {
            throw conflict("Rascunho desatualizado ou já publicado");
        }
        var drafts = repository.drafts(communityId, processId);
        if (drafts.size() != 1) throw conflict("É necessária uma versão de rascunho");
        final String serialized;
        try {
            serialized = mapper.writeValueAsString(new TermsText(normalized));
        } catch (JsonProcessingException exception) {
            throw invalid("Termos inválidos");
        }
        if (!repository.updateTerms(
            communityId, processId, drafts.get(0).id(), revision, serialized
        )) throw conflict("Rascunho alterado");
        audit(actor, "RECRUITMENT_TERMS_UPDATED", processId);
        return evaluate(communityId, processId, repository.findProcess(communityId, processId));
    }

    @Transactional
    public PublicationResult publish(
        Authentication auth, Integer communityId, Long processId, PublishRequest request
    ) {
        var actor = require(auth, communityId, CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES);
        long expectedRevision = requireRevision(request == null ? null : request.expectedRevision());

        var locked = repository.lockProcess(communityId, processId);
        if (locked == null) throw missing();
        if (!"DRAFT".equals(locked.status()) || locked.revision() != expectedRevision) {
            throw conflict("Rascunho desatualizado ou já publicado");
        }

        var check = evaluate(communityId, processId, locked);
        if (!check.ready()) throw conflict(
            "Não é possível publicar: " + String.join("; ", check.blockers())
        );
        if (!repository.publish(
            communityId, processId, check.processVersionId(), expectedRevision
        )) throw conflict("A versão deixou de ser um rascunho");

        audit(actor, "RECRUITMENT_PROCESS_PUBLISHED", processId);
        return new PublicationResult(
            processId, check.processVersionId(), expectedRevision + 1,
            "ACTIVE", "PUBLISHED", "CLOSED", "PRIVATE"
        );
    }

    private PublicationCheck evaluate(
        Integer communityId, Long processId,
        RecruitmentPublicationRepository.DraftProcess process
    ) {
        List<String> blockers = new ArrayList<>();
        if (!"DRAFT".equals(process.status())) {
            blockers.add("O processo não está mais em rascunho");
        }
        var versions = repository.drafts(communityId, processId);
        if (versions.size() != 1) {
            blockers.add("É necessária exatamente uma versão de rascunho");
            return new PublicationCheck(processId, null, process.revision(), false, 0, blockers, null, null);
        }
        var version = versions.get(0);
        EligibilityRules configuredEligibility = null;
        if (version.eligibilityJson() == null || version.eligibilityJson().isBlank()) {
            blockers.add("Configure os requisitos de elegibilidade");
        } else {
            try {
                EligibilityRules rules = mapper.readValue(
                    version.eligibilityJson(), EligibilityRules.class
                );
                validateEligibility(rules);
                configuredEligibility = rules;
            } catch (JsonProcessingException | ResponseStatusException invalid) {
                blockers.add("As regras de elegibilidade são inválidas");
            }
        }

        if (version.termsJson() == null || version.termsJson().isBlank()) {
            blockers.add("Configure os termos de participação");
        } else {
            try {
                TermsText terms = mapper.readValue(version.termsJson(), TermsText.class);
                if (terms == null || terms.text() == null
                    || terms.text().strip().length() < 20
                    || terms.text().strip().length() > 12000) {
                    blockers.add("Termos de participação inválidos");
                }
            } catch (JsonProcessingException exception) {
                blockers.add("Termos de participação inválidos");
            }
        }

        String configuredTermsText = null;
        if (version.termsJson() != null) {
            try {
                TermsText terms = mapper.readValue(version.termsJson(), TermsText.class);
                configuredTermsText = terms == null ? null : terms.text();
            } catch (JsonProcessingException ignored) {
                // Already reported as a publication blocker above.
            }
        }

        var stages = repository.stages(communityId, processId, version.id());
        if (stages.isEmpty()) blockers.add("Adicione ao menos uma etapa");
        if (stages.size() > 60) blockers.add("O processo excede 60 etapas");
        if (!stages.isEmpty()) {
            var first = stages.get(0);
            if (first.position() != 1 || !"APPLICATION".equals(first.stageType())
                || first.candidateFormJson() == null) {
                blockers.add("A primeira etapa deve ser Inscrição e possuir formulário do candidato");
            } else {
                try {
                    RecruitmentFormDefinition enrollment = readForm(first.candidateFormJson());
                    validator.validate(enrollment, false, StageType.APPLICATION);
                    if (enrollment == null || enrollment.questions().isEmpty()) {
                        blockers.add("O formulário da primeira etapa não pode estar vazio");
                    }
                } catch (JsonProcessingException | IllegalArgumentException
                    | ResponseStatusException malformed) {
                    blockers.add("Formulário de inscrição da primeira etapa inválido");
                }
            }
        }
        // Application intake is a separate system concern, not tied to a fixed
        // first stage type. Stages remain arbitrary, sequential and configurable.
        int expectedPosition = 1;
        for (var stage : stages) {
            if (stage.position() != expectedPosition++) {
                blockers.add("A ordenação das etapas não é consecutiva");
                break;
            }
            if ("AUTO_EVALUATION".equals(stage.stageType()) && !stage.hasAttemptPolicy()) {
                blockers.add("A etapa " + stage.position()
                    + " precisa de política de tentativas antes da publicação");
            }
            if (!"AUTO_EVALUATION".equals(stage.stageType())) {
                try {
                    var decisionPolicy = RecruitmentDecisionPolicy.parse(
                        mapper,stage.evaluationConfigJson());
                    if (decisionPolicy.decisionMode() ==
                        RecruitmentDecisionPolicy.DecisionMode.AUTOMATIC_VOTE) {
                        blockers.add("A etapa " + stage.position()
                            + " usa votação automática ainda indisponível");
                    }
                } catch (IllegalStateException invalid) {
                    blockers.add("Política de decisão inválida na etapa " + stage.position());
                }
            }
            try {
                StageType type = StageType.valueOf(stage.stageType());
                RecruitmentFormDefinition candidate =
                    readForm(stage.candidateFormJson());
                RecruitmentFormDefinition staff = readForm(stage.staffFormJson());
                validator.validate(candidate, false, type);
                validator.validate(staff, true, type);
            } catch (IllegalArgumentException | JsonProcessingException | ResponseStatusException exception) {
                blockers.add("A etapa " + stage.position() + " contém configuração inválida");
            }
        }
        return new PublicationCheck(
            processId, version.id(), process.revision(),
            blockers.isEmpty(), stages.size(), List.copyOf(blockers),
            configuredEligibility, configuredTermsText
        );
    }

    private RecruitmentFormDefinition readForm(String json) throws JsonProcessingException {
        return json == null ? null : mapper.readValue(json, RecruitmentFormDefinition.class);
    }

    private void validateEligibility(EligibilityRules rules) {
        if (rules == null || rules.minMembershipDays() < 0 || rules.minMembershipDays() > 3650) {
            throw invalid("Tempo mínimo de associação inválido");
        }
    }

    private long requireRevision(Long revision) {
        if (revision == null || revision < 0) throw invalid("Revisão do rascunho obrigatória");
        return revision;
    }

    private CommunityAuthorizationService.CommunityAccessContext requireOneOf(
        Authentication auth, Integer communityId,
        CommunityCapability first, CommunityCapability second
    ) {
        gate.requireEnabledFor(communityId);
        var context = authorization.resolve(auth, communityId);
        if (!context.capabilities().contains(first) && !context.capabilities().contains(second)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permissão insuficiente");
        }
        return context;
    }

    private CommunityAuthorizationService.CommunityAccessContext require(
        Authentication auth, Integer communityId, CommunityCapability capability
    ) {
        gate.requireEnabledFor(communityId);
        return authorization.requireCapability(auth, communityId, capability);
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Processo não encontrado");
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor, String action, Long processId
    ) {
        var event = new CommunityAuditLog();
        event.setCommunity(actor.community());
        event.setActorUser(actor.user());
        event.setAction(action);
        event.setTargetType("RECRUITMENT");
        event.setTargetId(String.valueOf(processId));
        audits.save(event);
    }
}
