package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.*;

@Service
public class GoogleDriveSearchService {
    private static final String FOLDER="application/vnd.google-apps.folder";
    private static final String SHORTCUT="application/vnd.google-apps.shortcut";
    private static final int CURSOR_LIMIT=512;
    private static final long CURSOR_TTL=Duration.ofMinutes(5).toNanos();
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final boolean enabled;
    private final Semaphore slots=new Semaphore(2);
    // Opaque, short-lived local cursors. No Google page token in the browser.
    private final LinkedHashMap<String,Cursor> cursors=new LinkedHashMap<>();
    record Cursor(int actor,GoogleDriveConnectionService.FolderScope scope,String query,String providerToken,long expires){}
    public record Hit(String id,String name,String mimeType,boolean folder,String modifiedTime,List<Mount> trail){}
    public record Page(List<Hit> entries,String nextCursor){}
    record Checked(Hit hit,List<String> ancestry,JsonNode metadata){}
    public GoogleDriveSearchService(GoogleDriveFolderAccessService acl,GoogleDriveConnectionService connection,
            GoogleDriveOAuthClient google,PlatformAuthorizationService authorization,
            @Value("${GOOGLE_DRIVE_SEARCH_ENABLED:false}") boolean enabled){
        this.acl=acl;this.connection=connection;this.google=google;this.authorization=authorization;this.enabled=enabled;
    }

    public Page search(Authentication auth,String raw,String cursor){
        if(!enabled)throw unavailable();
        String query=normalize(raw);
        int actor=authorization.currentUser(auth).getId();
        var policy=acl.browsePolicy(auth);
        if(policy.visible().isEmpty())return new Page(List.of(),null);
        String providerToken=cursor==null?null:resolve(cursor,actor,policy.scope(),query);
        if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Pesquisas em andamento");
        try{
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            String access=connection.browserAccessToken(policy.scope());
            var hidden=connection.hiddenUploadResources(policy.scope());
            var response=google.searchNames(access,query,providerToken);
            if(response==null||!response.path("files").isArray()||response.path("files").size()>10||
                response.path("incompleteSearch").asBoolean(false))throw unavailable();
            var candidates=new ArrayList<Checked>();
            var seen=new HashSet<String>();
            var firstCache=new HashMap<String,JsonNode>();
            for(var row:response.path("files")){
                String id=row.path("id").asText("");
                if(!GoogleDriveConnectionService.validFolderId(id)||!seen.add(id)||hidden.contains(id)||
                    id.equals(policy.scope().rootId())||!matches(row.path("name").asText(""),query))continue;
                try{
                    var checked=inspect(access,policy,hidden,id,query,firstCache,deadline);
                    candidates.add(checked);
                }catch(GoogleDriveOAuthClient.ProviderFailure ex){
                    if(ex.status!=403&&ex.status!=404)throw ex;
                }catch(ResponseStatusException ex){
                    if(ex.getStatusCode()!=HttpStatus.NOT_FOUND)throw ex;
                }
            }
            var middle=acl.browsePolicy(auth);
            if(!policy.equals(middle)||authorization.currentUser(auth).getId()!=actor)throw inaccessible();
            var currentHidden=connection.hiddenUploadResources(policy.scope());
            var confirmed=new ArrayList<Checked>();
            var secondCache=new HashMap<String,JsonNode>();
            for(var candidate:candidates){
                try{
                    var fresh=inspect(access,middle,currentHidden,candidate.hit().id(),query,secondCache,deadline);
                    if(candidate.equals(fresh))confirmed.add(fresh);
                }catch(GoogleDriveOAuthClient.ProviderFailure ex){
                    if(ex.status!=403&&ex.status!=404)throw ex;
                }catch(ResponseStatusException ex){
                    if(ex.getStatusCode()!=HttpStatus.NOT_FOUND)throw ex;
                }
            }
            var after=acl.browsePolicy(auth);
            if(!policy.equals(after)||authorization.currentUser(auth).getId()!=actor)throw inaccessible();
            var finalHidden=connection.hiddenUploadResources(after.scope());
            var hits=confirmed.stream().filter(c->!finalHidden.contains(c.hit().id())&&
                c.ancestry().stream().noneMatch(finalHidden::contains)).map(Checked::hit).toList();
            String next=response.path("nextPageToken").asText("");
            if(next.length()>2048||next.chars().anyMatch(Character::isISOControl))throw unavailable();
            return new Page(hits,next.isBlank()?null:remember(actor,policy.scope(),query,next));
        }catch(GoogleDriveOAuthClient.ProviderFailure ex){throw unavailable();}
        finally{slots.release();}
    }

