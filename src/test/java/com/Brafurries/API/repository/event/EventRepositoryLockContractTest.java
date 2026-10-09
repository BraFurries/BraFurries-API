package com.Brafurries.API.repository.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class EventRepositoryLockContractTest {

    @Test
    void partnerToggleLockUsesPessimisticWrite() throws Exception {
        Lock lock = EventRepository.class.getMethod("findByIdForUpdate", Integer.class).getAnnotation(Lock.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }
}
