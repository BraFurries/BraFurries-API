package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileRequest;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberProfileUpdateServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void updateDisplayNameUsesNormalizedAuthenticatedPrincipalAndOnlyChangesDisplayName() {
        User user = user();
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(userRepository.updateDisplayNameById(42, "Novo Nome")).thenReturn(1);

        MemberProfileUpdateService service = new MemberProfileUpdateService(userRepository);

        UpdateMemberProfileResponse response = service.updateDisplayName(
            " MEMBER@EXAMPLE.COM ",
            new UpdateMemberProfileRequest("  Novo Nome  ")
        );

        assertEquals(42, response.userId());
        assertEquals("Novo Nome", response.displayName());
        verify(userRepository).findByEmail("member@example.com");

        verify(userRepository).updateDisplayNameById(42, "Novo Nome");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateDisplayNameRejectsBlankNameAfterTrim() {
        MemberProfileUpdateService service = new MemberProfileUpdateService(userRepository);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.updateDisplayName("member@example.com", new UpdateMemberProfileRequest("   "))
        );

        assertEquals("Nome de exibição é obrigatório", exception.getMessage());
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateDisplayNameRejectsNameLongerThanContractLimit() {
        MemberProfileUpdateService service = new MemberProfileUpdateService(userRepository);

        assertThrows(
            IllegalArgumentException.class,
            () -> service.updateDisplayName("member@example.com", new UpdateMemberProfileRequest("a".repeat(33)))
        );

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateDisplayNameDoesNotUseAnyUserIdentifierOtherThanAuthenticatedPrincipal() {
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user()));
        when(userRepository.updateDisplayNameById(42, "Novo Nome")).thenReturn(1);

        MemberProfileUpdateService service = new MemberProfileUpdateService(userRepository);

        service.updateDisplayName("member@example.com", new UpdateMemberProfileRequest("Novo Nome"));

        verify(userRepository, never()).findById(any());
        verify(userRepository).findByEmail("member@example.com");
    }

    @Test
    void updateDisplayNameRejectsMissingAuthenticatedUser() {
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.empty());
        MemberProfileUpdateService service = new MemberProfileUpdateService(userRepository);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.updateDisplayName("member@example.com", new UpdateMemberProfileRequest("Novo Nome"))
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateDisplayNameAccepts32CharactersAfterTrim() {
        String displayName = "a".repeat(32);
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user()));
        when(userRepository.updateDisplayNameById(42, displayName)).thenReturn(1);

        MemberProfileUpdateService service = new MemberProfileUpdateService(userRepository);

        UpdateMemberProfileResponse response = service.updateDisplayName(
            "member@example.com",
            new UpdateMemberProfileRequest("  " + displayName + "  ")
        );

        assertEquals(displayName, response.displayName());
        verify(userRepository).updateDisplayNameById(42, displayName);
        verify(userRepository, never()).save(any());
    }

    private User user() {
        User user = new User();
        user.setId(42);
        user.setDisplayName("Nome Antigo");
        user.setEmail("member@example.com");
        user.setUsername("member-username");
        user.setProfileImageUrl("https://cdn.example.com/avatar.webp");
        return user;
    }
}
