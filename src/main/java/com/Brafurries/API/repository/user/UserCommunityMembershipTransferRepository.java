package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserCommunityMembershipTransfer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCommunityMembershipTransferRepository
        extends JpaRepository<UserCommunityMembershipTransfer, Long> {
    List<UserCommunityMembershipTransfer> findByConsolidationIdOrderByIdAsc(Long consolidationId);
}
