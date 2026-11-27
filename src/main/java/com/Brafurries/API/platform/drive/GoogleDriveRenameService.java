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
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.RENAME;

@Service
public class GoogleDriveRenameService {
    private final GoogleDriveRenameLedger ledger;
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final boolean enabled;

    public GoogleDriveRenameService(GoogleDriveRenameLedger ledger,GoogleDriveFolderAccessService acl,
            GoogleDriveConnectionService connection,GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,
            PlatformAuthorizationService authorization,@Value("${GOOGLE_DRIVE_RENAME_ENABLED:false}") boolean enabled){
        this.ledger=ledger;this.acl=acl;this.connection=connection;this.browser=browser;this.google=google;
        this.authorization=authorization;this.enabled=enabled;
    }

    public record Result(String id,String name,String version) {}
    record Metadata(String name,String version,String mime) {}

    public Result rename(Authentication auth,String anchor,String parent,String file,String version,String name,String operationId){
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Renomeação indisponível");
        if(!GoogleDriveConnectionService.validFolderId(anchor)||!GoogleDriveConnectionService.validFolderId(parent)||
            !GoogleDriveConnectionService.validFolderId(file)||file.equals(parent)||
            !validVersion(version)||!GoogleDriveUploadPolicy.validName(name)||!GoogleDriveUploadPolicy.validSessionId(operationId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dados de renomeação inválidos");
        int actor=authorization.currentUser(auth).getId();
        var policy=acl.browsePolicy(auth);
        requireAnchor(policy,anchor);
        requireVisibleTarget(policy,file,parent);
        GoogleDriveRenameLedger.Operation reservation=null;
        boolean attempted=false;
        try{
            String token=connection.browserAccessToken(policy.scope());
            var path=browser.verifyPath(token,policy,anchor,parent);
            requireLocal(auth,actor,policy.scope(),anchor,parent,file,path);
            var meta=verify(google.renameMetadata(token,file),file,parent);
            var prior=ledger.find(operationId);
            if(prior!=null){
                if(prior.actor()!=actor||!prior.scope().equals(policy.scope())||!prior.anchor().equals(anchor)||
                    !prior.parent().equals(parent)||!prior.file().equals(file)||!prior.version().equals(version)||!prior.newName().equals(name))
                    throw inaccessible();
                if(!"RENAMED".equals(prior.status())||!meta.name().equals(name))throw conflict();
                return new Result(file,name,meta.version());
            }
            if(!meta.version().equals(version))throw conflict();
            if(meta.name().equals(name))return new Result(file,name,version);
            var r=new GoogleDriveRenameLedger.Operation(operationId,actor,policy.scope(),anchor,parent,file,
                version,meta.name(),name,"PENDING");
            try{ledger.reserve(r);}catch(DuplicateKeyException ex){throw conflict();}
            reservation=r;
            var current=acl.browsePolicy(auth);
            if(!r.scope().equals(current.scope()))throw inaccessible();
            requireVisibleTarget(current,file,parent);
            path=browser.verifyPath(token,current,anchor,parent);
            if(!meta.equals(verify(google.renameMetadata(token,file),file,parent)))throw conflict();
            requireLocal(auth,actor,r.scope(),anchor,parent,file,path);
            attempted=true;
            var changed=verify(google.rename(token,file,name),file,parent);
            var confirmed=verify(google.renameMetadata(token,file),file,parent);
            if(!changed.equals(confirmed)||!name.equals(confirmed.name())||!meta.mime().equals(confirmed.mime())||
                new java.math.BigInteger(confirmed.version()).compareTo(new java.math.BigInteger(version))<=0)throw conflict();
            var after=acl.browsePolicy(auth);
            if(!r.scope().equals(after.scope()))throw inaccessible();
            path=browser.verifyPath(token,after,anchor,parent);
            requireLocal(auth,actor,r.scope(),anchor,parent,file,path);
            ledger.complete(r);
            return new Result(file,name,confirmed.version());
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){
            if(reservation!=null){if(attempted)ledger.uncertain(reservation);else ledger.abort(reservation);}
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Renomeação não confirmada");
        }catch(RuntimeException ex){
            if(reservation!=null){if(attempted)ledger.uncertain(reservation);else ledger.abort(reservation);}
            throw ex;
        }
    }

    private void requireVisibleTarget(GoogleDriveFolderAccessService.BrowsePolicy policy,String file,String parent){
        // Registered mounts have names in the registry and their own ACL boundary.
        if(file.equals(policy.scope().rootId())||parent.equals(policy.scope().rootId())||
            policy.registered().contains(file)||connection.hiddenUploadResources(policy.scope()).contains(file))throw inaccessible();
    }
    private void requireLocal(Authentication auth,int actor,GoogleDriveConnectionService.FolderScope scope,
            String anchor,String parent,String file,List<String> path){
        if(authorization.currentUser(auth).getId()!=actor)throw inaccessible();
        var current=acl.browsePolicy(auth);
        if(!scope.equals(current.scope()))throw inaccessible();
        requireAnchor(current,anchor);requireVisibleTarget(current,file,parent);
        var hidden=connection.hiddenUploadResources(scope);
        for(String id:path)if(hidden.contains(id)||(current.registered().contains(id)&&!current.canView(id)))throw inaccessible();
        if(!operationAllowed(current,path,RENAME))throw inaccessible();
    }
    static boolean validVersion(String version){return version!=null&&version.matches("[0-9]{1,32}");}
    static Metadata verify(JsonNode node,String file,String parent){
        if(node==null||!file.equals(node.path("id").asText())||!parent.equals(singleParent(node))||
            !isOwnedLive(node)||!node.path("shared").isBoolean()||node.path("shared").asBoolean()||
            !node.path("capabilities").path("canRename").isBoolean()||!node.path("capabilities").path("canRename").asBoolean()||
            !node.path("name").isTextual()||!GoogleDriveUploadPolicy.validName(node.path("name").asText())||
            !node.path("mimeType").isTextual()||node.path("mimeType").asText().isBlank()||
            "application/vnd.google-apps.shortcut".equals(node.path("mimeType").asText())||
            !node.path("version").isTextual()||!validVersion(node.path("version").asText()))throw inaccessible();
        return new Metadata(node.path("name").asText(),node.path("version").asText(),node.path("mimeType").asText());
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Arquivo alterado ou operação pendente de revisão");}
}
