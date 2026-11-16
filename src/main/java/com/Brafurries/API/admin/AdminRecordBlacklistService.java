package com.Brafurries.API.admin;

import com.Brafurries.API.entity.community.BlacklistedGame;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.BlacklistedGameRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminRecordBlacklistService {
    private static final Logger log = LoggerFactory.getLogger(AdminRecordBlacklistService.class);
    private final CommunityDiscordRepository guilds;
    private final BlacklistedGameRepository games;

    public AdminRecordBlacklistService(CommunityDiscordRepository guilds,
                                       BlacklistedGameRepository games) {
        this.guilds = guilds;
        this.games = games;
    }

    public record Guild(int communityId, String communityName, String guildId, String guildName) {}
    public record Game(int id, String name) {}
    public record ListView(List<Game> games) {}
    public record CreateRequest(String name) {}

    public List<Guild> guildOptions() {
        return guilds.findAllByActiveTrueOrderByGuildIdAsc().stream().map(g ->
            new Guild(g.getCommunity().getId(), g.getCommunity().getName(),
                Long.toString(g.getGuildId()), g.getName())
        ).toList();
    }

    private static long snowflake(String guildId) {
        if (guildId == null || !guildId.matches("[1-9][0-9]{0,18}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Servidor Discord inválido");
        }
        try {
            return Long.parseLong(guildId);
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Servidor Discord inválido");
        }
    }

    private CommunityDiscord resolveGuild(int communityId, String guildId, boolean lock) {
        if (communityId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community inválida");
        }
        long id = snowflake(guildId);
        List<CommunityDiscord> matches = lock
            ? guilds.lockActiveGuildInCommunity(id, communityId)
            : guilds.findAllByActiveTrueOrderByGuildIdAsc().stream()
                .filter(g -> Objects.equals(g.getGuildId(), id)
                    && g.getCommunity() != null
                    && Objects.equals(g.getCommunity().getId(), communityId))
                .toList();
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Servidor não pertence à Community ativa solicitada");
        if (matches.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Mais de um vínculo Discord para esta Community; operação bloqueada");
        return matches.getFirst();
    }

    private static ListView toView(List<BlacklistedGame> entries) {
        return new ListView(entries.stream()
            .map(item -> new Game(item.getId(), item.getGameName())).toList());
    }

    @Transactional(readOnly = true)
    public ListView list(int communityId, String guildId) {
        CommunityDiscord guild = resolveGuild(communityId, guildId, false);
        return toView(games.findAllByCommunityDiscordGuildIdOrderByGameNameAsc(guild.getGuildId()));
    }

    @Transactional
    public ListView add(Authentication actor, int communityId, String guildId, CreateRequest request) {
        if (request == null || request.name() == null) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Nome do jogo obrigatório");
        String name = request.name().trim();
        if (name.isEmpty() || name.length() > 50 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Nome do jogo deve conter de 1 a 50 caracteres válidos");
        }
        CommunityDiscord guild = resolveGuild(communityId, guildId, true);
        var current = games.findAllByCommunityDiscordGuildIdOrderByGameNameAsc(guild.getGuildId());
        if (current.stream().anyMatch(g -> g.getGameName().toLowerCase(Locale.ROOT)
                                         .equals(name.toLowerCase(Locale.ROOT)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Jogo já está na blacklist");
        }
        var entry = new BlacklistedGame();
        entry.setCommunityDiscord(guild);
        entry.setGameName(name);
        games.saveAndFlush(entry);
        log.info("record_blacklist_add actor={} community_id={} guild_id={} game={}",
            actor.getName(), communityId, guildId, name);
        return toView(games.findAllByCommunityDiscordGuildIdOrderByGameNameAsc(guild.getGuildId()));
    }

    @Transactional
    public ListView remove(Authentication actor, int communityId, String guildId, int gameId) {
        CommunityDiscord guild = resolveGuild(communityId, guildId, true);
        var existing = games.findById(gameId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrada não encontrada"));
        if (!Objects.equals(existing.getCommunityDiscord().getGuildId(), guild.getGuildId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entrada não pertence a este servidor");
        }
        games.delete(existing);
        games.flush();
        log.info("record_blacklist_remove actor={} community_id={} guild_id={} entry_id={}",
            actor.getName(), communityId, guildId, gameId);
        return toView(games.findAllByCommunityDiscordGuildIdOrderByGameNameAsc(guild.getGuildId()));
    }
}
