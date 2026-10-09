package com.Brafurries.API.user;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates a guild-owned OpenAI credential/model pair without involving Coddy.
 * Secrets are sent only to OpenAI and are never returned or logged.
 */
@Component
public class OpenAiCredentialValidator {
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";

    private final RestClient restClient;

    @Autowired
    public OpenAiCredentialValidator(
        @Value("${OPENAI_CONNECT_TIMEOUT_MS:3000}") int connectTimeoutMs,
        @Value("${OPENAI_VALIDATION_TIMEOUT_MS:15000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(readTimeoutMs, 1)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    OpenAiCredentialValidator(RestClient restClient) {
        this.restClient = restClient;
    }

    public void validate(String token, String model) {
        String normalizedToken = token == null ? "" : token.trim();
        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedToken.isEmpty() || normalizedModel.isEmpty()) {
            throw invalid("Credencial ou modelo OpenAI inválido");
        }

        try {
            restClient.post()
                .uri(RESPONSES_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "model", normalizedModel,
                    "input", "ping",
                    "max_output_tokens", 1
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException exception) {
            mapClientError(exception);
        } catch (HttpServerErrorException exception) {
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private void mapClientError(HttpClientErrorException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        String response = exception.getResponseBodyAsString();
        String safeBody = response == null ? "" : response.toLowerCase(Locale.ROOT);

        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            if (
                safeBody.contains("insufficient_quota")
                    || safeBody.contains("billing")
                    || safeBody.contains("quota")
            ) {
                throw invalid("A chave OpenAI foi reconhecida, mas a conta está sem cota ou billing disponível");
            }
            throw unavailable();
        }

        if (
            status == HttpStatus.UNAUTHORIZED
                || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.BAD_REQUEST
                || status == HttpStatus.NOT_FOUND
                || status == HttpStatus.UNPROCESSABLE_ENTITY
        ) {
            throw invalid("A OpenAI rejeitou esta chave ou ela não possui acesso ao modelo configurado");
        }

        throw unavailable();
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Não foi possível validar a credencial na OpenAI agora"
        );
    }
}
