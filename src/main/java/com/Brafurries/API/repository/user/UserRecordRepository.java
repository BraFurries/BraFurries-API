package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRecordRepository extends JpaRepository<UserRecord, Integer> {
}
