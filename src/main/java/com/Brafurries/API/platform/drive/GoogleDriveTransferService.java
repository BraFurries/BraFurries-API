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
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static com.Brafurries.API.platform.drive.GoogleDriveTransferLedger.*;

@Service
public class GoogleDriveTransferService {
    static final String FOLDER="application/vnd.google-apps.folder";
    private final GoogleDriveTransferLedger ledger;
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final boolean enabled;

    public GoogleDriveTransferService(GoogleDriveTransferLedger ledger,GoogleDriveFolderAccessService acl,
        GoogleDriveConnectionService connection,GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,
        PlatformAuthorizationService authorization,@Value("${GOOGLE_DRIVE_TRANSFER_ENABLED:false}") boolean enabled){
        this.ledger=ledger;this.acl=acl;this.connection=connection;this.browser=browser;this.google=google;
        this.authorization=authorization;this.enabled=enabled;
    }
    public record Result(String id,String name){}
    record Metadata(String name,String mime,String version,String hash,String size){}
    record Paths(List<String> source,List<String> destination){}

    public Result transfer(Authentication auth,String sourceAnchor,String parent,String file,String version,
            String destinationAnchor,String destination,Action action,String operationId){
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Transferências indisponíveis");
        if(action==null||!GoogleDriveUploadPolicy.validSessionId(operationId)||!GoogleDriveRenameService.validVersion(version)||
            !java.util.stream.Stream.of(sourceAnchor,parent,file,destinationAnchor,destination).allMatch(GoogleDriveConnectionService::validFolderId)||
            file.equals(parent)||file.equals(destination)||(action==Action.MOVE&&parent.equals(destination)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Transferência inválida");
        int actor=authorization.currentUser(auth).getId();
        var policy=acl.browsePolicy(auth);
        Operation reserved=null;boolean attempted=false;
        try{
            String token=connection.browserAccessToken(policy.scope());
            var paths=paths(auth,actor,policy.scope(),token,sourceAnchor,parent,file,destinationAnchor,destination,action,false);
            var previous=ledger.find(operationId);
            if(previous!=null){
                if(previous.actor()!=actor||!previous.scope().equals(policy.scope())||!previous.sourceAnchor().equals(sourceAnchor)||
                    !previous.sourceParent().equals(parent)||!previous.file().equals(file)||!previous.destinationAnchor().equals(destinationAnchor)||
                    !previous.destination().equals(destination)||!previous.version().equals(version)||previous.action()!=action)
                    throw inaccessible();
                if(!"COMPLETED".equals(previous.status()))throw conflict();
                var result=verify(google.transferMetadata(token,previous.result()),previous.result(),destination,null);
                paths(auth,actor,policy.scope(),token,sourceAnchor,parent,file,destinationAnchor,destination,action,false);
                return new Result(previous.result(),result.name());
            }
            var meta=verify(google.transferMetadata(token,file),file,parent,action);
            if(!meta.version().equals(version))throw conflict();
            checkFolderMove(policy,paths,sourceAnchor,destinationAnchor,file,meta,action);
            String resultId=action==Action.COPY?GoogleDriveUploadPolicy.generatedFileId(google.generateUploadId(token)):file;
            var r=new Operation(operationId,actor,policy.scope(),sourceAnchor,parent,file,destinationAnchor,destination,resultId,version,action,"PENDING");
            try{ledger.reserve(r);}catch(DuplicateKeyException ex){throw conflict();}
            reserved=r;
            var current=paths(auth,actor,r.scope(),token,sourceAnchor,parent,file,destinationAnchor,destination,action,true);
            if(!paths.equals(current)||!meta.equals(verify(google.transferMetadata(token,file),file,parent,action)))throw conflict();
            checkFolderMove(acl.browsePolicy(auth),current,sourceAnchor,destinationAnchor,file,meta,action);
            local(auth,actor,r.scope(),sourceAnchor,parent,file,destinationAnchor,destination,current,action,true);
            String resultName=action==Action.COPY&&parent.equals(destination)?copyName(meta.name()):meta.name();
            attempted=true;
            JsonNode response=action==Action.MOVE?google.moveUploadedFile(token,file,parent,destination):
                google.copyFile(token,file,resultId,destination,resultName,operationId);
            if(response==null||!resultId.equals(response.path("id").asText()))throw conflict();
            var result=verify(google.transferMetadata(token,resultId),resultId,destination,null);
            if(!resultName.equals(result.name())||!meta.mime().equals(result.mime())||!meta.hash().equals(result.hash())||
                !meta.size().equals(result.size()))throw conflict();
            if(action==Action.COPY){
                var copy=google.uploadedFile(token,resultId);
                if(copy==null||!resultId.equals(copy.path("id").asText())||!operationId.equals(copy.path("appProperties").path("brafurriesTransferOperation").asText())||
                    !destination.equals(singleParent(copy))||!isOwnedLive(copy)||!copy.path("shared").isBoolean()||copy.path("shared").asBoolean())throw conflict();
                if(!meta.equals(verify(google.transferMetadata(token,file),file,parent,action)))throw conflict();
            }
            var after=paths(auth,actor,r.scope(),token,sourceAnchor,parent,file,destinationAnchor,destination,action,true);
            if(!current.equals(after))throw conflict();
            checkFolderMove(acl.browsePolicy(auth),after,sourceAnchor,destinationAnchor,file,meta,action);
            local(auth,actor,r.scope(),sourceAnchor,parent,file,destinationAnchor,destination,after,action,true);
            ledger.transition(r,"COMPLETED");
            return new Result(resultId,result.name());
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){
            if(reserved!=null)ledger.transition(reserved,attempted?"RECONCILE_REQUIRED":"ABORTED");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Transferência não confirmada");
        }catch(RuntimeException ex){
            if(reserved!=null)ledger.transition(reserved,attempted?"RECONCILE_REQUIRED":"ABORTED");
            throw ex;
        }
    }

    private Paths paths(Authentication auth,int actor,GoogleDriveConnectionService.FolderScope scope,String token,String sourceAnchor,
            String parent,String file,String destinationAnchor,String destination,Action action,boolean claimed){
        var p=acl.browsePolicy(auth);
        if(!scope.equals(p.scope()))throw inaccessible();
        requireAnchor(p,sourceAnchor);requireAnchor(p,destinationAnchor);
        var result=new Paths(browser.verifyPath(token,p,sourceAnchor,parent),browser.verifyPath(token,p,destinationAnchor,destination));
        requirePrivateFolder(google.uploadFolderMetadata(token,destination),destination);
        local(auth,actor,scope,sourceAnchor,parent,file,destinationAnchor,destination,result,action,claimed);
        return result;
    }
    private void local(Authentication auth,int actor,GoogleDriveConnectionService.FolderScope scope,String sourceAnchor,
            String parent,String file,String destinationAnchor,String destination,Paths paths,Action action,boolean claimed){
        if(authorization.currentUser(auth).getId()!=actor)throw inaccessible();
        var p=acl.browsePolicy(auth);
        if(!scope.equals(p.scope())||p.registered().contains(file)||scope.rootId().equals(file)||
            scope.rootId().equals(parent)||scope.rootId().equals(destination))throw inaccessible();
        requireAnchor(p,sourceAnchor);requireAnchor(p,destinationAnchor);
        var hidden=connection.hiddenUploadResources(scope);
        if((hidden.contains(file)&&!(claimed&&action==Action.MOVE))||paths.source().isEmpty()||paths.destination().isEmpty())throw inaccessible();
        for(var path:List.of(paths.source(),paths.destination()))for(String id:path)
            if(hidden.contains(id)||(p.registered().contains(id)&&!p.canView(id)))throw inaccessible();
        if(!operationAllowed(p,paths.source(),action==Action.MOVE?MOVE:DOWNLOAD)||!operationAllowed(p,paths.destination(),UPLOAD))
            throw inaccessible();
    }
    private static void checkFolderMove(GoogleDriveFolderAccessService.BrowsePolicy p,Paths paths,String sourceAnchor,
            String destinationAnchor,String file,Metadata meta,Action action){
        if(!FOLDER.equals(meta.mime()))return;
        // Folder moves are limited to the owner and same effective ACL boundary.
        // No moving an ancestor into itself; registered mount IDs never move.
        if(action!=Action.MOVE||!p.owner()||!sourceAnchor.equals(destinationAnchor)||
            paths.destination().contains(file)||!boundary(p,paths.source()).equals(boundary(p,paths.destination())))throw inaccessible();
    }
    private static String boundary(GoogleDriveFolderAccessService.BrowsePolicy p,List<String> path){
        return path.stream().filter(p.registered()::contains).findFirst().orElseThrow(GoogleDriveBrowserService::inaccessible);
    }
    static Metadata verify(JsonNode n,String id,String parent,Action action){
        if(n==null||!id.equals(n.path("id").asText())||!parent.equals(singleParent(n))||!isOwnedLive(n)||
            !n.path("shared").isBoolean()||n.path("shared").asBoolean()||
            !GoogleDriveUploadPolicy.validName(n.path("name").asText(""))||
            !GoogleDriveRenameService.validVersion(n.path("version").asText(""))||
            n.path("mimeType").asText("").isBlank()||"application/vnd.google-apps.shortcut".equals(n.path("mimeType").asText()))throw inaccessible();
        String mime=n.path("mimeType").asText(),hash=n.path("sha256Checksum").asText(""),size=n.path("size").asText("");
        if(action==Action.MOVE&&!n.path("capabilities").path("canMoveItemWithinDrive").asBoolean(false))throw inaccessible();
        if(action==Action.COPY&&(!GoogleDriveUploadPolicy.validMime(mime)||!hash.matches("[0-9a-f]{64}")||
            !size.matches("[0-9]{1,20}")||!n.path("capabilities").path("canCopy").asBoolean(false)))throw inaccessible();
        return new Metadata(n.path("name").asText(),mime,n.path("version").asText(),hash,size);
    }
    static String copyName(String original){
        String result="Cópia de "+original;
        return result.codePointCount(0,result.length())<=255?result:result.substring(0,result.offsetByCodePoints(0,255));
    }
    private static void requirePrivateFolder(JsonNode n,String id){
        if(n==null||!id.equals(n.path("id").asText())||!FOLDER.equals(n.path("mimeType").asText())||!isOwnedLive(n)||
            !n.path("shared").isBoolean()||n.path("shared").asBoolean()||!n.path("capabilities").path("canAddChildren").asBoolean(false))
            throw inaccessible();
    }
}
