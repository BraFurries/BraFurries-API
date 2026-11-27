package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.*;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static com.Brafurries.API.platform.drive.GoogleDriveTrashLedger.conflict;

@Service
public class GoogleDriveTrashService {
    private final GoogleDriveTrashLedger ledger;
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final boolean enabled;
    public GoogleDriveTrashService(GoogleDriveTrashLedger ledger,GoogleDriveFolderAccessService acl,
            GoogleDriveConnectionService connection,GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,
            PlatformAuthorizationService authorization,@Value("${GOOGLE_DRIVE_TRASH_ENABLED:false}") boolean enabled){
        this.ledger=ledger;this.acl=acl;this.connection=connection;this.browser=browser;this.google=google;
        this.authorization=authorization;this.enabled=enabled;
    }
    public record Result(String operationId,String status){}
    public record Item(String operationId,String name,String mimeType,String version,Instant trashedAt,boolean canRestore){}
    public record Page(List<Item> items,String nextCursor){}
    record Metadata(String name,String mime,String version,boolean canTrash,boolean canUntrash){}

    public Result trash(Authentication auth,String anchor,String parent,String file,String version,String id){
        requireEnabled();validate(anchor,parent,file,id,version);
        int actor=authorization.currentUser(auth).getId();
        var scope=acl.browsePolicy(auth).scope();
        GoogleDriveTrashLedger.Operation reserved=null;boolean attempted=false;
        try{
            String token=connection.browserAccessToken(scope);
            var path=path(auth,actor,scope,token,anchor,parent,file,true,false);
            var previous=ledger.find(id);
            if(previous!=null){
                if(previous.actor()!=actor||!previous.scope().equals(scope)||!previous.anchor().equals(anchor)||
                    !previous.parent().equals(parent)||!previous.file().equals(file)||!previous.version().equals(version))throw inaccessible();
                if(!"TRASHED".equals(previous.status()))throw conflict();
                verify(google.trashMetadata(token,file),file,parent,true);
                local(auth,actor,scope,anchor,parent,file,path,true,false);
                return new Result(id,"TRASHED");
            }
            local(auth,actor,scope,anchor,parent,file,path,false,false);
            var before=verify(google.trashMetadata(token,file),file,parent,false);
            if(!before.canTrash()||!version.equals(before.version()))throw conflict();
            var r=new GoogleDriveTrashLedger.Operation(id,scope,anchor,parent,file,before.name(),before.mime(),version,actor,"TRASHING",null);
            try{ledger.reserve(r);}catch(DuplicateKeyException ex){throw conflict();}
            reserved=r;
            if(!path.equals(path(auth,actor,scope,token,anchor,parent,file,true,false))||
                !before.equals(verify(google.trashMetadata(token,file),file,parent,false)))throw conflict();
            local(auth,actor,scope,anchor,parent,file,path,true,false);
            attempted=true;
            var changed=verify(google.setTrashed(token,file,true),file,parent,true);
            var confirmed=verify(google.trashMetadata(token,file),file,parent,true);
            if(!sameIdentity(before,changed)||!changed.equals(confirmed))throw conflict();
            if(!path.equals(path(auth,actor,scope,token,anchor,parent,file,true,false)))throw conflict();
            ledger.trashed(r);
            return new Result(id,"TRASHED");
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){
            if(reserved!=null)ledger.failed(reserved,false,attempted);
            throw unavailable();
        }catch(RuntimeException ex){
            if(reserved!=null)ledger.failed(reserved,false,attempted);
            throw ex;
        }
    }

