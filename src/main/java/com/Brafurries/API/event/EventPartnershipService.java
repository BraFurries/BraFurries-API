package com.Brafurries.API.event;

import com.Brafurries.API.repository.misc.PartnerLinkRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventPartnershipService {

    private final PartnerLinkRepository partnerLinkRepository;

    public EventPartnershipService(PartnerLinkRepository partnerLinkRepository) {
        this.partnerLinkRepository = partnerLinkRepository;
    }

    @Transactional(readOnly = true)
    public boolean isPartnerEvent(Integer eventId) {
        return eventId != null && findActivePartnerEventIds(Set.of(eventId)).contains(eventId);
    }

    @Transactional(readOnly = true)
    public Set<Integer> findActivePartnerEventIds(Collection<Integer> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(partnerLinkRepository.findActivePartnerEventIds(eventIds));
    }
}
