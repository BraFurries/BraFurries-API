package com.Brafurries.API.user;
import com.Brafurries.API.user.dto.LogConfigurationDtos.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
@Service public class LogConfigurationService {
 private final GuildManagementAccessService access; private final CoddyLogConfigurationClient coddy;
 public LogConfigurationService(GuildManagementAccessService access, CoddyLogConfigurationClient coddy) { this.access=access; this.coddy=coddy; }
 public LogsState get(Authentication auth,String guildId){ var authorized=access.authorize(auth,guildId); return coddy.get(guildId,authorized.discordUserId()); }
 public LogsState update(Authentication auth,String guildId,String type,UpdateLogRequest request){ var authorized=access.authorize(auth,guildId); return coddy.update(guildId,type,authorized.discordUserId(),request); }
 public void test(Authentication auth,String guildId,String type,TestLogRequest request){ var authorized=access.authorize(auth,guildId); coddy.test(guildId,type,authorized.discordUserId(),request); }
}
