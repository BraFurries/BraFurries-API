package com.Brafurries.API.admin;

import com.Brafurries.API.entity.user.UserIdentityLink;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ConfirmedIdentityClusterService {

    private final UserIdentityLinkRepository linkRepository;

    public ConfirmedIdentityClusterService(UserIdentityLinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    public Set<Integer> resolveConfirmedUserIds(Integer userId) {
        var visited = new LinkedHashSet<Integer>();
        var frontier = new LinkedHashSet<Integer>();
        visited.add(userId);
        frontier.add(userId);

        while (!frontier.isEmpty()) {
            List<UserIdentityLink> adjacent = linkRepository.findActiveLinksContainingUsersByStatus(
                frontier,
                UserIdentityLinkStatus.CONFIRMED
            );
            var nextFrontier = new LinkedHashSet<Integer>();
            for (UserIdentityLink link : adjacent) {
                addIfUnvisited(link.getUserA().getId(), visited, nextFrontier);
                addIfUnvisited(link.getUserB().getId(), visited, nextFrontier);
            }
            frontier = nextFrontier;
        }

        return Set.copyOf(visited);
    }

    private void addIfUnvisited(Integer userId, Set<Integer> visited, Set<Integer> nextFrontier) {
        if (visited.add(userId)) {
            nextFrontier.add(userId);
        }
    }
}
