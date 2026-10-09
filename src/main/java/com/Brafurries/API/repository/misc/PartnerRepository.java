package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.Partner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerRepository extends JpaRepository<Partner, Integer> {

    @EntityGraph(attributePaths = {"community", "representativeUser", "links", "externalLinks"})
    @Query("""
        SELECT DISTINCT p
        FROM Partner p
        WHERE (:status IS NULL OR LOWER(p.status) = LOWER(:status))
          AND (:category IS NULL OR LOWER(p.category) = LOWER(:category))
        ORDER BY p.name ASC
        """)
    List<Partner> findAdminPartners(@Param("status") String status, @Param("category") String category);

    @EntityGraph(attributePaths = {"community", "representativeUser", "links", "externalLinks"})
    @Query("SELECT p FROM Partner p WHERE p.id = :id")
    Optional<Partner> findWithDetailsById(@Param("id") Integer id);

    boolean existsBySlug(String slug);

    long countByStatusIgnoreCase(String status);
}
