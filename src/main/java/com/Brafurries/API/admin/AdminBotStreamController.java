package com.Brafurries.API.admin;

import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated by the existing /admin/** = ROLE_ADMIN policy. */
@RestController
@Validated
@RequestMapping("/admin/bot")
public class AdminBotStreamController {
    private final AdminBotLiveStreamService service;

    public AdminBotStreamController(AdminBotLiveStreamService service) {
        this.service = service;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
        @RequestParam(required = false) String level,
        @RequestParam(required = false) @Min(0) Long after,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
        Authentication authentication
    ) {
        Long resumed = after;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                long cursor = Long.parseLong(lastEventId);
                if (cursor < 0) throw new NumberFormatException("negative");
                resumed = resumed == null ? cursor : Math.max(resumed, cursor);
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor SSE inválido");
            }
        }
        SseEmitter emitter = service.subscribe(level, resumed, authentication);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .body(emitter);
    }
}