    public Result restore(Authentication auth,String anchor,String id,String version){
        requireEnabled();
        if(!GoogleDriveConnectionService.validFolderId(anchor)||!GoogleDriveUploadPolicy.validSessionId(id)||
            !GoogleDriveRenameService.validVersion(version))throw inaccessible();
        int actor=authorization.currentUser(auth).getId();
        var scope=acl.browsePolicy(auth).scope();
        var r=ledger.find(id);
        if(r==null||!r.scope().equals(scope)||!r.anchor().equals(anchor))throw inaccessible();
        boolean claimed=false,attempted=false;
        try{
            String token=connection.browserAccessToken(scope);
            var path=path(auth,actor,scope,token,anchor,r.parent(),r.file(),true,true);
            if("RESTORED".equals(r.status())){
                verify(google.trashMetadata(token,r.file()),r.file(),r.parent(),false);
                return new Result(id,"RESTORED");
            }
            if(!"TRASHED".equals(r.status()))throw conflict();
            var before=verify(google.trashMetadata(token,r.file()),r.file(),r.parent(),true);
            if(!before.canUntrash()||!before.version().equals(version)||!before.mime().equals(r.mime()))throw conflict();
            ledger.claimRestore(r,actor);claimed=true;
            if(!path.equals(path(auth,actor,scope,token,anchor,r.parent(),r.file(),true,true))||
                !before.equals(verify(google.trashMetadata(token,r.file()),r.file(),r.parent(),true)))throw conflict();
            local(auth,actor,scope,anchor,r.parent(),r.file(),path,true,true);
            attempted=true;
            var changed=verify(google.setTrashed(token,r.file(),false),r.file(),r.parent(),false);
            var confirmed=verify(google.trashMetadata(token,r.file()),r.file(),r.parent(),false);
            if(!sameIdentity(before,changed)||!changed.equals(confirmed))throw conflict();
            if(!path.equals(path(auth,actor,scope,token,anchor,r.parent(),r.file(),true,true)))throw conflict();
            ledger.restored(r);
            return new Result(id,"RESTORED");
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){
            if(claimed)ledger.failed(r,true,attempted);
            throw unavailable();
        }catch(RuntimeException ex){
            if(claimed)ledger.failed(r,true,attempted);
            throw ex;
        }
    }

    public Page list(Authentication auth,String anchor,String cursor){
        requireEnabled();
        if(!GoogleDriveConnectionService.validFolderId(anchor)||(cursor!=null&&!GoogleDriveUploadPolicy.validSessionId(cursor)))throw inaccessible();
        int actor=authorization.currentUser(auth).getId();
        var policy=acl.browsePolicy(auth);
        requireAnchor(policy,anchor);
        if(policy.rights().entrySet().stream().noneMatch(e->policy.canView(e.getKey())&&e.getValue().contains(TRASH)))
            return new Page(List.of(),null);
        var rows=ledger.page(policy.scope(),anchor,cursor);
        if(rows.isEmpty())return new Page(List.of(),null);
        try{
            String token=connection.browserAccessToken(policy.scope());
            var entries=new ArrayList<Item>();
            int count=Math.min(10,rows.size());
            for(int i=0;i<count;i++){
                var r=rows.get(i);
                try{
                    var path=path(auth,actor,policy.scope(),token,anchor,r.parent(),r.file(),true,false);
                    var m=verify(google.trashMetadata(token,r.file()),r.file(),r.parent(),true);
                    if(!m.mime().equals(r.mime()))continue;
                    if(!path.equals(path(auth,actor,policy.scope(),token,anchor,r.parent(),r.file(),true,false)))continue;
                    var current=ledger.find(r.id());
                    if(current==null||!"TRASHED".equals(current.status()))continue;
                    var fresh=acl.browsePolicy(auth);
                    if(!policy.scope().equals(fresh.scope()))throw inaccessible();
                    local(auth,actor,policy.scope(),anchor,r.parent(),r.file(),path,true,false);
                    entries.add(new Item(r.id(),m.name(),m.mime(),m.version(),r.trashedAt(),
                        m.canUntrash()&&operationAllowed(fresh,path,UPLOAD)));
                }catch(GoogleDriveOAuthClient.ProviderFailure ex){
                    // 404 can be lost Google access, not proof of permanent deletion.
                    if(ex.status!=403&&ex.status!=404)throw ex;
                }catch(ResponseStatusException ex){
                    if(ex.getStatusCode()!=HttpStatus.NOT_FOUND)throw ex;
                }
            }
            var after=acl.browsePolicy(auth);
            if(!policy.scope().equals(after.scope())||authorization.currentUser(auth).getId()!=actor)throw inaccessible();
            requireAnchor(after,anchor);
            // Do not emit earlier entries if ACL changed while gathering the page.
            if(!policy.equals(after))throw inaccessible();
            return new Page(List.copyOf(entries),rows.size()>10?rows.get(count-1).id():null);
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){throw unavailable();}
    }

