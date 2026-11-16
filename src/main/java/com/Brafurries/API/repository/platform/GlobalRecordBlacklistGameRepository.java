package com.Brafurries.API.repository.platform;

import com.Brafurries.API.entity.platform.GlobalRecordBlacklistGame;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GlobalRecordBlacklistGameRepository extends JpaRepository<GlobalRecordBlacklistGame, Integer> {
    List<GlobalRecordBlacklistGame> findAllByOrderByGameNameAsc();
    boolean existsByGameNameIgnoreCase(String name);
}
