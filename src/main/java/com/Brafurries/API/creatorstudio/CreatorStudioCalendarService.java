package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioCalendarDtos.*;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Private planning dates are independent of workflow, status and due date. */
@Service
public class CreatorStudioCalendarService {
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;
    private static final String STEP = """
        SELECT 'STEP' AS kind,c.id AS commission_id,s.id AS step_id,c.title,c.client_name,
               s.name AS step_name,c.status AS commission_status,s.status AS step_status,
               s.planned_on AS event_on,c.requested_on,s.position AS step_position
          FROM creator_studio_commission_steps s
          JOIN creator_studio_commissions c
            ON c.owner_user_id=s.owner_user_id AND c.id=s.commission_id
         WHERE s.owner_user_id=?
        """;
    private static final String PRODUCTION = """
        SELECT 'PRODUCTION' AS kind,c.id AS commission_id,NULL AS step_id,c.title,c.client_name,
               NULL AS step_name,c.status AS commission_status,NULL AS step_status,
               c.planned_on AS event_on,c.requested_on,-1 AS step_position
          FROM creator_studio_commissions c WHERE c.owner_user_id=?
        """;
    private static final String DELIVERY = """
        SELECT 'DELIVERY' AS kind,c.id AS commission_id,NULL AS step_id,c.title,c.client_name,
               NULL AS step_name,c.status AS commission_status,NULL AS step_status,
               c.due_on AS event_on,c.requested_on,-1 AS step_position
          FROM creator_studio_commissions c WHERE c.owner_user_id=?
        """;
    private static final String NO_STEPS = """
        NOT EXISTS (SELECT 1 FROM creator_studio_commission_steps s
                     WHERE s.owner_user_id=c.owner_user_id AND s.commission_id=c.id)
        """;
    private static final RowMapper<Item> MAPPER = (rs, i) -> {
        long step = rs.getLong("step_id");
        Long stepId = rs.wasNull() ? null : step;
        Date date = rs.getDate("event_on");
        return new Item(Kind.valueOf(rs.getString("kind")),rs.getLong("commission_id"),stepId,
            rs.getString("title"),rs.getString("client_name"),rs.getString("step_name"),
            rs.getString("commission_status"),rs.getString("step_status"),
            date == null ? null : date.toLocalDate());
    };

    public CreatorStudioCalendarService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    private static void validateDate(LocalDate date) {
        if (date != null && (date.getYear() < 1900 || date.getYear() > 9999))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Data inválida");
    }

    private static long offset(int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Paginação inválida");
        return (long)(page-1)*size;
    }

    private static Page page(List<Item> items,int size) {
        return new Page(items.size()>size ? List.copyOf(items.subList(0,size)) : items,items.size()>size);
    }

    @Transactional(readOnly = true)
    public Page calendar(Authentication auth,LocalDate start,LocalDate end,int page,int size) {
        int owner = access.requirePro(auth);
        validateDate(start); validateDate(end);
        if (start == null || end == null || end.isBefore(start) || ChronoUnit.DAYS.between(start,end)>41)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Intervalo de até 42 dias");
        long skip = offset(page,size);
        return page(jdbc.query(STEP+" AND s.planned_on BETWEEN ? AND ? UNION ALL "+
            PRODUCTION+" AND c.planned_on BETWEEN ? AND ? UNION ALL "+
            DELIVERY+" AND c.due_on BETWEEN ? AND ? "+
            " ORDER BY event_on,commission_id,step_position,kind,step_id LIMIT ? OFFSET ?",
            MAPPER,owner,Date.valueOf(start),Date.valueOf(end),owner,Date.valueOf(start),Date.valueOf(end),
            owner,Date.valueOf(start),Date.valueOf(end),size+1,skip),size);
    }

    @Transactional(readOnly = true)
    public Page unplanned(Authentication auth,int page,int size) {
        int owner = access.requirePro(auth);
        long skip = offset(page,size);
        return page(jdbc.query(STEP+
            " AND s.planned_on IS NULL AND s.status<>'DONE' AND c.status<>'COMPLETED' UNION ALL "+
            PRODUCTION+" AND c.planned_on IS NULL AND c.status<>'COMPLETED' AND "+NO_STEPS+
            " ORDER BY requested_on,commission_id,step_position,step_id LIMIT ? OFFSET ?",
            MAPPER,owner,owner,size+1,skip),size);
    }

    @Transactional(readOnly = true)
    public List<Item> commission(Authentication auth,long id) {
        int owner = access.requirePro(auth);
        assertOwned(owner,id,false);
        // Preserve an existing general plan if steps are added later; never
        // silently discard it. New general plans require a step-less order.
        return jdbc.query(STEP+" AND c.id=? UNION ALL "+PRODUCTION+
            " AND c.id=? AND (c.planned_on IS NOT NULL OR "+NO_STEPS+")"+
            " ORDER BY step_position,step_id",MAPPER,owner,id,owner,id);
    }

    private String assertOwned(int owner,long id,boolean lock) {
        List<String> status = jdbc.query("SELECT status FROM creator_studio_commissions WHERE owner_user_id=? AND id=?"+
            (lock ? " FOR UPDATE" : ""), (rs,i)->rs.getString("status"),owner,id);
        if (status.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pedido não encontrado");
        return status.get(0);
    }

    @Transactional
    public void plan(Authentication auth,long id,Long stepId,Plan input) {
        int owner = access.requirePro(auth);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe a data planejada");
        validateDate(input.plannedOn());
        String status = assertOwned(owner,id,true);
        if ("COMPLETED".equals(status)) throw conflict("Pedido concluído. Reabra para planejar.");
        Date date = input.plannedOn()==null ? null : Date.valueOf(input.plannedOn());
        if (stepId != null) {
            List<String> steps = jdbc.query("""
                SELECT status FROM creator_studio_commission_steps
                 WHERE owner_user_id=? AND commission_id=? AND id=? FOR UPDATE
                """,(rs,i)->rs.getString("status"),owner,id,stepId);
            if (steps.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Etapa não encontrada");
            if ("DONE".equals(steps.get(0))) throw conflict("Etapa concluída. Reabra para planejar.");
            jdbc.update("""
                UPDATE creator_studio_commission_steps SET planned_on=?
                 WHERE owner_user_id=? AND commission_id=? AND id=?
                """,date,owner,id,stepId);
            jdbc.update("""
                UPDATE creator_studio_commissions SET updated_at=UTC_TIMESTAMP(6)
                 WHERE owner_user_id=? AND id=?
                """,owner,id);
        } else {
            if (date != null) {
                Integer eligible = jdbc.queryForObject("SELECT COUNT(*) FROM creator_studio_commissions c"+
                    " WHERE c.owner_user_id=? AND c.id=? AND (c.planned_on IS NOT NULL OR "+NO_STEPS+")",
                    Integer.class,owner,id);
                if (eligible == null || eligible != 1)
                    throw conflict("Planeje as etapas deste pedido individualmente.");
            }
            jdbc.update("""
                UPDATE creator_studio_commissions SET planned_on=?
                 WHERE owner_user_id=? AND id=?
                """,date,owner,id);
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}
