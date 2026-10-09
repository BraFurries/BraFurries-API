package com.Brafurries.API.partner;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PartnerImageTransactionCleanupTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void commitDeletesPreviousImageButKeepsNewUpload() {
        PartnerImageStorageService storage = mock(PartnerImageStorageService.class);
        PartnerImageTransactionCleanup cleanup = new PartnerImageTransactionCleanup(storage);
        TransactionSynchronizationManager.initSynchronization();
        cleanup.deletePreviousAfterCommit(10, "https://cdn.example/partners/10/image/old.webp");
        cleanup.deleteUploadedOnRollback(10, "partners/10/image/new.webp");

        synchronizations().forEach(TransactionSynchronization::afterCommit);
        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(storage).deletePartnerImageByUrl(10, "https://cdn.example/partners/10/image/old.webp");
        verify(storage, never()).deletePartnerImageByKey(10, "partners/10/image/new.webp");
    }

    @Test
    void rollbackDeletesNewUploadButKeepsPreviousImage() {
        PartnerImageStorageService storage = mock(PartnerImageStorageService.class);
        PartnerImageTransactionCleanup cleanup = new PartnerImageTransactionCleanup(storage);
        TransactionSynchronizationManager.initSynchronization();
        cleanup.deletePreviousAfterCommit(10, "https://cdn.example/partners/10/image/old.webp");
        cleanup.deleteUploadedOnRollback(10, "partners/10/image/new.webp");

        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(storage, never()).deletePartnerImageByUrl(10, "https://cdn.example/partners/10/image/old.webp");
        verify(storage).deletePartnerImageByKey(10, "partners/10/image/new.webp");
    }

    private List<TransactionSynchronization> synchronizations() {
        return TransactionSynchronizationManager.getSynchronizations();
    }
}
