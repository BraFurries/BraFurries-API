package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityStoreRepository extends JpaRepository<CommunityStore, Integer> {
}
