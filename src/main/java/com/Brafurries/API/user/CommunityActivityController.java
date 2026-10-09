package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityActivityDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}/activity")
@Tag(name = "Community Activity", description = "Administrative activity timeline for Community Owner/Admin")
public class CommunityActivityController {
    private final CommunityActivityService service;

    public CommunityActivityController(CommunityActivityService service) {
        this.service = service;
    }

    @Operation(summary = "Lista a atividade administrativa da Community")
    @GetMapping
    public ActivityPage list(
        Authentication authentication,
        @PathVariable Integer communityId,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String actorUserId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "30") String limit
    ) {
        return service.list(
            authentication,
            communityId,
            category,
            parseInteger(actorUserId, "actorUserId"),
            parseDateTime(from, "from"),
            parseDateTime(to, "to"),
            cursor,
            parseLimit(limit)
        );
    }

    @Operation(summary = "Lista atores disponíveis para filtrar a atividade da Community")
    @GetMapping("/actors")
    public List<ActivityActorOption> actors(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return service.listActors(authentication, communityId);
    }

    private Integer parseInteger(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                field + " inválido"
            );
        }
    }

    private int parseLimit(String raw) {
        Integer parsed = parseInteger(raw == null || raw.isBlank() ? "30" : raw, "limit");
        return parsed == null ? 30 : parsed;
    }

    private LocalDateTime parseDateTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                field + " inválido"
            );
        }
    }
}
