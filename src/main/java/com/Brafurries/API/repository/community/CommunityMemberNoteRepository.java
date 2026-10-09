package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityMemberNote;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityMemberNoteRepository extends JpaRepository<CommunityMemberNote, Long> {

    @Query("""
        SELECT note
        FROM CommunityMemberNote note
        JOIN FETCH note.authorUser
        JOIN FETCH note.updatedByUser
        WHERE note.community.id = :communityId
          AND note.userId = :userId
          AND (:includeArchived = true OR note.archivedAt IS NULL)
        ORDER BY note.createdAt DESC, note.id DESC
        """)
    List<CommunityMemberNote> findMemberNotes(
        @Param("communityId") Integer communityId,
        @Param("userId") Integer userId,
        @Param("includeArchived") boolean includeArchived
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT note
        FROM CommunityMemberNote note
        JOIN FETCH note.authorUser
        JOIN FETCH note.updatedByUser
        WHERE note.id = :noteId
          AND note.community.id = :communityId
          AND note.userId = :userId
        """)
    Optional<CommunityMemberNote> findForUpdate(
        @Param("noteId") Long noteId,
        @Param("communityId") Integer communityId,
        @Param("userId") Integer userId
    );
}
