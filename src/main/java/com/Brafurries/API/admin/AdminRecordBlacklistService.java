package com.Brafurries.API.admin;

import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global Coddy record policy: deliberately NOT tenant-scoped.
 * All writes require the controller's platform ADMIN role and are audited.
 * New tables belong exclusively to BraFurries-Database migrations.
 */
@Service
public class AdminRecordBlacklistService {
    private static final Logger log = LoggerFactory.getLogger(AdminRecordBlacklistService.class);
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public AdminRecordBlacklistService(UserRepository users, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    public record Game(int id, String name) {}
    public record ListView(List<Game> games) {}
    public record CreateRequest(String name) {}

    private int actorId(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        return users.findByEmail(auth.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta não encontrada"))
            .getId();
    }

    @Transactional(readOnly = true)
    public ListView list() {
        return listCurrent();
    }

    private ListView listCurrent() {
        return new ListView(jdbc.query(
            "SELECT id, game_name FROM record_global_blacklisted_games ORDER BY game_name, id",
            (rs, row) -> new Game(rs.getInt("id"), rs.getString("game_name"))
        ));
    }

    @Transactional
    public ListView add(Authentication auth, CreateRequest request) {
        int actor = actorId(auth);
        if (request == null || request.name() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome do jogo obrigatório");
        String name = request.name().trim();
        if (name.isEmpty() || name.length() > 50 || name.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Nome do jogo deve conter de 1 a 50 caracteres válidos");
        try {
            // Unique CI-collated key is the concurrency authority.
            jdbc.update("INSERT INTO record_global_blacklisted_games (game_name) VALUES (?)", name);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Jogo já está na blacklist global");
        }
        Integer id = jdbc.queryForObject(
            "SELECT id FROM record_global_blacklisted_games WHERE game_name = ?", Integer.class, name);
        if (id == null) throw new IllegalStateException("Game ID not found after insert");
        jdbc.update("""
            INSERT INTO record_global_blacklist_audit (entry_id, game_name, action_type, actor_user_id)
            VALUES (?, ?, 'ADD', ?)
            """, id, name, actor);
        log.info("record_global_blacklist_add actor_id={} entry_id={}", actor, id);
        return listCurrent();
    }

    @Transactional
    public ListView remove(Authentication auth, int entryId) {
        int actor = actorId(auth);
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id, game_name FROM record_global_blacklisted_games WHERE id = ? FOR UPDATE", entryId);
        if (existing.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado");
        String name = (String) existing.getFirst().get("game_name");
        int deleted = jdbc.update("DELETE FROM record_global_blacklisted_games WHERE id = ?", entryId);
        if (deleted != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Jogo alterado durante exclusão");
        jdbc.update("""
            INSERT INTO record_global_blacklist_audit (entry_id, game_name, action_type, actor_user_id)
            VALUES (?, ?, 'REMOVE', ?)
            """, entryId, name, actor);
        log.info("record_global_blacklist_remove actor_id={} entry_id={}", actor, entryId);
        return listCurrent();
    }
}
