package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.*;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.CREATE_FOLDER;

@Service
public class GoogleDriveFolderCreationService {
    private static final String FOLDER="application/vnd.google-apps.folder";
    private final GoogleDriveFolderCreationLedger ledger;
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final boolean enabled;

    public GoogleDriveFolderCreationService(GoogleDriveFolderCreationLedger ledger,
            GoogleDriveFolderAccessService acl,GoogleDriveConnectionService connection,
            GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,PlatformAuthorizationService authorization,
            @Value("${GOOGLE_DRIVE_CREATE_FOLDER_ENABLED:false}") boolean enabled) {
        this.ledger=ledger;this.acl=acl;this.connection=connection;this.browser=browser;
        this.google=google;this.authorization=authorization;this.enabled=enabled;
    }
    public boolean enabled(){return enabled;}
    public record Result(String id,String name) {}

    public Result create(Authentication auth,String anchor,String parent,String name,String operationId) {
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Criação de pastas indisponível");
        if(!GoogleDriveConnectionService.validFolderId(anchor)||!GoogleDriveConnectionService.validFolderId(parent)||
            !GoogleDriveUploadPolicy.validName(name)||!GoogleDriveUploadPolicy.validSessionId(operationId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dados de pasta inválidos");
        int actor=authorization.currentUser(auth).getId();
        var policy=acl.browsePolicy(auth);
        requireAnchor(policy,anchor);
        if(parent.equals(policy.scope().rootId()))throw inaccessible();
        GoogleDriveFolderCreationLedger.Operation reservation=null;
        try {
            String token=connection.browserAccessToken(policy.scope());
            var path=verifyParent(token,policy,anchor,parent);
            requireLocal(auth,actor,policy.scope(),anchor,path);
            var old=ledger.find(operationId);
            if(old!=null){
                requireSameOperation(old,actor,policy.scope(),anchor,parent,name);
                if("CREATED".equals(old.status()))return new Result(old.providerId(),old.name());
                if(!"RECONCILE_REQUIRED".equals(old.status()))throw conflict();
                // Confirm the reserved provider ID only; never replay a write.
                validateCreated(google.uploadedFile(token,old.providerId()),old);
                var current=acl.browsePolicy(auth);
                if(!old.scope().equals(current.scope()))throw inaccessible();
                path=verifyParent(token,current,anchor,parent);
                requireLocal(auth,actor,old.scope(),anchor,path);
                ledger.confirmReconciled(old);
                return new Result(old.providerId(),old.name());
            }
            String id=GoogleDriveUploadPolicy.generatedFileId(google.generateUploadId(token));
            var r=new GoogleDriveFolderCreationLedger.Operation(operationId,actor,policy.scope(),anchor,parent,id,name,"PENDING");
            try {ledger.reserve(r);} catch(DuplicateKeyException ex) {throw conflict();}
            reservation=r;
            var current=acl.browsePolicy(auth);
            if(!current.scope().equals(r.scope()))throw inaccessible();
            path=verifyParent(token,current,anchor,parent);
            requireLocal(auth,actor,r.scope(),anchor,path);
            validateCreated(google.createFolder(token,id,parent,name,operationId),r);
            validateCreated(google.uploadedFile(token,id),r);
            var after=acl.browsePolicy(auth);
            if(!after.scope().equals(r.scope()))throw inaccessible();
            path=verifyParent(token,after,anchor,parent);
            requireLocal(auth,actor,r.scope(),anchor,path);
            ledger.complete(r);
            return new Result(id,name);
        } catch(GoogleDriveOAuthClient.ProviderFailure ex) {
            if(reservation!=null)ledger.uncertain(reservation);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Criação não confirmada pelo Google");
        } catch(RuntimeException ex) {
            if(reservation!=null)ledger.uncertain(reservation);
            throw ex;
        }
    }

    private static void requireSameOperation(GoogleDriveFolderCreationLedger.Operation r,int actor,
            GoogleDriveConnectionService.FolderScope scope,String anchor,String parent,String name) {
        if(r.actor()!=actor||!r.scope().equals(scope)||!r.anchor().equals(anchor)||!r.parent().equals(parent)||!r.name().equals(name))
            throw inaccessible();
    }
    private List<String> verifyParent(String token,GoogleDriveFolderAccessService.BrowsePolicy policy,String anchor,String parent) {
        var path=browser.verifyPath(token,policy,anchor,parent);
        JsonNode meta=google.uploadFolderMetadata(token,parent);
        if(meta==null||!parent.equals(meta.path("id").asText())||!FOLDER.equals(meta.path("mimeType").asText())||
            !isOwnedLive(meta)||!meta.path("shared").isBoolean()||meta.path("shared").asBoolean()||
            !meta.path("capabilities").path("canAddChildren").isBoolean()||!meta.path("capabilities").path("canAddChildren").asBoolean())
            throw inaccessible();
        return path;
    }
    private void requireLocal(Authentication auth,int actor,GoogleDriveConnectionService.FolderScope scope,String anchor,List<String> path) {
        if(authorization.currentUser(auth).getId()!=actor)throw inaccessible();
        var current=acl.browsePolicy(auth);
        if(!scope.equals(current.scope()))throw inaccessible();
        requireAnchor(current,anchor);
        var hidden=connection.hiddenUploadResources(scope);
        for(String id:path)if(hidden.contains(id)||(current.registered().contains(id)&&!current.canView(id)))throw inaccessible();
        if(!operationAllowed(current,path,CREATE_FOLDER))throw inaccessible();
    }
    static void validateCreated(JsonNode node,GoogleDriveFolderCreationLedger.Operation r) {
        if(node==null||!r.providerId().equals(node.path("id").asText())||
            !r.name().equals(node.path("name").asText())||!FOLDER.equals(node.path("mimeType").asText())||
            !isOwnedLive(node)||!node.path("shared").isBoolean()||node.path("shared").asBoolean()||
            !r.parent().equals(singleParent(node))||
            !r.id().equals(node.path("appProperties").path("brafurriesFolderOperation").asText()))
            throw conflict();
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Operação já registrada ou exige reconciliação");}
}
