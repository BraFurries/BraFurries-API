package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerifiedSocialIdentityServiceTest {
    @Mock UserSocialIdentityRepository repository;

    private VerifiedSocialIdentityService service() {
        return new VerifiedSocialIdentityService(repository);
    }

    private User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    @Test
    void canBindIndependentProviderSubjectsToOneVerifiedUser() {
        User owner = user(10);
        service().bindProvenSubject(owner, "google", "g-sub-1", "Owner@Example.com");
        service().bindProvenSubject(owner, "discord", "1234567890", null);

        ArgumentCaptor<UserSocialIdentity> added = ArgumentCaptor.forClass(UserSocialIdentity.class);
        verify(repository, times(2)).saveAndFlush(added.capture());
        assertEquals("google", added.getAllValues().get(0).getProvider());
        assertEquals("g-sub-1", added.getAllValues().get(0).getProviderSubject());
        assertEquals("owner@example.com", added.getAllValues().get(0).getVerifiedEmail());
        assertEquals("discord", added.getAllValues().get(1).getProvider());
        assertEquals("1234567890", added.getAllValues().get(1).getProviderSubject());
        assertEquals(10, added.getAllValues().get(1).getUser().getId());
    }

    @Test
    void sameSubjectCannotEverBeClaimedByTwoSiteUsers() {
        User owner = user(10);
        User attacker = user(20);
        UserSocialIdentity existing = new UserSocialIdentity();
        existing.setUser(owner);
        when(repository.findByProviderAndProviderSubject("google", "g-sub-1"))
            .thenReturn(Optional.of(existing));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service().bindProvenSubject(attacker, "google", "g-sub-1", "attacker@example.com"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reverifiedSameSubjectIsIdempotentAndRefreshesMetadataWithoutNewOwner() {
        User owner = user(10);
        UserSocialIdentity existing = new UserSocialIdentity();
        existing.setUser(owner);
        existing.setProvider("google");
        existing.setProviderSubject("g-sub-1");
        when(repository.findByProviderAndProviderSubject("google", "g-sub-1"))
            .thenReturn(Optional.of(existing));

        service().bindProvenSubject(owner, "GOOGLE", "g-sub-1", "NEW@example.com");

        assertNotNull(existing.getLastVerifiedAt());
        assertEquals("new@example.com", existing.getVerifiedEmail());
        verify(repository).save(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void raceOnUniqueProviderSubjectFailsClosed() {
        when(repository.saveAndFlush(any(UserSocialIdentity.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate subject"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service().bindProvenSubject(user(10), "google", "g-sub-1", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void rejectsInvalidProofSubjectsBeforeAccessingDatabase() {
        assertThrows(IllegalArgumentException.class,
            () -> service().bindProvenSubject(user(10), "github", "unapproved", null));
        assertThrows(IllegalArgumentException.class,
            () -> service().bindProvenSubject(user(10), "google", " ", null));
        assertThrows(IllegalArgumentException.class,
            () -> service().bindProvenSubject(user(10), "discord", " 123 ", null));
        assertThrows(IllegalArgumentException.class,
            () -> service().ownerOf("google", null));
        verifyNoInteractions(repository);
    }
}