    private List<String> path(Authentication auth,int actor,GoogleDriveConnectionService.FolderScope scope,String token,
            String anchor,String parent,String file,boolean ownTrash,boolean restoring){
        var p=acl.browsePolicy(auth);
        if(!scope.equals(p.scope()))throw inaccessible();
        requireAnchor(p,anchor);
        var path=browser.verifyPath(token,p,anchor,parent);
        local(auth,actor,scope,anchor,parent,file,path,ownTrash,restoring);
        if(restoring){
            var dest=google.uploadFolderMetadata(token,parent);
            if(dest==null||!parent.equals(dest.path("id").asText())||!isOwnedLive(dest)||
                !"application/vnd.google-apps.folder".equals(dest.path("mimeType").asText())||
                !dest.path("shared").isBoolean()||dest.path("shared").asBoolean()||
                !dest.path("capabilities").path("canAddChildren").asBoolean(false))throw inaccessible();
        }
        local(auth,actor,scope,anchor,parent,file,path,ownTrash,restoring);
        return path;
    }
    private void local(Authentication auth,int actor,GoogleDriveConnectionService.FolderScope scope,String anchor,
            String parent,String file,List<String> path,boolean ownTrash,boolean restoring){
        if(authorization.currentUser(auth).getId()!=actor)throw inaccessible();
        var p=acl.browsePolicy(auth);
        if(!scope.equals(p.scope())||p.registered().contains(file)||file.equals(scope.rootId())||
            parent.equals(scope.rootId())||path.isEmpty())throw inaccessible();
        requireAnchor(p,anchor);
        var hidden=connection.hiddenUploadResources(scope);
        if(!ownTrash&&hidden.contains(file))throw inaccessible();
        for(String folder:path)if(hidden.contains(folder)||(p.registered().contains(folder)&&!p.canView(folder)))throw inaccessible();
        if(!operationAllowed(p,path,TRASH)||(restoring&&!operationAllowed(p,path,UPLOAD)))throw inaccessible();
    }
    static Metadata verify(JsonNode n,String id,String parent,boolean trashed){
        if(n==null||!id.equals(n.path("id").asText())||!parent.equals(singleParent(n))||
            !n.path("ownedByMe").isBoolean()||!n.path("ownedByMe").asBoolean()||
            !n.path("shared").isBoolean()||n.path("shared").asBoolean()||
            !n.path("trashed").isBoolean()||n.path("trashed").asBoolean()!=trashed||
            (trashed&&(!n.path("explicitlyTrashed").isBoolean()||!n.path("explicitlyTrashed").asBoolean()))||
            !GoogleDriveUploadPolicy.validName(n.path("name").asText(""))||
            !GoogleDriveRenameService.validVersion(n.path("version").asText(""))||
            n.path("mimeType").asText("").isBlank()||
            List.of("application/vnd.google-apps.folder","application/vnd.google-apps.shortcut").contains(n.path("mimeType").asText()))throw inaccessible();
        return new Metadata(n.path("name").asText(),n.path("mimeType").asText(),n.path("version").asText(),
            n.path("capabilities").path("canTrash").asBoolean(false),n.path("capabilities").path("canUntrash").asBoolean(false));
    }
    private static boolean sameIdentity(Metadata before,Metadata after){
        return before.name().equals(after.name())&&before.mime().equals(after.mime())&&
            new java.math.BigInteger(after.version()).compareTo(new java.math.BigInteger(before.version()))>0;
    }
    private static void validate(String anchor,String parent,String file,String id,String version){
        if(!GoogleDriveConnectionService.validFolderId(anchor)||!GoogleDriveConnectionService.validFolderId(parent)||
            !GoogleDriveConnectionService.validFolderId(file)||parent.equals(file)||!GoogleDriveUploadPolicy.validSessionId(id)||
            !GoogleDriveRenameService.validVersion(version))throw inaccessible();
    }
    private void requireEnabled(){if(!enabled)throw unavailable();}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Lixeira indisponível ou operação não confirmada");}
}
