package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.IdentityCandidate;
import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.PropagationRequest;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.UserBan;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IdentityBanPropagationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityBanPropagationService.class);

    private final ConfirmedIdentityClusterService clusters;
    private final UserBanRepository bans;
    private final UserDiscordRepository discordAccounts;
    private final IdentityBanPropagationClient client;

    public IdentityBanPropagationService(
        ConfirmedIdentityClusterService clusters,
        UserBanRepository bans,
        UserDiscordRepository discordAccounts,
        IdentityBanPropagationClient client
    ) {
        this.clusters = clusters;
        this.bans = bans;
        this.discordAccounts = discordAccounts;
        this.client = client;
    }

    public void propagateForConfirmedCluster(Integer seedUserId) {
        try {
            Set<Integer> cluster = clusters.resolveConfirmedUserIds(seedUserId);
            List<UserBan> activeBans = bans.findActiveBansWithDiscordByUserIds(
                cluster,
                LocalDate.now()
            );
            if (activeBans.isEmpty()) {
                return;
            }

            List<IdentityCandidate> identities = discordAccounts.findByUserIdIn(cluster)
                .stream()
                .map(account -> new IdentityCandidate(
                    account.getUser().getId(),
                    String.valueOf(account.getDiscordUserId())
                ))
                .sorted(
                    Comparator.comparing(IdentityCandidate::userId)
                        .thenComparing(IdentityCandidate::discordUserId)
                )
                .toList();
            if (identities.isEmpty()) {
                return;
            }

            for (UserBan ban : activeBans) {
                CommunityDiscord discord = ban.getCommunity().getDiscord();
                if (
                    discord == null
                    || discord.getGuildId() == null
                    || !Boolean.TRUE.equals(discord.getActive())
                ) {
                    log.warn(
                        "Ban {} da Community {} não pôde ser propagado: integração Discord inativa",
                        ban.getId(),
                        ban.getCommunity().getId()
                    );
                    continue;
                }

                try {
                    client.propagate(
                        discord.getGuildId(),
                        new PropagationRequest(
                            ban.getId(),
                            ban.getReason(),
                            identities
                        )
                    );
                } catch (RuntimeException exception) {
                    log.error(
                        "Falha ao propagar ban {} após confirmação de identidade na guild {}",
                        ban.getId(),
                        discord.getGuildId(),
                        exception
                    );
                }
            }
        } catch (RuntimeException exception) {
            log.error(
                "Falha ao reconciliar bans após confirmação do cluster do User {}",
                seedUserId,
                exception
            );
        }
    }
}
