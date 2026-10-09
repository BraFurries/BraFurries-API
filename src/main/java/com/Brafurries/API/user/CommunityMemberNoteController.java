package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityMemberNoteDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}/members/{userId}/notes")
public class CommunityMemberNoteController {
    private final CommunityMemberNoteService service;

    public CommunityMemberNoteController(CommunityMemberNoteService service) {
        this.service = service;
    }

    @Operation(summary = "Lista notas internas do membro na Community")
    @GetMapping
    public List<CommunityMemberNoteResponse> list(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer userId,
        @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        return service.list(auth, communityId, userId, includeArchived);
    }

    @Operation(summary = "Cria uma nota interna para o membro na Community")
    @PostMapping
    public CommunityMemberNoteResponse create(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer userId,
        @Valid @RequestBody SaveCommunityMemberNoteRequest request
    ) {
        return service.create(auth, communityId, userId, request);
    }

    @Operation(summary = "Atualiza uma nota interna ativa")
    @PutMapping("/{noteId}")
    public CommunityMemberNoteResponse update(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer userId,
        @PathVariable Long noteId,
        @Valid @RequestBody SaveCommunityMemberNoteRequest request
    ) {
        return service.update(auth, communityId, userId, noteId, request);
    }

    @Operation(summary = "Arquiva uma nota interna sem apagá-la")
    @PatchMapping("/{noteId}/archive")
    public CommunityMemberNoteResponse archive(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer userId,
        @PathVariable Long noteId
    ) {
        return service.archive(auth, communityId, userId, noteId);
    }
}
