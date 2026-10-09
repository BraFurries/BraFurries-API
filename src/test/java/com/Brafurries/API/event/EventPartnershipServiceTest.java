package com.Brafurries.API.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.repository.misc.PartnerLinkRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventPartnershipServiceTest {

    @Mock
    private PartnerLinkRepository partnerLinkRepository;

    @InjectMocks
    private EventPartnershipService service;

    @Test
    void resolvesPartnerEventsInOneBatch() {
        when(partnerLinkRepository.findActivePartnerEventIds(Set.of(10, 11, 12))).thenReturn(List.of(10, 12));

        Set<Integer> result = service.findActivePartnerEventIds(Set.of(10, 11, 12));

        assertEquals(Set.of(10, 12), result);
        verify(partnerLinkRepository).findActivePartnerEventIds(Set.of(10, 11, 12));
    }

    @Test
    void singleEventUsesDerivedActiveLinkState() {
        when(partnerLinkRepository.findActivePartnerEventIds(Set.of(10))).thenReturn(List.of(10));
        when(partnerLinkRepository.findActivePartnerEventIds(Set.of(11))).thenReturn(List.of());

        assertTrue(service.isPartnerEvent(10));
        assertFalse(service.isPartnerEvent(11));
    }
}
