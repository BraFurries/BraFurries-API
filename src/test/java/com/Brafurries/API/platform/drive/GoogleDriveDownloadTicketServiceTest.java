package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveDownloadTicketServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final UserRepository users=mock(UserRepository.class);
    private final UserTokenRepository tokens=mock(UserTokenRepository.class);
    private final GoogleDriveDownloadTicketService service=new GoogleDriveDownloadTicketService(jdbc,
        mock(PlatformAuthorizationService.class),users,tokens);
    private final GoogleDriveDownloadTicketService.Target target=new GoogleDriveDownloadTicketService.Target(8,1,
        new GoogleDriveConnectionService.FolderScope("subject","root_folder"),"anchor_folder","child_folder","file_123");
    private final String secret="A".repeat(43);

    @Test void malformedTicketDoesNotQueryDatabase(){
        assertThrows(ResponseStatusException.class,()->service.consume("invalid"));
        verifyNoInteractions(jdbc);
    }

    @Test void consumedOrExpiredTicketCannotBeRedeemed(){
        when(jdbc.query(contains("consumed_at IS NULL"),org.mockito.ArgumentMatchers.<RowMapper<GoogleDriveDownloadTicketService.Target>>any(),any(byte[].class)))
            .thenReturn(List.of(target),List.of());
        when(jdbc.update(contains("SET consumed_at"),any(byte[].class))).thenReturn(1);
        assertEquals(target,service.consume(secret));
        assertThrows(ResponseStatusException.class,()->service.consume(secret));
        verify(jdbc,times(1)).update(contains("SET consumed_at"),any(byte[].class));
    }

    @Test void changedAuthVersionRejectsPreviouslyIssuedTicket(){
        when(jdbc.queryForList(contains("auth_version=?"),eq(String.class),eq(8),eq(1L))).thenReturn(List.of());
        assertThrows(ResponseStatusException.class,()->service.authenticate(target));
        verifyNoInteractions(users,tokens);
    }
}
