package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.*;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;

@Service
public class GoogleDriveRevisionService {
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final GoogleDriveUploadSessionService uploads;
    private final boolean enabled;

    public GoogleDriveRevisionService(GoogleDriveFolderAccessService acl,GoogleDriveConnectionService connection,
            GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,GoogleDriveUploadSessionService uploads,
            @Value("${GOOGLE_DRIVE_REVISION_HISTORY_ENABLED:false}") boolean enabled){
        this.acl=acl;this.connection=connection;this.browser=browser;this.google=google;this.uploads=uploads;this.enabled=enabled;
    }
    public record Revision(String id,String modifiedTime,String size,boolean kept,boolean current){}
    public record History(List<Revision> revisions,String nextPageToken){}
    record Snapshot(String name,String mime,String version,String head,String sha256,String size){}
    record Target(GoogleDriveConnectionService.FolderScope scope,List<String> path,Snapshot snapshot){}

    public History history(Authentication auth,String anchor,String parent,String file,String pageToken){
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Histórico indisponível");
        validateIds(anchor,parent,file);
        if(pageToken!=null&&(pageToken.length()>2048||pageToken.chars().anyMatch(Character::isISOControl)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Paginação inválida");
        var policy=acl.browsePolicy(auth);
        requireLocal(policy,anchor,parent,file,List.of(),false);
        try {
            String token=connection.browserAccessToken(policy.scope());
            var target=inspect(auth,policy.scope(),token,anchor,parent,file,false);
            var result=google.revisions(token,file,pageToken);
            if(result==null||!result.path("revisions").isArray()||result.path("revisions").size()>100)throw unavailable();
            List<Revision> revisions=new ArrayList<>();
            for(var r:result.path("revisions")){
                String id=r.path("id").asText("");
                if(!validRevisionId(id)||!r.path("modifiedTime").isTextual()||
                   !r.path("size").isTextual()||!r.path("size").asText().matches("[0-9]{1,20}")||
                   !r.path("keepForever").isBoolean())throw unavailable();
                revisions.add(new Revision(id,r.path("modifiedTime").asText(),r.path("size").asText(),
                    r.path("keepForever").asBoolean(),id.equals(target.snapshot().head())));
            }
            if(!target.equals(inspect(auth,target.scope(),token,anchor,parent,file,false)))throw inaccessible();
            String next=result.path("nextPageToken").asText("");
            if(next.length()>2048||next.chars().anyMatch(Character::isISOControl))throw unavailable();
            return new History(List.copyOf(revisions),next.isBlank()?null:next);
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){throw provider(ex);}
    }

    // Internal only: no public endpoint until controlled commit and recovery exist.
    // Upload URL always points to a fresh staging file, never the original.
    GoogleDriveUploadSessionService.Start prepareReplacement(Authentication auth,String anchor,String parent,
            String file,String expectedVersion,String mime,long size){
        validateIds(anchor,parent,file);
        if(!GoogleDriveRenameService.validVersion(expectedVersion)||!GoogleDriveUploadPolicy.validMime(mime)||size<=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dados da substituição inválidos");
        var policy=acl.browsePolicy(auth);
        requireLocal(policy,anchor,parent,file,List.of(),true);
        try {
            String token=connection.browserAccessToken(policy.scope());
            var target=inspect(auth,policy.scope(),token,anchor,parent,file,true);
            var original=target.snapshot();
            if(!original.version().equals(expectedVersion)||!original.mime().equals(mime))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Versão ou formato do arquivo alterado");
            var intent=new GoogleDriveUploadLedger.Replacement(file,original.version(),original.head(),original.sha256(),original.name());
            return uploads.beginReplacement(auth,anchor,parent,original.name(),mime,size,intent,target.scope());
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){throw provider(ex);}
    }

    private Target inspect(Authentication auth,GoogleDriveConnectionService.FolderScope expected,String token,
            String anchor,String parent,String file,boolean replace){
        var before=acl.browsePolicy(auth);
        if(!expected.equals(before.scope()))throw inaccessible();
        requireLocal(before,anchor,parent,file,List.of(),replace);
        var path=browser.verifyPath(token,before,anchor,parent);
        requireLocal(before,anchor,parent,file,path,replace);
        var snapshot=verify(google.revisionFile(token,file),file,parent,replace);
        if(!path.equals(browser.verifyPath(token,before,anchor,parent)))throw inaccessible();
        var after=acl.browsePolicy(auth);
        if(!expected.equals(after.scope()))throw inaccessible();
        requireLocal(after,anchor,parent,file,path,replace);
        return new Target(expected,path,snapshot);
    }
    private void requireLocal(GoogleDriveFolderAccessService.BrowsePolicy policy,String anchor,String parent,
            String file,List<String> path,boolean replace){
        requireAnchor(policy,anchor);
        var hidden=connection.hiddenUploadResources(policy.scope());
        if(file.equals(policy.scope().rootId())||parent.equals(policy.scope().rootId())||
            policy.registered().contains(file)||hidden.contains(file)||hidden.contains(parent))throw inaccessible();
        for(String id:path)if(hidden.contains(id)||(policy.registered().contains(id)&&!policy.canView(id)))throw inaccessible();
        if(!path.isEmpty()&&!operationAllowed(policy,path,replace?REPLACE:VIEW))throw inaccessible();
    }
    static Snapshot verify(JsonNode node,String file,String parent,boolean replace){
        if(node==null||!file.equals(node.path("id").asText())||!parent.equals(singleParent(node))||
            !isOwnedLive(node)||!node.path("name").isTextual()||
            !GoogleDriveUploadPolicy.validMime(node.path("mimeType").asText(""))||
            !node.path("version").isTextual()||!GoogleDriveRenameService.validVersion(node.path("version").asText())||
            !validRevisionId(node.path("headRevisionId").asText(""))||
            !node.path("size").isTextual()||!node.path("size").asText().matches("[0-9]{1,20}"))throw inaccessible();
        String hash=node.path("sha256Checksum").asText("");
        if(replace&&(!node.path("shared").isBoolean()||node.path("shared").asBoolean()||
            !GoogleDriveUploadPolicy.validName(node.path("name").asText())||!hash.matches("[0-9a-f]{64}")||
            !node.path("capabilities").path("canModifyContent").isBoolean()||!node.path("capabilities").path("canModifyContent").asBoolean()))
            throw inaccessible();
        return new Snapshot(node.path("name").asText(),node.path("mimeType").asText(),
            node.path("version").asText(),node.path("headRevisionId").asText(),hash,node.path("size").asText());
    }
    static boolean validRevisionId(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{1,200}");}
    private static void validateIds(String anchor,String parent,String file){
        if(!GoogleDriveConnectionService.validFolderId(anchor)||!GoogleDriveConnectionService.validFolderId(parent)||
            !GoogleDriveConnectionService.validFolderId(file)||file.equals(parent))throw inaccessible();
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Histórico indisponível no Google");}
    private static ResponseStatusException provider(GoogleDriveOAuthClient.ProviderFailure ex){
        return ex.status==403||ex.status==404?inaccessible():unavailable();
    }
}
