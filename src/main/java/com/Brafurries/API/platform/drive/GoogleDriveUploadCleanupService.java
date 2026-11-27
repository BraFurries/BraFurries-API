package com.Brafurries.API.platform.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded reconciliation. Ambiguous publication is never retried or deleted automatically. */
@Service
public class GoogleDriveUploadCleanupService {
    private final GoogleDriveUploadLedger ledger;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveOAuthClient google;
    private final boolean enabled;

    public GoogleDriveUploadCleanupService(GoogleDriveUploadLedger ledger,GoogleDriveConnectionService connection,
            GoogleDriveOAuthClient google,@Value("${GOOGLE_DRIVE_UPLOAD_CLEANUP_ENABLED:false}") boolean enabled) {
        this.ledger=ledger;this.connection=connection;this.google=google;this.enabled=enabled;
    }

    @Scheduled(fixedDelayString="${GOOGLE_DRIVE_UPLOAD_CLEANUP_DELAY_MS:900000}",
        initialDelayString="${GOOGLE_DRIVE_UPLOAD_CLEANUP_DELAY_MS:900000}")
    public void reconcile() {
        if (!enabled) return;
        for (var session:ledger.due()) {
            try {cleanup(session);}
            catch (RuntimeException ignored) {
                // No provider bodies, capability URLs or exception messages in logs.
                // The committed claim lease permits another attempt.
            }
        }
    }

    void cleanup(GoogleDriveUploadLedger.Session session) {
        var r=session.reservation();
        if (!ledger.claimCleanup(r)) return;
        try {
            String token=connection.browserAccessToken(r.scope());
            GoogleDriveUploadPolicy.requirePrivateStaging(google.uploadFolderMetadata(token,"root"),
                google.uploadFolderMetadata(token,r.scope().rootId()),google.uploadFolderMetadata(token,r.staging()),
                google.uploadFolderPermissions(token,r.staging()),r.scope().rootId(),r.staging());
            com.fasterxml.jackson.databind.JsonNode file;
            try {file=google.uploadedFile(token,r.providerId());}
            catch (GoogleDriveOAuthClient.ProviderFailure ex) {
                if (ex.status!=404) throw ex;
                // The upload capability can remain live after local expiry.
                // Retain the reservation until a full provider window plus margin.
                if (session.providerExpiryPassed()) ledger.cleaned(r);
                else ledger.retryCleanup(r);
                return;
            }
            if (!GoogleDriveUploadPolicy.identifiableStagedFile(file,r)) {
                ledger.cleanupNeedsReview(r);return;
            }
            if (!file.path("trashed").asBoolean()) {
                var trashed=google.trashStagedFile(token,r.providerId());
                if (trashed==null || !r.providerId().equals(trashed.path("id").asText()) ||
                    !trashed.path("trashed").isBoolean() || !trashed.path("trashed").asBoolean() ||
                    !trashed.path("ownedByMe").asBoolean(false) ||
                    !r.staging().equals(GoogleDriveBrowserService.singleParent(trashed)) ||
                    !r.id().equals(trashed.path("appProperties").path("brafurriesUploadSession").asText())) {
                    ledger.cleanupNeedsReview(r);return;
                }
            }
            // Recoverable trash only; no permanent deletion in this delivery.
            if (session.providerExpiryPassed()) ledger.cleaned(r);
            else ledger.retryCleanup(r);
        } catch (RuntimeException ex) {
            ledger.retryCleanup(r);
        }
    }
}
