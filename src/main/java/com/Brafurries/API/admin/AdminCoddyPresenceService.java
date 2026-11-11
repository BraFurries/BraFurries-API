package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCoddyPresenceDtos.Response;
import com.Brafurries.API.admin.dto.AdminCoddyPresenceDtos.Server;
import com.Brafurries.API.admin.dto.AdminCoddyPresenceDtos.Summary;
import com.Brafurries.API.admin.dto.AdminDtos.AdminPagination;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global ADMIN-only Coddy presence directory. Community remains the tenant;
 * this cross-community view must never be reused for tenant-scoped endpoints.
 */
@Service
public class AdminCoddyPresenceService {
    private final CommunityDiscordRepository guilds;
    private final AdminCoddyReachService reachService;

    public AdminCoddyPresenceService(CommunityDiscordRepository guilds, AdminCoddyReachService reachService) {
        this.guilds = guilds;
        this.reachService = reachService;
    }

    @Transactional(readOnly = true)
    public Response list(int page, int pageSize, String search, String status, Integer communityId) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagina ou tamanho invalido");
        }
        if (communityId != null && communityId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community ID invalido");
        }

        String query = search == null ? "" : search.trim();
        if (query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Busca excede 100 caracteres");
        }

        String normalizedStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        Boolean activeFilter = switch (normalizedStatus) {
            case "", "all" -> null;
            case "active" -> true;
            case "inactive" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status de presenca invalido");
        };

        var pageResult = guilds.findAll(
            filter(query, activeFilter, communityId),
            PageRequest.of(page - 1, pageSize, Sort.by(
                Sort.Order.desc("active"), Sort.Order.asc("name"), Sort.Order.asc("id")
            ))
        );

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        var totals = guilds.fetchGeneralDiscordMetrics();
        var reach = reachService.read();
        long knownServers = safe(totals.getTotalDiscordCommunities());
        long activeServers = safe(totals.getActiveDiscordCommunities());

        Summary summary = new Summary(
            knownServers,
            activeServers,
            reach.reconciledServers(),
            Math.max(knownServers - activeServers, 0),
            reach.uniqueMembers(),
            guilds.countByLastBotJoinedAtGreaterThanEqual(nowUtc.minusDays(7)),
            guilds.countByLastBotLeftAtGreaterThanEqual(nowUtc.minusDays(7)),
            guilds.countByLastBotJoinedAtGreaterThanEqual(nowUtc.minusDays(30)),
            guilds.countByLastBotLeftAtGreaterThanEqual(nowUtc.minusDays(30))
        );

        return new Response(
            summary,
            pageResult.getContent().stream().map(this::toServer).toList(),
            new AdminPagination(page, pageSize, pageResult.getTotalElements(), pageResult.getTotalPages())
        );
    }

    private Specification<CommunityDiscord> filter(String search, Boolean active, Integer communityId) {
        Long exactGuildId = parseDiscordGuildId(search);
        String term = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";

        return (root, criteria, cb) -> {
            var community = root.join("community", JoinType.INNER);
            List<Predicate> conditions = new ArrayList<>();
            if (active != null) {
                conditions.add(cb.equal(root.get("active"), active));
            }
            if (communityId != null) {
                conditions.add(cb.equal(community.get("id"), communityId));
            }
            if (!search.isEmpty()) {
                List<Predicate> matching = new ArrayList<>();
                matching.add(cb.like(cb.lower(root.get("name")), term, '\\'));
                matching.add(cb.like(cb.lower(community.get("name")), term, '\\'));
                if (exactGuildId != null) {
                    matching.add(cb.equal(root.get("guildId"), exactGuildId));
                }
                conditions.add(cb.or(matching.toArray(Predicate[]::new)));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    private Long parseDiscordGuildId(String search) {
        if (!search.matches("[0-9]{1,20}")) {
            return null;
        }
        try {
            return Long.parseLong(search);
        } catch (NumberFormatException ignored) {
            // An out-of-range Discord ID is not truncated or rounded.
            return null;
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Server toServer(CommunityDiscord guild) {
        return new Server(
            guild.getCommunity().getId(),
            guild.getCommunity().getName(),
            String.valueOf(guild.getGuildId()),
            guild.getName(),
            Boolean.TRUE.equals(guild.getActive()),
            guild.getUsersQuantity() == null ? 0 : Math.max(guild.getUsersQuantity(), 0),
            guild.getMembershipSyncState(),
            utc(guild.getLastBotJoinedAt()),
            utc(guild.getLastBotLeftAt()),
            guild.getLastFullReconciliationAt(),
            guild.getLastMembershipEventAt()
        );
    }

    private Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private long safe(Number number) {
        return number == null ? 0L : number.longValue();
    }
}
