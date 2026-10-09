package com.Brafurries.API.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.repository.misc.PartnerRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PartnerSlugServiceTest {

    @Mock
    private PartnerRepository partnerRepository;

    @InjectMocks
    private PartnerSlugService service;

    @Test
    void normalizesAccentsAndUsesDeterministicSuffix() {
        when(partnerRepository.existsBySlug("encontro-patas")).thenReturn(true);
        when(partnerRepository.existsBySlug("encontro-patas-2")).thenReturn(false);

        assertEquals("encontro-patas-2", service.uniqueSlug(" Encontro Pátas! ", null));
    }

    @Test
    void keepsCurrentPartnersOwnSlug() {
        Partner current = new Partner();
        current.setSlug("patas");
        when(partnerRepository.existsBySlug("patas")).thenReturn(true);
        when(partnerRepository.findById(7)).thenReturn(Optional.of(current));

        assertEquals("patas", service.uniqueSlug("Patas", 7));
    }

    @Test
    void rejectsValuesThatCannotProduceSlug() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.uniqueSlug("---", null));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
