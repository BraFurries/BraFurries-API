package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.UserPermissionDtos.EventPermissionFlags;
import com.Brafurries.API.user.dto.UserPermissionDtos.EventStaffPermission;
import com.Brafurries.API.user.dto.UserPermissionDtos.OwnedServer;
import com.Brafurries.API.user.dto.UserPermissionDtos.UserPermissionResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserPermissionService {

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final CommunityDiscordRepository communityDiscordRepository;
    private final EventStaffRepository eventStaffRepository;
    private final EventRepository eventRepository;

    public UserPermissionService(
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        CommunityDiscordRepository communityDiscordRepository,
        EventStaffRepository eventStaffRepository,
        EventRepository eventRepository
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.communityDiscordRepository = communityDiscordRepository;
        this.eventStaffRepository = eventStaffRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public UserPermissionResponse getLoggedUserPermissions(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }

        String email = authentication.getName().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));

        List<String> systemPermissions = new ArrayList<>();
        if (authentication.getAuthorities() != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                if (authority != null && authority.getAuthority() != null && !authority.getAuthority().isBlank()) {
                    systemPermissions.add(authority.getAuthority());
                }
            }
        }

        Map<Integer, EventStaffPermission> eventPermissionsByEventId = new LinkedHashMap<>();

        for (EventStaff staff : eventStaffRepository.findByUserId(user.getId())) {
            EventStaffPermission permission = toEventStaffPermission(staff);
            if (permission != null) {
                eventPermissionsByEventId.put(permission.eventId(), permission);
            }
        }

        for (Event ownedEvent : eventRepository.findByHostUserId(user.getId())) {
            if (ownedEvent == null || ownedEvent.getId() == null) {
                continue;
            }

            eventPermissionsByEventId.computeIfAbsent(
                ownedEvent.getId(),
                ignored -> new EventStaffPermission(
                    ownedEvent.getId(),
                    ownedEvent.getEventName(),
                    true,
                    new EventPermissionFlags(false, false, false)
                )
            );
        }

        List<EventStaffPermission> eventStaffPermissions = eventPermissionsByEventId.values().stream()
            .sorted(Comparator.comparing(EventStaffPermission::eventId))
            .toList();

        UserDiscord userDiscord = userDiscordRepository.findByUser(user).orElse(null);
        List<OwnedServer> ownedServers = userDiscord == null
            ? List.of()
            : communityDiscordRepository
                .findByDiscordAdminIdAndActiveTrueOrderByGuildIdAsc(userDiscord.getDiscordUserId())
                .stream()
                .map(community -> new OwnedServer(community.getGuildId(), community.getName()))
                .toList();

        return new UserPermissionResponse(
            authentication.getName(),
            systemPermissions.stream().distinct().sorted().toList(),
            eventStaffPermissions,
            ownedServers
        );
    }

    private EventStaffPermission toEventStaffPermission(EventStaff staff) {
        if (staff == null || staff.getEvent() == null || staff.getEvent().getId() == null) {
            return null;
        }

        boolean isOwner = staff.getEvent().getHostUser() != null
            && staff.getEvent().getHostUser().getId() != null
            && staff.getUser() != null
            && staff.getUser().getId() != null
            && staff.getEvent().getHostUser().getId().equals(staff.getUser().getId());

        return new EventStaffPermission(
            staff.getEvent().getId(),
            staff.getEvent().getEventName(),
            isOwner,
            new EventPermissionFlags(
                Boolean.TRUE.equals(staff.getMngAgenda()),
                Boolean.TRUE.equals(staff.getEditEvent()),
                Boolean.TRUE.equals(staff.getMngStaff())
            )
        );
    }

}
