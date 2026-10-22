package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Checks candidate answers against the frozen candidate form, NEVER a staff form or gabarito. */
@Component
public class RecruitmentCandidateAnswerValidator {
    public Map<String, JsonNode> validate(RecruitmentFormDefinition form, Map<String, JsonNode> answers) {
        if (form == null || form.questions() == null || form.questions().isEmpty()) {
            throw invalid("Inscrição sem formulário configurado");
        }
        if (answers == null || answers.size() > 60) throw invalid("Respostas inválidas");
        var questions = new HashMap<String, Question>();
        for (Question q : form.questions()) questions.put(q.id(), q);
        if (!questions.keySet().containsAll(answers.keySet())) {
            throw invalid("O formulário contém respostas para perguntas desconhecidas");
        }
        for (Question q : form.questions()) {
            JsonNode value = answers.get(q.id());
            if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
                if (q.required()) throw invalid("Resposta obrigatória ausente: " + q.id());
                continue;
            }
            switch (q.type()) {
                case SHORT_TEXT, LONG_TEXT -> {
                    if (!value.isTextual() || value.textValue().length() > (q.type() == QuestionType.SHORT_TEXT ? 500 : 10000)) {
                        throw invalid("Resposta de texto inválida");
                    }
                }
                case LINK -> {
                    if (!value.isTextual() || value.textValue().length() > 2048) throw invalid("URL inválida");
                    try {
                        URI uri = URI.create(value.textValue());
                        if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null) {
                            throw invalid("URL inválida");
                        }
                    } catch (IllegalArgumentException error) {
                        throw invalid("URL inválida");
                    }
                }
                case DATE -> {
                    if (!value.isTextual()) throw invalid("Data inválida");
                    try { LocalDate.parse(value.textValue()); }
                    catch (DateTimeParseException error) { throw invalid("Data inválida"); }
                }
                case YES_NO, CONSENT -> {
                    if (!value.isBoolean()) throw invalid("Sim/não ou aceite inválido");
                    if (q.type() == QuestionType.CONSENT && q.required() && !value.booleanValue()) {
                        throw invalid("Aceite obrigatório não confirmado");
                    }
                }
                case SINGLE_CHOICE -> {
                    if (!value.isTextual() || !optionIds(q).contains(value.textValue())) {
                        throw invalid("Alternativa inválida");
                    }
                }
                case MULTIPLE_CHOICE -> {
                    if (!value.isArray() || value.size() > 20 || value.size() == 0) {
                        throw invalid("Seleção múltipla inválida");
                    }
                    Set<String> selected = new HashSet<>();
                    var allowed = optionIds(q);
                    for (JsonNode selectedValue : value) {
                        if (!selectedValue.isTextual() || !allowed.contains(selectedValue.textValue())
                            || !selected.add(selectedValue.textValue())) {
                            throw invalid("Seleção múltipla inválida");
                        }
                    }
                }
                case NUMBER, SCALE -> {
                    if (!value.isNumber()) throw invalid("Número inválido");
                    BigDecimal number = value.decimalValue();
                    if ((q.minValue() != null && number.compareTo(BigDecimal.valueOf(q.minValue())) < 0)
                        || (q.maxValue() != null && number.compareTo(BigDecimal.valueOf(q.maxValue())) > 0)
                        || (q.type() == QuestionType.SCALE && number.stripTrailingZeros().scale() > 0)) {
                        throw invalid("Número fora dos limites");
                    }
                }
            }
        }
        return Map.copyOf(answers);
    }

    private Set<String> optionIds(Question question) {
        Set<String> result = new HashSet<>();
        for (Option option : question.options() == null ? List.<Option>of() : question.options()) {
            result.add(option.id());
        }
        return result;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
