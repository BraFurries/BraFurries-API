package com.Brafurries.API.repository.form;

import com.Brafurries.API.entity.form.FormPublishedMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormPublishedMessageRepository extends JpaRepository<FormPublishedMessage, Integer> {
    Optional<FormPublishedMessage> findByMessageId(Long messageId);
}