    private Checked inspect(String access,GoogleDriveFolderAccessService.BrowsePolicy policy,Set<String> hidden,
            String id,String query,Map<String,JsonNode> cache,long deadline){
        budget(deadline);
        if(hidden.contains(id)||id.equals(policy.scope().rootId()))throw inaccessible();
        var n=google.searchMetadata(access,id);
        if(n==null||!id.equals(n.path("id").asText())||!isOwnedLive(n)||!n.path("name").isTextual()||
            !matches(n.path("name").asText(),query))throw inaccessible();
        String mime=n.path("mimeType").asText("");
        if(mime.isBlank()||SHORTCUT.equals(mime))throw inaccessible();
        boolean folder=FOLDER.equals(mime);
        if(policy.registered().contains(id)&&(!folder||!policy.canView(id)))throw inaccessible();
        String current=folder?id:singleParent(n);
        var visited=new LinkedHashSet<String>();
        var trail=new ArrayList<Mount>();
        boolean found=false;
        for(int depth=0;depth<32;depth++){
            budget(deadline);
            if(!GoogleDriveConnectionService.validFolderId(current)||hidden.contains(current)||!visited.add(current))throw inaccessible();
            final String key=current;
            var node=cache.computeIfAbsent(key,k->google.root(access,k));
            if(node==null||!key.equals(node.path("id").asText())||!FOLDER.equals(node.path("mimeType").asText())||
                !isOwnedLive(node)||!node.path("name").isTextual())throw inaccessible();
            if(folder&&key.equals(id)&&!Objects.equals(singleParent(n),singleParent(node)))throw inaccessible();
            if(key.equals(policy.scope().rootId())){
                if(!found)throw inaccessible();
                Collections.reverse(trail);
                return new Checked(new Hit(id,n.path("name").asText(),mime,folder,
                    n.path("modifiedTime").isTextual()?n.path("modifiedTime").asText():null,List.copyOf(trail)),
                    List.copyOf(visited),n);
            }
            if(!found){
                if(policy.registered().contains(key)){
                    if(!policy.canView(key))throw inaccessible();
                    trail.add(new Mount(key,policy.visible().get(key)));found=true;
                }else trail.add(new Mount(key,node.path("name").asText()));
            }
            current=singleParent(node);
        }
        throw inaccessible();
    }

    private static void budget(long deadline){if(System.nanoTime()-deadline>=0)throw unavailable();}
    static String normalize(String raw){
        String q=raw==null?"":raw.strip();
        int length=q.codePointCount(0,q.length());
        if(length<2||length>100||q.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Digite entre 2 e 100 caracteres");
        return q;
    }
    static boolean matches(String name,String query){
        return name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
    private synchronized String remember(int actor,GoogleDriveConnectionService.FolderScope scope,String query,String token){
        long now=System.nanoTime();
        cursors.values().removeIf(c->now-c.expires()>=0);
        while(cursors.size()>=CURSOR_LIMIT)cursors.remove(cursors.keySet().iterator().next());
        String id=UUID.randomUUID().toString();
        cursors.put(id,new Cursor(actor,scope,query,token,now+CURSOR_TTL));
        return id;
    }
    private synchronized String resolve(String id,int actor,GoogleDriveConnectionService.FolderScope scope,String query){
        if(!GoogleDriveUploadPolicy.validSessionId(id))throw invalidCursor();
        var c=cursors.get(id);
        if(c==null||System.nanoTime()-c.expires()>=0||c.actor()!=actor||!c.scope().equals(scope)||!c.query().equals(query))
            throw invalidCursor();
        return c.providerToken();
    }
    private static ResponseStatusException invalidCursor(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Pesquisa expirada. Pesquise novamente.");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Pesquisa temporariamente indisponível");}
}
