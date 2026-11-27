package com.Brafurries.API.platform.drive;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.Brafurries.API.platform.PlatformAuthorizationService;

/**
 * Internal orchestration only. Do not expose it through HTTP until reconciliation,
 * publication and browser CORS validation have been delivered.
 */
@Service
public class GoogleDriveUploadSessionService {
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final GoogleDriveUploadLedger ledger;
    private final PlatformAuthorizationService authorization;
    private final String staging;
    private final long maxBytes;

    public GoogleDriveUploadSessionService(GoogleDriveFolderAccessService acl,
            GoogleDriveConnectionService connection,GoogleDriveBrowserService browser,
            GoogleDriveOAuthClient google,GoogleDriveUploadLedger ledger,
            PlatformAuthorizationService authorization,
            @Value("${GOOGLE_DRIVE_UPLOAD_STAGING_FOLDER_ID:}") String staging,
            @Value("${GOOGLE_DRIVE_UPLOAD_MAX_BYTES:5368709120}") long maxBytes) {
        this.acl=acl;this.connection=connection;this.browser=browser;this.google=google;
        this.ledger=ledger;this.authorization=authorization;
        this.staging=staging.trim();this.maxBytes=maxBytes;
    }

    record Start(String sessionId,URI uploadUri) {
        @Override public String toString() {return "Start[sessionId="+sessionId+",uploadUri=REDACTED]";}
    }

    Start begin(Authentication auth,String anchor,String destination,String name,String mime,long size) {
        return beginInternal(auth,anchor,destination,name,mime,size,null,null);
    }

    Start beginReplacement(Authentication auth,String anchor,String destination,String name,String mime,long size,
            GoogleDriveUploadLedger.Replacement replacement,GoogleDriveConnectionService.FolderScope expectedScope) {
        java.util.Objects.requireNonNull(replacement);java.util.Objects.requireNonNull(expectedScope);
        return beginInternal(auth,anchor,destination,name,mime,size,replacement,expectedScope);
    }

    private Start beginInternal(Authentication auth,String anchor,String destination,String name,String mime,long size,
            GoogleDriveUploadLedger.Replacement replacement,GoogleDriveConnectionService.FolderScope expectedScope) {
        var permission=replacement==null?GoogleDriveFolderAccessService.Permission.UPLOAD:GoogleDriveFolderAccessService.Permission.REPLACE;
        if (!GoogleDriveConnectionService.validFolderId(staging) || maxBytes<=0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Upload institucional não configurado");
        if (!GoogleDriveConnectionService.validFolderId(anchor) ||
            !GoogleDriveConnectionService.validFolderId(destination) ||
            !GoogleDriveUploadPolicy.validName(name) || !GoogleDriveUploadPolicy.validMime(mime) || size<=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Metadados de upload inválidos");
        if (size>maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Arquivo excede o limite permitido");
        int actor=authorization.currentUser(auth).getId();
        var before=acl.browsePolicy(auth);
        if(expectedScope!=null&&!expectedScope.equals(before.scope()))throw GoogleDriveBrowserService.inaccessible();
        requireDestination(before,anchor,destination);
        String token=connection.browserAccessToken(before.scope());
        GoogleDriveUploadLedger.Reservation reservation=null;
        try {
            var path=browser.verifyPath(token,before,anchor,destination);
            requireOperation(before,path,permission);
            var destinationMeta=google.uploadFolderMetadata(token,destination);
            if (destinationMeta==null || !destination.equals(destinationMeta.path("id").asText()) ||
                !GoogleDriveBrowserService.isOwnedLive(destinationMeta) ||
                !"application/vnd.google-apps.folder".equals(destinationMeta.path("mimeType").asText()) ||
                !destinationMeta.path("shared").isBoolean() || destinationMeta.path("shared").asBoolean() ||
                !destinationMeta.path("capabilities").path("canAddChildren").isBoolean() ||
                !destinationMeta.path("capabilities").path("canAddChildren").asBoolean())
                throw GoogleDriveBrowserService.inaccessible();
            verifyStaging(token,before);
            String providerId=GoogleDriveUploadPolicy.generatedFileId(google.generateUploadId(token));
            var after=acl.browsePolicy(auth);
            if (!before.scope().equals(after.scope()) || actor!=authorization.currentUser(auth).getId())
                throw GoogleDriveBrowserService.inaccessible();
            requireDestination(after,anchor,destination);
            requireOperation(after,path,permission);
            var r=new GoogleDriveUploadLedger.Reservation(UUID.randomUUID().toString(),actor,
                before.scope(),anchor,destination,staging,providerId,name,mime,size);
            if(replacement==null)ledger.reserve(r);else ledger.reserveReplacement(r,replacement);
            reservation=r;
            // The committed reservation always precedes creation of the provider session.
            URI uri=google.beginResumableUpload(token,providerId,staging,r.id(),name,mime,size);
            var current=acl.browsePolicy(auth);
            if (!before.scope().equals(current.scope()) || actor!=authorization.currentUser(auth).getId())
                throw GoogleDriveBrowserService.inaccessible();
            requireDestination(current,anchor,destination);
            requireOperation(current,browser.verifyPath(token,current,anchor,destination),permission);
            verifyStaging(token,current);
            if (!ledger.markOpen(r)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Sessão de upload expirada");
            return new Start(r.id(),uri);
        } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
            if (reservation!=null) ledger.requireCleanup(reservation);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível iniciar o upload no Google");
        } catch (RuntimeException ex) {
            if (reservation!=null) ledger.requireCleanup(reservation);
            throw ex;
        }
    }

    private void verifyStaging(String token,GoogleDriveFolderAccessService.BrowsePolicy policy) {
        if (policy.registered().contains(staging)) throw GoogleDriveBrowserService.inaccessible();
        GoogleDriveUploadPolicy.requirePrivateStaging(google.uploadFolderMetadata(token,"root"),
            google.uploadFolderMetadata(token,policy.scope().rootId()),google.uploadFolderMetadata(token,staging),
            google.uploadFolderPermissions(token,staging),policy.scope().rootId(),staging);
    }

    private void requireDestination(GoogleDriveFolderAccessService.BrowsePolicy policy,String anchor,String destination) {
        GoogleDriveBrowserService.requireAnchor(policy,anchor);
        if (staging.equals(anchor)||staging.equals(destination)||policy.scope().rootId().equals(destination) ||
            (policy.registered().contains(destination)&&!policy.canView(destination)))
            throw GoogleDriveBrowserService.inaccessible();
    }

    private static void requireOperation(GoogleDriveFolderAccessService.BrowsePolicy policy,List<String> path,GoogleDriveFolderAccessService.Permission permission) {
        for (String id:path) if (policy.registered().contains(id)&&!policy.canView(id))
            throw GoogleDriveBrowserService.inaccessible();
        if (!GoogleDriveBrowserService.operationAllowed(policy,path,permission))
            throw GoogleDriveBrowserService.inaccessible();
    }
}
