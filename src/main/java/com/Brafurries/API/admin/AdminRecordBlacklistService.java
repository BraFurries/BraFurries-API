package com.Brafurries.API.admin;

import com.Brafurries.API.entity.platform.GlobalRecordBlacklistGame;
import com.Brafurries.API.repository.platform.GlobalRecordBlacklistGameRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One global policy for all Coddy guilds: deliberately NOT tenant scoped.
 * Only the ADMIN-only controller may access this service.
 */
@Service
public class AdminRecordBlacklistService {
    private static final Logger log = LoggerFactory.getLogger(AdminRecordBlacklistService.class);
    private final GlobalRecordBlacklistGameRepository games;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public AdminRecordBlacklistService(GlobalRecordBlacklistGameRepository games,
                                       UserRepository users, JdbcTemplate jdbc) {
        this.games = games;
        this.users = users;
        this.jdbc = jdbc;
    }

    public record Game(int id, String name) {}
    public record ListView(List<Game> games) {}
    public record CreateRequest(String name) {}

    private static ListView view(List<GlobalRecordBlacklistGame> entries) {
        return new ListView(entries.stream()
            .map(e -> new Game(e.getId(), e.getGameName())).toList());
    }

    private int actorId(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        return users.findByEmail(auth.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta não encontrada"))
            .getId();
    }

    @Transactional(readOnly = true)
    public ListView list() {
        return view(games.findAllByOrderByGameNameAsc());
    }

    @Transactional
    public ListView add(Authentication actor, CreateRequest request) {
        int userId = actorId(actor);
        if (request == null || request.name() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do jogo obrigatório");
        String name = request.name().trim();
        if (name.isEmpty() || name.length() > 50 || name.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Nome do jogo deve conter de 1 a 50 caracteres válidos");
        if (games.existsByGameNameIgnoreCase(name))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Jogo já está na blacklist global");
        var entry = new GlobalRecordBlacklistGame();
        entry.setGameName(name);
        try {
            games.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicate) {
            // The DB unique index is the final concurrency authority.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Jogo já está na blacklist global");
        }
        jdbc.update("""
            INSERT INTO record_global_blacklist_audit
                (entry_id, game_name, action_type, actor_user_id)
            VALUES (?, ?, 'ADD', ?)
            """, entry.getId(), entry.getGameName(), userId);
        log.info("record_global_blacklist_add actor_id={} entry_id={}", userId, entry.getId());
        return view(games.findAllByOrderByGameNameAsc());
    }

    @Transactional
    public ListView remove(Authentication actor, int entryId) {
        int userId = actorId(actor);
        var entry = games.findById(entryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado"));
        String name = entry.getGameName();
        games.delete(entry);
        games.flush();
        jdbc.update("""
            INSERT INTO record_global_blacklist_audit
                (entry_id, game_name, action_type, actor_user_id)
            VALUES (?, ?, 'REMOVE', ?)
            """, entryId, name, userId);
        log.info("record_global_blacklist_remove actor_id={} entry_id={}", userId, entryId);
        return view(games.findAllByOrderByGameNameAsc());
    }
}
