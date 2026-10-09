package com.Brafurries.API.user;

import com.Brafurries.API.common.BotBaseUrlNormalizer;
import com.Brafurries.API.user.dto.LogConfigurationDtos.LogsState;
import com.Brafurries.API.user.dto.LogConfigurationDtos.UpdateLogRequest;
import com.Brafurries.API.user.dto.LogConfigurationDtos.TestLogRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CoddyLogConfigurationClient {
    private final RestClient readClient; private final RestClient operationClient; private final String baseUrl; private final String token;
    public CoddyLogConfigurationClient(@Value("${app.bot.base-url:http://127.0.0.1:18088}") String baseUrl, @Value("${app.bot.status-token:}") String token, @Value("${app.bot.connect-timeout-ms:2000}") int connect, @Value("${app.bot.read-timeout-ms:3000}") int read, @Value("${app.bot.operation-timeout-ms:30000}") int operation) {
        this.readClient = client(connect, read); this.operationClient = client(connect, operation); this.baseUrl = BotBaseUrlNormalizer.normalize(baseUrl); this.token = token;
    }
    private RestClient client(int connect, int read) { SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(Duration.ofMillis(Math.max(connect, 1))); factory.setReadTimeout(Duration.ofMillis(Math.max(read, 1))); return RestClient.builder().requestFactory(factory).build(); }
    public LogsState get(String guildId, Long userId) { return request(readClient.get().uri(baseUrl + "/guilds/{guildId}/logs/{userId}", guildId, userId).headers(this::auth)); }
    public LogsState update(String guildId, String type, Long userId, UpdateLogRequest body) { return request(operationClient.put().uri(baseUrl + "/guilds/{guildId}/logs/{type}/{userId}", guildId, type, userId).headers(this::auth).body(body)); }
    public void test(String guildId, String type, Long userId, TestLogRequest body) { try { operationClient.post().uri(baseUrl + "/guilds/{guildId}/logs/{type}/{userId}/test", guildId, type, userId).headers(this::auth).body(body).retrieve().toBodilessEntity(); } catch (HttpClientErrorException ex) { throw mapped(ex); } catch (RestClientException ex) { throw unavailable(); } }
    private LogsState request(RestClient.RequestHeadersSpec<?> request) { try { LogsState value = request.retrieve().body(LogsState.class); if (value == null) throw unavailable(); return value; } catch (HttpClientErrorException ex) { throw mapped(ex); } catch (RestClientException ex) { throw unavailable(); } }
    private void auth(org.springframework.http.HttpHeaders headers) { if (token != null && !token.isBlank()) headers.setBearerAuth(token); }
    private ResponseStatusException mapped(HttpClientErrorException ex) { HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value()); String message = switch (status) { case BAD_REQUEST -> "Requisição de logs inválida"; case FORBIDDEN -> "Sem permissão para gerenciar este servidor"; case NOT_FOUND -> "Servidor ou tipo de log não encontrado"; case UNPROCESSABLE_ENTITY -> "Destino de log inválido ou sem permissão para o Coddy"; case UNAUTHORIZED -> "Falha de autenticação interna com o Coddy"; default -> "Coddy indisponível no momento"; }; return new ResponseStatusException(status, message); }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy indisponível no momento"); }
}
