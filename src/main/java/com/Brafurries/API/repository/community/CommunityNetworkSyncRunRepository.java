package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityNetworkSyncRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityNetworkSyncRunRepository extends JpaRepository<CommunityNetworkSyncRun, Long> {
    Optional<CommunityNetworkSyncRun> findByIdAndCommunityIdAndNetworkTypeAndExternalNetworkId(
        Long id,
        Integer communityId,
        String networkType,
        Long externalNetworkId
    );

    Optional<CommunityNetworkSyncRun> findFirstByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
        Integer communityId,
        String networkType,
        Long externalNetworkId,
        String status
    );

    List<CommunityNetworkSyncRun> findAllByCommunityIdAndNetworkTypeAndExternalNetworkIdAndStatusOrderByIdAsc(
        Integer communityId,
        String networkType,
        Long externalNetworkId,
        String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CommunityNetworkSyncRun> findFirstByNetworkTypeAndStatusOrderByIdAsc(
        String networkType,
        String status
    );

    Optional<CommunityNetworkSyncRun> findFirstByCommunityIdAndNetworkTypeAndExternalNetworkIdOrderByIdDesc(
        Integer communityId,
        String networkType,
        Long externalNetworkId
    );
}
