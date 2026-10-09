package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserNote;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserNoteRepository extends JpaRepository<UserNote, Integer> {
    List<UserNote> findByUserIdIn(Collection<Integer> userIds);
}
