package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TemporaryRoleGrantServiceTest {
    @Mock GuildManagementAccessService guildAccess;
    @Mock CommunityAuthorizationService authorization;
    @Mock CommunityDiscordRepository links;
    @Mock CommunityAuditLogRepository audits;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    TemporaryRoleGrantService service;

    @BeforeEach void init() {
        service=new TemporaryRoleGrantService(guildAccess,authorization,links,audits,jdbc);
    }

    private void authorized(boolean editable) {
        var community=new Community();
        community.setId(5);
        var link=new CommunityDiscord();
        link.setId(6);link.setGuildId(123L);
        link.setCommunity(community);link.setActive(true);
        var user=new User();user.setId(7);
        var context=new CommunityAuthorizationService.CommunityAccessContext(
            community,user,true,true,true,Set.of(CommunityCapability.COMMUNITY_ADMIN),
            List.of(),Map.of(),Set.of());
        when(guildAccess.authorize(auth,"123")).thenReturn(new AuthorizedGuild(42L,
            new GuildResources("123",
                List.of(new GuildRole("50","Membro",10,false,editable)),
                List.of(),List.of(),new BotCapabilities(true,true,true,List.of()))));
        when(links.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(authorization.requireCapability(auth,5,CommunityCapability.COMMUNITY_ADMIN))
            .thenReturn(context);
    }

    @Test void cannotManageAnotherCommunityEvenWithDiscordGuildAccess() {
        when(guildAccess.authorize(auth,"123")).thenReturn(
            new AuthorizedGuild(42L,new GuildResources("123",List.of(),List.of(),
                List.of(),new BotCapabilities(true,true,true,List.of()))));
        when(links.findByGuildId(123L)).thenReturn(Optional.empty());
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->service.list(auth,"123"));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
        verifyNoInteractions(authorization,jdbc,audits);
    }

    @Test void adminCapabilityIsMandatoryForReadAndMutation() {
        var community=new Community();community.setId(5);
        var link=new CommunityDiscord();link.setCommunity(community);link.setActive(true);
        when(guildAccess.authorize(auth,"123")).thenReturn(
            new AuthorizedGuild(42L,new GuildResources("123",List.of(),List.of(),
                List.of(),new BotCapabilities(true,true,true,List.of()))));
        when(links.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(authorization.requireCapability(auth,5,CommunityCapability.COMMUNITY_ADMIN))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->service.list(auth,"123")).getStatusCode());
        verifyNoInteractions(jdbc,audits);
    }

    @Test void rejectsNonEditableRoleBeforeWriting() {
        authorized(false);
        var request=new TemporaryRoleGrantService.Create(
            "ee04a7c9-d325-4ff6-b57c-d7c8eb57a26d","700","50",
            Instant.now().plusSeconds(3600),"Participação temporária");
        var error=assertThrows(ResponseStatusException.class,
            ()->service.create(auth,"123",request));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        verifyNoInteractions(jdbc,audits);
    }

    @Test void rejectsExpiredTimeAndBadIdBeforeWriting() {
        authorized(true);
        var request=new TemporaryRoleGrantService.Create(
            "ee04a7c9-d325-4ff6-b57c-d7c8eb57a26d","700","50",
            Instant.now().minusSeconds(1),"Participação temporária");
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.create(auth,"123",request)).getStatusCode());
        verifyNoInteractions(jdbc,audits);
    }

    @Test void rejectsOversizedDiscordSnowflakeWithoutSql() {
        authorized(true);
        var request=new TemporaryRoleGrantService.Create(
            "ee04a7c9-d325-4ff6-b57c-d7c8eb57a26d",
            "999999999999999999999999999","50",
            Instant.now().plusSeconds(3600),"Participação temporária");
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.create(auth,"123",request)).getStatusCode());
        verifyNoInteractions(jdbc,audits);
    }
}
