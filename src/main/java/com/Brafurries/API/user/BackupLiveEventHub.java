package com.Brafurries.API.user;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process fan-out only. MariaDB remains the authority; every connection
 * starts with a fresh DB snapshot, including reconnects after lost webhooks.
 * This V1 requires a single API application instance for live fan-out.
 */
@Service
public class BackupLiveEventHub {
    private static final long STREAM_LIFETIME_MS = 60_000L;
    private static final int MAX_CLIENTS_PER_OPERATION = 8;

    private final BackupControlPlaneService controlPlane;
    private final BackupControlPlaneStore store;
    private final ConcurrentHashMap<Key, CopyOnWriteArrayList<Subscriber>> clients =
        new ConcurrentHashMap<>();

    public BackupLiveEventHub(
        BackupControlPlaneService controlPlane,
        BackupControlPlaneStore store
    ) {
        this.controlPlane = controlPlane;
        this.store = store;
    }

    public SseEmitter subscribe(
        Authentication authentication,
        String guildId,
        String kind,
        long operationId
    ) {
        String normalized = normalizeKind(kind);
        if (operationId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operação inválida");
        }

        // Always verify guild access and operation ownership BEFORE registering.
        authorizedState(authentication, guildId, normalized, operationId);

        Key key = new Key(Long.parseLong(guildId), normalized, operationId);
        SseEmitter emitter = new SseEmitter(STREAM_LIFETIME_MS);
        Subscriber subscriber = new Subscriber(emitter);

        // The capacity check and insertion are atomic even during reconnect bursts.
        clients.compute(key, (unused, subscribers) -> {
            var present = subscribers == null
                ? new CopyOnWriteArrayList<Subscriber>()
                : subscribers;
            if (present.size() >= MAX_CLIENTS_PER_OPERATION) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Limite de conexões de progresso");
            }
            present.add(subscriber);
            return present;
        });

        Runnable cleanup = () -> remove(key, subscriber);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            // Synchronize the DB read AND send with live publications.
            synchronized (subscriber) {
                send(emitter, "state", authorizedState(authentication, guildId, normalized, operationId));
            }
        } catch (RuntimeException failure) {
            cleanup.run();
            emitter.completeWithError(failure);
        }
        return emitter;
    }

    public void publish(long guildId, String kind, long operationId, long stepId) {
        String normalized = normalizeKind(kind);
        if (guildId <= 0 || operationId <= 0 || stepId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referência de progresso inválida");
        }
        if (!store.existsOperationStep(guildId, normalized, operationId, stepId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa não encontrada na guild");
        }
        Key key = new Key(guildId, normalized, operationId);
        var subscribers = clients.get(key);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        // DB read and send are ordered per subscriber. A late initial
        // snapshot can therefore never overwrite a newer update.
        for (Subscriber subscriber : subscribers) {
            try {
                synchronized (subscriber) {
                    Object state = controlPlane.trustedOperationState(guildId, normalized, operationId);
                    if (state != null) {
                        send(subscriber.emitter, "state", state);
                    }
                }
            } catch (RuntimeException failure) {
                remove(key, subscriber);
                subscriber.emitter.complete();
            }
        }
    }

    private Object authorizedState(Authentication auth, String guildId, String kind, long id) {
        return "SNAPSHOT".equals(kind)
            ? controlPlane.snapshotOperation(auth, guildId, id)
            : controlPlane.restoreOperation(auth, guildId, id);
    }

    private static String normalizeKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!"RESTORE".equals(normalized) && !"SNAPSHOT".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de operação inválido");
        }
        return normalized;
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException error) {
            throw new IllegalStateException("Não foi possível entregar o evento", error);
        }
    }

    private void remove(Key key, Subscriber subscriber) {
        clients.computeIfPresent(key, (unused, subscribers) -> {
            subscribers.remove(subscriber);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    private record Key(long guildId, String kind, long operationId) {}
    private record Subscriber(SseEmitter emitter) {}
}
