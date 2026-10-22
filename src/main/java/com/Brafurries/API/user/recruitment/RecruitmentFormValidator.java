package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;

import com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Rejects incomplete or inconsistent draft form definitions before persistence. */
@Component
public class RecruitmentFormValidator {
    private static final Pattern KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,63}");
    private static final int MAX_QUESTIONS = 60;
    private static final int MAX_OPTIONS = 20;
    private static final int MAX_POINTS = 10000;

    public RecruitmentFormDefinition validate(
        RecruitmentFormDefinition form, boolean staff, StageType stageType
    ) {
        boolean auto = !staff && stageType == StageType.AUTO_EVALUATION;
        if (form == null) {
            if (auto) throw invalid("Avaliação automática exige formulário do candidato");
            return null;
        }
        if (form.questions() == null || form.questions().size() > MAX_QUESTIONS
            || (auto && form.questions().isEmpty())) {
            throw invalid("Quantidade de perguntas inválida");
        }
        List<Question> clean = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int totalPoints = 0;
        for (Question question : form.questions()) {
            if (question == null || question.type() == null) {
                throw invalid("Pergunta sem tipo");
            }
            if (!validKey(question.id()) || !keys.add(question.id())) {
                throw invalid("ID da pergunta inválido ou repetido");
            }
            String label = requiredText(question.label(), 500, "Texto da pergunta");
            QuestionType type = question.type();
            boolean choice = type == QuestionType.SINGLE_CHOICE || type == QuestionType.MULTIPLE_CHOICE;
            boolean limits = type == QuestionType.NUMBER || type == QuestionType.SCALE;
            List<Option> options = List.of();
            if (choice) {
                if (question.options() == null || question.options().size() < 2
                    || question.options().size() > MAX_OPTIONS) {
                    throw invalid("Alternativas precisam ter entre 2 e 20 opções");
                }
                List<Option> values = new ArrayList<>();
                Set<String> optionIds = new HashSet<>();
                for (Option option : question.options()) {
                    if (option == null || !validKey(option.id())
                        || !optionIds.add(option.id())) {
                        throw invalid("ID da alternativa inválido ou repetido");
                    }
                    values.add(new Option(
                        option.id(), requiredText(option.label(), 300, "Texto da alternativa")
                    ));
                }
                options = List.copyOf(values);
            } else if (question.options() != null && !question.options().isEmpty()) {
                throw invalid("Este tipo de pergunta não aceita alternativas");
            }

            Integer min = question.minValue();
            Integer max = question.maxValue();
            if (!limits && (min != null || max != null)) {
                throw invalid("Limites numéricos não se aplicam a este campo");
            }
            if (limits && min != null && max != null && min > max) {
                throw invalid("Limite mínimo superior ao máximo");
            }
            if (type == QuestionType.SCALE
                && (min == null || max == null || min < 0 || max > 10 || min >= max)) {
                throw invalid("Escala exige limites crescentes entre 0 e 10");
            }

            Integer points = question.points();
            List<String> answerKey = question.correctAnswers();
            if (!auto) {
                if (points != null || (answerKey != null && !answerKey.isEmpty())) {
                    throw invalid("Gabarito e pontuação são exclusivos de provas automáticas");
                }
                answerKey = List.of();
            } else {
                if (!choice && type != QuestionType.YES_NO
                    && type != QuestionType.NUMBER && type != QuestionType.SCALE) {
                    throw invalid("Prova automática aceita apenas perguntas objetivas");
                }
                if (points == null || points < 1 || points > MAX_POINTS) {
                    throw invalid("Pontuação da pergunta inválida");
                }
                totalPoints += points;
                if (totalPoints > 1000000) {
                    throw invalid("Pontuação total excede o limite");
                }
                if (answerKey == null || answerKey.isEmpty()) {
                    throw invalid("Pergunta automática exige gabarito");
                }
                Set<String> answers = new HashSet<>(answerKey);
                if (answers.size() != answerKey.size()) {
                    throw invalid("Gabarito contém respostas repetidas");
                }
                boolean multiple = type == QuestionType.MULTIPLE_CHOICE;
                if (!multiple && answerKey.size() != 1) {
                    throw invalid("Esta pergunta exige exatamente uma resposta correta");
                }
                if (choice) {
                    Set<String> optionIds = new HashSet<>();
                    for (Option option : options) optionIds.add(option.id());
                    if (!optionIds.containsAll(answers)) {
                        throw invalid("Gabarito contém alternativa inexistente");
                    }
                } else if (type == QuestionType.YES_NO) {
                    if (!Set.of("true", "false").containsAll(answers)) {
                        throw invalid("Gabarito sim/não inválido");
                    }
                } else {
                    for (String answer : answers) {
                        try {
                            BigDecimal numeric = new BigDecimal(answer);
                            if (min != null && numeric.compareTo(BigDecimal.valueOf(min)) < 0
                                || max != null && numeric.compareTo(BigDecimal.valueOf(max)) > 0) {
                                throw invalid("Gabarito numérico fora do intervalo");
                            }
                            if (type == QuestionType.SCALE
                                && numeric.stripTrailingZeros().scale() > 0) {
                                throw invalid("Resposta de escala precisa ser inteira");
                            }
                        } catch (NumberFormatException | NullPointerException ex) {
                            throw invalid("Gabarito numérico inválido");
                        }
                    }
                }
                answerKey = List.copyOf(answerKey);
            }
            clean.add(new Question(
                question.id(), label, type, question.required(), options, min, max,
                auto ? points : null, answerKey
            ));
        }
        return new RecruitmentFormDefinition(List.copyOf(clean));
    }

    private boolean validKey(String value) {
        return value != null && KEY.matcher(value).matches();
    }

    private String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw invalid(field + " inválido");
        }
        return value.strip();
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
