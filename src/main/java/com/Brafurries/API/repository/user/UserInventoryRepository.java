package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public interface UserInventoryRepository extends JpaRepository<UserInventory, Integer> {
    @EntityGraph(attributePaths = {"storeItem"})
    List<UserInventory> findAllByOwnerUserIdAndCommunityIdOrderByIdDesc(Integer userId, Integer communityId);

    @EntityGraph(attributePaths = {"storeItem"})
    List<UserInventory> findTop6ByOwnerUserIdAndCommunityIdOrderByIdDesc(Integer userId, Integer communityId);
}
