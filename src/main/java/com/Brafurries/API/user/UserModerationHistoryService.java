package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.UserModerationDtos.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserModerationHistoryService {
    private static final String CONTEXT_NOT_FOUND = "Contexto comunitário não encontrado";
    private final UserRepository userRepository;
    private final UserCommunityStatusRepository userCommunityStatusRepository;
    private final UserWarningRepository userWarningRepository;

    public UserModerationHistoryService(
        UserRepository userRepository,
        UserCommunityStatusRepository userCommunityStatusRepository,
        UserWarningRepository userWarningRepository
    ) {
        this.userRepository = userRepository;
        this.userCommunityStatusRepository = userCommunityStatusRepository;
        this.userWarningRepository = userWarningRepository;
    }

    @Transactional(readOnly = true)
    public MemberModerationPage getLoggedUserModeration(String principal, Integer communityId, int page, int pageSize) {
        User user = findAuthenticatedUser(principal);
        var memberships = userCommunityStatusRepository
            .findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(user.getId(), communityId);
        if (memberships.isEmpty()
            || memberships.getFirst().getCommunity().getDiscord() == null
            || !Boolean.TRUE.equals(memberships.getFirst().getCommunity().getDiscord().getActive())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, CONTEXT_NOT_FOUND);
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        Page<UserWarningRepository.MemberModerationProjection> results = userWarningRepository.findMemberCommunityModerationRecords(
            user.getId(), communityId, LocalDate.now(), PageRequest.of(safePage - 1, safePageSize)
        );
        return new MemberModerationPage(
            results.getContent().stream().map(this::toRecord).toList(),
            safePage,
            safePageSize,
            results.getTotalElements(),
            results.getTotalPages()
        );
    }

    private User findAuthenticatedUser(String principal) {
        String normalizedEmail = principal == null ? "" : principal.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
    }

    private MemberModerationRecord toRecord(UserWarningRepository.MemberModerationProjection projection) {
        return new MemberModerationRecord(
            projection.getId(),
            projection.getRecordType(),
            projection.getReason(),
            projection.getOccurredAt(),
            projection.getStatus(),
            projection.getCanAppeal() == null ? null : projection.getCanAppeal().intValue() != 0,
            projection.getValidUntil(),
            projection.getRevokedAt(),
            projection.getRevocationReason()
        );
    }
}
