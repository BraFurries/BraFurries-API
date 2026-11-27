package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.Event;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Listing fragment: the existing controller remains responsible for access checks. */
public interface EventListingRepository {
    Page<Event> findUserEventsListing(
            String userEmail,
            boolean canSeeAllEvents,
            boolean canManageAllEvents,
            Boolean approved,
            String state,
            String location,
            String name,
            LocalDateTime monthStart,
            LocalDateTime monthEnd,
            LocalDateTime weekStart,
            LocalDateTime weekEnd,
            String type,
            String scope,
            Integer relatedUserId,
            boolean staffOnlyManaged,
            Boolean upcoming,
            LocalDateTime now,
            Pageable pageable
    );
}
