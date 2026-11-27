package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Internal publication; no HTTP exposure until browser/provider pilot passes. */
@Service
public class GoogleDriveUploadPublicationService {
    private final GoogleDriveUploadLedger ledger;
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final String staging;

    public GoogleDriveUploadPublicationService(GoogleDriveUploadLedger ledger,GoogleDriveFolderAccessService acl,
            GoogleDriveConnectionService connection,GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,
            PlatformAuthorizationService authorization,
            @Value("${GOOGLE_DRIVE_UPLOAD_STAGING_FOLDER_ID:}") String staging) {
        this.ledger=ledger;this.acl=acl;this.connection=connection;this.browser=browser;
        this.google=google;this.authorization=authorization;this.staging=staging.trim();
    }
    record Result(String sessionId,String status) {}

    Result publish(Authentication auth,String sessionId) {
        var session=owned(auth,sessionId);
        var r=session.reservation();
        if (ledger.isReplacement(sessionId)) throw conflict();
        if ("PUBLISHED".equals(session.status())) return new Result(sessionId,"PUBLISHED");
        if (session.expired() || !"OPEN".equals(session.status())) throw conflict();
        boolean claimed=false;
        try {
            var policy=acl.browsePolicy(auth);
            requireScope(r,policy);
            String token=connection.browserAccessToken(r.scope());
            requireDestination(token,r,policy);
            requireStaging(token,r);
            validateStaged(google.uploadedFile(token,r.providerId()),r);
            if (!ledger.claimPublication(r)) throw conflict();
            claimed=true;
            // Check again after the durable claim; never issue a second move on
            // an uncertain provider result. A crash leaves PUBLISHING hidden.
            var current=acl.browsePolicy(auth);
            requireScope(r,current);
            var currentPath=requireDestination(token,r,current);
            requireStaging(token,r);
            validateStaged(google.uploadedFile(token,r.providerId()),r);
            // Fresh ACL after all pre-move provider I/O.
            requireLocal(r,acl.browsePolicy(auth),currentPath);
            GoogleDriveUploadPolicy.requirePublishedFile(
                google.moveUploadedFile(token,r.providerId(),r.staging(),r.destination()),r);
            GoogleDriveUploadPolicy.requirePublishedFile(google.uploadedFile(token,r.providerId()),r);
            var after=acl.browsePolicy(auth);
            requireScope(r,after);
            var finalPath=requireDestination(token,r,after);
            requireLocal(r,acl.browsePolicy(auth),finalPath);
            ledger.finishPublication(r);
            return new Result(sessionId,"PUBLISHED");
        } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
            if (claimed) ledger.publicationFailed(r);
            if (!claimed && ex.status==404) throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload ainda não confirmado pelo Google");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível confirmar a publicação");
        } catch (RuntimeException ex) {
            if (claimed) ledger.publicationFailed(r);
            throw ex;
        }
    }

    Result cancel(Authentication auth,String sessionId) {
        var session=owned(auth,sessionId);
        if (List.of("RESERVED","OPEN").contains(session.status())) ledger.cancel(session.reservation());
        return new Result(sessionId,owned(auth,sessionId).status());
    }

    private GoogleDriveUploadLedger.Session owned(Authentication auth,String sessionId) {
        if (!GoogleDriveUploadPolicy.validSessionId(sessionId)) throw GoogleDriveBrowserService.inaccessible();
        return ledger.owned(sessionId,authorization.currentUser(auth).getId());
    }

    private void requireScope(GoogleDriveUploadLedger.Reservation r,GoogleDriveFolderAccessService.BrowsePolicy policy) {
        if (!r.scope().equals(policy.scope()) || !staging.equals(r.staging()) ||
            policy.registered().contains(r.staging()) || r.destination().equals(r.staging()))
            throw GoogleDriveBrowserService.inaccessible();
        GoogleDriveBrowserService.requireAnchor(policy,r.anchor());
    }

    private List<String> requireDestination(String token,GoogleDriveUploadLedger.Reservation r,
            GoogleDriveFolderAccessService.BrowsePolicy policy) {
        var path=browser.verifyPath(token,policy,r.anchor(),r.destination());
        requireLocal(r,policy,path);
        JsonNode target=google.uploadFolderMetadata(token,r.destination());
        // Deliberately private destinations during pilot. Moving into a shared
        // folder grants external inherited access before local commit.
        if (target==null || !r.destination().equals(target.path("id").asText()) ||
            !"application/vnd.google-apps.folder".equals(target.path("mimeType").asText()) ||
            !GoogleDriveBrowserService.isOwnedLive(target) ||
            !target.path("shared").isBoolean() || target.path("shared").asBoolean() ||
            !target.path("capabilities").path("canAddChildren").isBoolean() ||
            !target.path("capabilities").path("canAddChildren").asBoolean())
            throw GoogleDriveBrowserService.inaccessible();
        return path;
    }
    private void requireLocal(GoogleDriveUploadLedger.Reservation r,
            GoogleDriveFolderAccessService.BrowsePolicy policy,List<String> path) {
        requireScope(r,policy);
        for(String id:path) if(policy.registered().contains(id)&&!policy.canView(id))
            throw GoogleDriveBrowserService.inaccessible();
        if (!GoogleDriveBrowserService.operationAllowed(policy,path,GoogleDriveFolderAccessService.Permission.UPLOAD))
            throw GoogleDriveBrowserService.inaccessible();
    }
    private void requireStaging(String token,GoogleDriveUploadLedger.Reservation r) {
        GoogleDriveUploadPolicy.requirePrivateStaging(google.uploadFolderMetadata(token,"root"),
            google.uploadFolderMetadata(token,r.scope().rootId()),google.uploadFolderMetadata(token,r.staging()),
            google.uploadFolderPermissions(token,r.staging()),r.scope().rootId(),r.staging());
    }
    private static void validateStaged(JsonNode file,GoogleDriveUploadLedger.Reservation r) {
        GoogleDriveUploadPolicy.requireStagedFile(file,r.providerId(),r.id(),r.staging(),r.name(),r.mime(),r.size());
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,"Sessão indisponível ou exige reconciliação");
    }
}
