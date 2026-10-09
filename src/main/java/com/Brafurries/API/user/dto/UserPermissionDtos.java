package com.Brafurries.API.user.dto;

import java.util.List;

public class UserPermissionDtos {

    public record UserPermissionResponse(
        String user,
        List<String> systemPermissions,
        List<EventStaffPermission> eventStaffPermissions,
        List<OwnedServer> ownedServers
    ) {
    }

    public record EventStaffPermission(
        Integer eventId,
        String eventName,
        Boolean owner,
        EventPermissionFlags permissions
    ) {
    }

    public record EventPermissionFlags(
        Boolean mngAgenda,
        Boolean editEvent,
        Boolean mngStaff
    ) {
    }

    public record OwnedServer(
        Long guildId,
        String communityName
    ) {
    }
}
