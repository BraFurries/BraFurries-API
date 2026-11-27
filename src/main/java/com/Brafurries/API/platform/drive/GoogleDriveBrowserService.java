package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only explorer. No provider links, tokens or content are returned. */
@Service
public class GoogleDriveBrowserService {
    private static final String FOLDER="application/vnd.google-apps.folder";
    private static final String SHORTCUT="application/vnd.google-apps.shortcut";
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveOAuthClient google;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_STREAM_DOWNLOAD_ENABLED:false}")
    private boolean streamEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_STREAM_DOWNLOAD_MAX_BYTES:5368709120}")
    private long streamMaxBytes=5368709120L;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_CREATE_FOLDER_ENABLED:false}")
    private boolean createFolderEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_RENAME_ENABLED:false}")
    private boolean renameEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_REVISION_HISTORY_ENABLED:false}")
    private boolean historyEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_REPLACEMENT_COMMIT_ENABLED:false}")
    private boolean replacementEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_UPLOAD_PILOT_ENABLED:false}")
    private boolean uploadPilotEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_UPLOAD_CLEANUP_ENABLED:false}")
    private boolean cleanupEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_TRANSFER_ENABLED:false}")
    private boolean transferEnabled;
    @org.springframework.beans.factory.annotation.Value("${GOOGLE_DRIVE_TRASH_ENABLED:false}")
    private boolean trashEnabled;

    public GoogleDriveBrowserService(GoogleDriveFolderAccessService acl,
            GoogleDriveConnectionService connection,GoogleDriveOAuthClient google) {
        this.acl=acl;this.connection=connection;this.google=google;
    }
    public record Mount(String id,String name) {}
    public record Entry(String id,String name,String mimeType,boolean folder,String size,String modifiedTime,String version,boolean canRename,boolean canViewHistory,boolean canReplace,boolean canMove,boolean canCopy,boolean canTrash) {
        public Entry(String id,String name,String mimeType,boolean folder,String size,String modifiedTime){this(id,name,mimeType,folder,size,modifiedTime,null,false,false,false,false,false,false);}
    }
    public record Page(List<Entry> entries,String nextPageToken,boolean canDownload,boolean canUpload,boolean canCreateFolder,boolean canStreamDownload,long streamMaxBytes,boolean canViewTrash) {
        public Page(List<Entry> entries,String nextPageToken,boolean canDownload){this(entries,nextPageToken,canDownload,false,false,false,0,false);}
    }

    public List<Mount> mounts(Authentication auth) {
        var policy=acl.browsePolicy(auth);
        var hidden=connection.hiddenUploadResources(policy.scope());
        return policy.visible().entrySet().stream().filter(e->!hidden.contains(e.getKey()))
            .map(e->new Mount(e.getKey(),e.getValue())).toList();
    }

    public Page list(Authentication auth,String anchor,String folder,String pageToken) {
        if (!GoogleDriveConnectionService.validFolderId(anchor) ||
            !GoogleDriveConnectionService.validFolderId(folder) ||
            (pageToken!=null && (pageToken.length()>2048 || pageToken.chars().anyMatch(Character::isISOControl))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Parâmetros inválidos");
        var before=acl.browsePolicy(auth);
        requireAnchor(before,anchor);
        if (folder.equals(before.scope().rootId()) ||
            (before.registered().contains(folder) && !before.canView(folder))) throw inaccessible();
        String access=connection.browserAccessToken(before.scope());
        try {
            List<String> path=verifyPath(access,before,anchor,folder);
            var response=google.children(access,folder,pageToken);
            if (response==null || !response.path("files").isArray() ||
                response.path("incompleteSearch").asBoolean(false))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Consulta incompleta ao Google");
            // Re-read permissions after network I/O; do not return stale local grants.
            // Detect moves during the listing before returning metadata.
            if (!path.equals(verifyPath(access,before,anchor,folder))) throw inaccessible();
            var after=acl.browsePolicy(auth);
            if (!before.scope().equals(after.scope())) throw inaccessible();
            requireAnchor(after,anchor);
            for (String id:path)
                if (after.registered().contains(id) && !after.canView(id)) throw inaccessible();
            var hidden=connection.hiddenUploadResources(after.scope());
            if (path.stream().anyMatch(hidden::contains)) throw inaccessible();
            List<Entry> entries=new ArrayList<>();
            for (JsonNode file:response.path("files")) {
                String id=file.path("id").asText("");
                String type=file.path("mimeType").asText("");
                if (!GoogleDriveConnectionService.validFolderId(id) || id.equals(folder) ||
                    id.equals(after.scope().rootId()) || hidden.contains(id) || type.isBlank() || SHORTCUT.equals(type) ||
                    !file.path("name").isTextual() || !isOwnedLive(file) ||
                    !folder.equals(singleParent(file))) continue;
                if (after.registered().contains(id) && !after.canView(id)) continue;
                entries.add(new Entry(id,file.path("name").asText(),type,FOLDER.equals(type),
                    file.path("size").isTextual()?file.path("size").asText():null,
                    file.path("modifiedTime").isTextual()?file.path("modifiedTime").asText():null,
                    file.path("version").isTextual()?file.path("version").asText():null,
                    renameEnabled&&!after.registered().contains(id)&&GoogleDriveRenameService.validVersion(file.path("version").asText(""))&&
                        operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.RENAME),
                    historyEnabled&&GoogleDriveUploadPolicy.validMime(type)&&!after.registered().contains(id),
                    replacementEnabled&&uploadPilotEnabled&&cleanupEnabled&&after.owner()&&GoogleDriveUploadPolicy.validMime(type)&&
                        !after.registered().contains(id)&&GoogleDriveRenameService.validVersion(file.path("version").asText(""))&&
                        operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.REPLACE),
                    transferEnabled&&!after.registered().contains(id)&&GoogleDriveRenameService.validVersion(file.path("version").asText(""))&&
                        (!FOLDER.equals(type)||after.owner())&&operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.MOVE),
                    transferEnabled&&!after.registered().contains(id)&&GoogleDriveRenameService.validVersion(file.path("version").asText(""))&&
                        GoogleDriveUploadPolicy.validMime(type)&&operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.DOWNLOAD),
                    trashEnabled&&!FOLDER.equals(type)&&!after.registered().contains(id)&&GoogleDriveRenameService.validVersion(file.path("version").asText(""))&&
                        operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.TRASH)));
            }
            String next=response.path("nextPageToken").asText("");
            if (next.length()>2048) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Paginação indisponível");
            return new Page(List.copyOf(entries),next.isBlank()?null:next,
                operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.DOWNLOAD),
                operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.UPLOAD),
                createFolderEnabled&&operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.CREATE_FOLDER),
                streamEnabled&&streamMaxBytes>0&&operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.DOWNLOAD),
                streamEnabled&&streamMaxBytes>0?streamMaxBytes:0,
                trashEnabled&&operationAllowed(after,path,GoogleDriveFolderAccessService.Permission.TRASH));
        } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
            if (ex.status==403 || ex.status==404) throw inaccessible();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível consultar o Google");
        }
    }

    // Follow real Google parent pointers all the way to institutional root.
    // An independently managed folder is a new boundary, not an inherited grant.
    List<String> verifyPath(String access,GoogleDriveFolderAccessService.BrowsePolicy policy,
            String anchor,String folder) {
        var hidden=connection.hiddenUploadResources(policy.scope());
        var visited=new HashSet<String>();
        var belowAnchor=new ArrayList<String>();
        String current=folder;
        boolean found=false;
        for (int depth=0;depth<32;depth++) {
            if (hidden.contains(current) || !visited.add(current)) break;
            if (!found && policy.registered().contains(current) && !policy.canView(current)) throw inaccessible();
            JsonNode node=google.root(access,current);
            if (node==null || !current.equals(node.path("id").asText()) ||
                !FOLDER.equals(node.path("mimeType").asText()) || !isOwnedLive(node)) throw inaccessible();
            if (!found) belowAnchor.add(current);
            if (current.equals(anchor)) found=true;
            if (current.equals(policy.scope().rootId())) {
                if (!found) break;
                return List.copyOf(belowAnchor);
            }
            String parent=singleParent(node);
            if (!GoogleDriveConnectionService.validFolderId(parent)) break;
            current=parent;
        }
        throw inaccessible();
    }

    // Closest registered ancestor determines operation rights; all boundaries
    // on the navigated path still require VIEW. No fall-back to broader grants.
    static boolean operationAllowed(GoogleDriveFolderAccessService.BrowsePolicy policy,
            List<String> path,GoogleDriveFolderAccessService.Permission permission) {
        for (String id:path)
            if (policy.registered().contains(id)) return policy.canView(id)&&policy.permits(id,permission);
        return false;
    }

    static boolean isOwnedLive(JsonNode node) {
        return node.path("ownedByMe").isBoolean() && node.path("ownedByMe").asBoolean() &&
            node.path("trashed").isBoolean() && !node.path("trashed").asBoolean();
    }
    static String singleParent(JsonNode node) {
        JsonNode parents=node.path("parents");
        return parents.isArray() && parents.size()==1 && parents.get(0).isTextual()?parents.get(0).asText():null;
    }
    static void requireAnchor(GoogleDriveFolderAccessService.BrowsePolicy policy,String anchor) {
        if (!policy.canView(anchor)) throw inaccessible();
    }
    static ResponseStatusException inaccessible() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Pasta indisponível ou sem acesso");
    }
}
