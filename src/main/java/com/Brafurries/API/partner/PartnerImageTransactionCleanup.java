package com.Brafurries.API.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PartnerImageTransactionCleanup {

    private static final Logger log = LoggerFactory.getLogger(PartnerImageTransactionCleanup.class);

    private final PartnerImageStorageService partnerImageStorageService;

    public PartnerImageTransactionCleanup(PartnerImageStorageService partnerImageStorageService) {
        this.partnerImageStorageService = partnerImageStorageService;
    }

    public void deletePreviousAfterCommit(Integer partnerId, String imageUrl) {
        if (isBlank(imageUrl)) return;
        register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(() -> partnerImageStorageService.deletePartnerImageByUrl(partnerId, imageUrl), partnerId, "anterior");
            }
        });
    }

    public void deleteUploadedOnRollback(Integer partnerId, String key) {
        if (isBlank(key)) return;
        register(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    safely(() -> partnerImageStorageService.deletePartnerImageByKey(partnerId, key), partnerId, "nova após rollback");
                }
            }
        });
    }

    private void register(TransactionSynchronization synchronization) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Cleanup de imagem exige transação Spring ativa");
        }
        TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    private void safely(Runnable cleanup, Integer partnerId, String imageKind) {
        try {
            cleanup.run();
        } catch (RuntimeException ex) {
            log.warn("Falha ao remover imagem {} da parceria {}", imageKind, partnerId, ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
