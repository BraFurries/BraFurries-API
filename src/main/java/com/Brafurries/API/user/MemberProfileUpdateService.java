package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileRequest;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileResponse;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberProfileUpdateService {

    private final UserRepository userRepository;

    public MemberProfileUpdateService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UpdateMemberProfileResponse updateDisplayName(
        String authenticationPrincipal,
        UpdateMemberProfileRequest request
    ) {
        String normalizedEmail = normalizeAuthenticationPrincipal(authenticationPrincipal);
        String normalizedDisplayName = normalizeDisplayName(request.displayName());

        User user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));

        int updatedRows = userRepository.updateDisplayNameById(user.getId(), normalizedDisplayName);
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado");
        }

        return new UpdateMemberProfileResponse(user.getId(), normalizedDisplayName);
    }

    private String normalizeAuthenticationPrincipal(String authenticationPrincipal) {
        if (authenticationPrincipal == null || authenticationPrincipal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário autenticado inválido");
        }

        return authenticationPrincipal.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("Nome de exibição é obrigatório");
        }

        String normalized = displayName.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Nome de exibição é obrigatório");
        }
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("Nome de exibição deve ter no máximo 32 caracteres");
        }

        return normalized;
    }
}
