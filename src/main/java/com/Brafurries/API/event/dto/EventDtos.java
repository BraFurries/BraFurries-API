package com.Brafurries.API.event.dto;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class EventDtos {
    public record UserEventItemDto(
            Integer id,
            String eventName,
            String city,
            String localeAbbrev,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            Boolean approved,
            Boolean partnerEvent,
            StaffStatusDto staffStatus
    ) {
    }

    public record StaffStatusDto(
            Boolean isStaff,
            Boolean canEdit
    ) {
    }

    public record BriefEventItemDto(
            Integer id,
            EventHostDto host,
            String eventName,
            String city,
            String localeAbbrev,
            String localeName,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            Boolean isEvent,
            Boolean partnerEvent,
            Boolean free,
            String eventLogoUrl,
            java.util.List<String> highlights,
            java.util.List<String> permissions
    ) {
        public BriefEventItemDto(
                Integer id,
                EventHostDto host,
                String eventName,
                String city,
                String localeAbbrev,
                String localeName,
                LocalDateTime startingDatetime,
                Boolean isEvent,
                Boolean partnerEvent,
                Boolean free,
                String eventLogoUrl,
                java.util.List<String> highlights,
                java.util.List<String> permissions
        ) {
            this(id, host, eventName, city, localeAbbrev, localeName, startingDatetime, null, isEvent, partnerEvent, free, eventLogoUrl, highlights, permissions);
        }
    }

    public record CreateEventRequestDto(
            Integer localeId,
            String eventName,
            String description,
            String city,
            String address,
            String pointName,
            Double price,
            Double maxPrice,
            Boolean priceConfirmed,
            String groupChatLink,
            String website,
            String ticketUrl,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            Boolean outOfTickets,
            Boolean salesEnded,
            @NotNull Boolean isEvent
    ) {
    }

    public record EventEditRequestDto(
            Integer localeId,
            String eventName,
            String description,
            String city,
            String address,
            String pointName,
            Double price,
            Double maxPrice,
            Boolean priceConfirmed,
            String groupChatLink,
            String website,
            String ticketUrl,
            Boolean outOfTickets,
            Boolean salesEnded,
            Boolean approved,
            String gcalEventId
    ) {
    }

    public record EventLogoResponseDto(
            Integer eventId,
            String eventLogoUrl
    ) {
    }

    public record ManageStaffRequestDto(
            @JsonAlias("id")
            Integer userId,
            String email,
            Boolean mngAgenda,
            Boolean editEvent,
            Boolean mngStaff,
            String cargo
    ) {
    }

    public record EditStaffRequestDto(
            Boolean mngAgenda,
            Boolean editEvent,
            Boolean mngStaff,
            String cargo
    ) {
    }

    public record EventStaffDto(
            Integer userId,
            String displayName,
            Boolean mngAgenda,
            Boolean editEvent,
            Boolean mngStaff,
            String cargo
    ) {
    }

    public record PublicEventStaffDto(
            Integer userId,
            String displayName,
            String cargo
    ) {
    }

    public record ScheduleEventRequestDto(
            String newStartingDatetime,
            String newEndingDatetime
    ) {
    }

    public record CreateEventTransferRequestDto(
            Integer targetUserId,
            String targetEmail,
            String reason
    ) {
    }

    public record EventTransferRequestDto(
            Long id,
            Integer eventId,
            String eventName,
            Integer originalOwnerUserId,
            String originalOwnerDisplayName,
            Integer targetUserId,
            String targetDisplayName,
            String reason,
            LocalDateTime createdAt
    ) {
    }

    public record InviteDto(
            Boolean valid,
            String type,
            String status,
            Long id,
            Integer requestedByUserId,
            String requestedByDisplayName,
            Integer targetUserId,
            String targetDisplayName,
            String reason,
            LocalDateTime createdAt,
            LocalDateTime respondedAt,
            EventTransferInviteDataDto inviteData
    ) {
    }

    public record EventTransferInviteDataDto(
            Integer eventId,
            String eventName,
            Integer originalOwnerUserId,
            String originalOwnerDisplayName
    ) {
    }

    public record EventBasicDto(
            Integer id,
            String eventName,
            String city,
            String localeAbbrev,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            Double price,
            Boolean outOfTickets,
            Boolean approved
    ) {
    }

    public record EventHostDto(
            Integer id,
            String displayName
    ) {
    }

    public record EventFullDto(
            Integer id,
            EventHostDto host,
            Boolean partnerEvent,
            Integer localeId,
            String localeAbbrev,
            String localeName,
            String eventName,
            String description,
            String city,
            String address,
            String pointName,
            Double price,
            Double maxPrice,
            Boolean priceConfirmed,
            String groupChatLink,
            String website,
            String ticketUrl,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            String eventLogoUrl,
            Boolean outOfTickets,
            Boolean salesEnded,
            Boolean approved,
            Boolean isEvent,
            String gcalEventId,
            java.util.List<PublicEventStaffDto> staffs
    ) {
    }

    public record PendingApprovalEventDto(
            Integer id,
            String eventName,
            String city,
            String localeAbbrev,
            String localeName,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            Boolean isEvent,
            Boolean free,
            String hostDisplayName,
            String eventLogoUrl,
            LocalDateTime createdAt,
            java.util.List<String> highlights
    ) {
        public PendingApprovalEventDto(
                Integer id,
                String eventName,
                String city,
                String localeAbbrev,
                String localeName,
                LocalDateTime startingDatetime,
                Boolean isEvent,
                Boolean free,
                String hostDisplayName,
                String eventLogoUrl,
                LocalDateTime createdAt,
                java.util.List<String> highlights
        ) {
            this(id, eventName, city, localeAbbrev, localeName, startingDatetime, null, isEvent, free, hostDisplayName, eventLogoUrl, createdAt, highlights);
        }
    }

    public record MemberEventMinidashDto(
            Long activeOwnerEvents,
            Long activeStaffEvents,
            Long pendingReviewEvents
    ) {
    }

    public record AdminEventMinidashDto(
            Long activeEvents,
            Long partnerEvents,
            Long pendingApprovalEvents
    ) {
    }

    public record EventAvailabilityResponseDto(
            LocalDate date,
            Integer localeId,
            String city,
            Boolean localeAvailable,
            Boolean cityAvailable,
            java.util.List<NearbyEventDto> nearbyEvents
    ) {
    }

    public record NearbyEventDto(
            String name,
            String city,
            Integer localeId,
            String localeAbbrev,
            String localeName,
            LocalDateTime date,
            LocalDateTime endingDate,
            String type
    ) {
    }
}
