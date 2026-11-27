package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Fail-closed provider metadata checks for direct upload. No public routes. */
final class GoogleDriveUploadPolicy {
    static final long DEFAULT_MAX_BYTES=5L*1024*1024*1024;
    static final int MAX_ACTIVE_PER_USER=3;
    static final java.time.Duration SESSION_TTL=java.time.Duration.ofHours(2);
    private static final String FOLDER="application/vnd.google-apps.folder";
    private GoogleDriveUploadPolicy() {}

    static boolean validSessionId(String id) {
        if (id==null) return false;
        try {return UUID.fromString(id).toString().equals(id);}
        catch (IllegalArgumentException ex) {return false;}
    }

    static boolean validMime(String mime) {
        return mime!=null && mime.length()<=255 &&
            mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") &&
            !mime.toLowerCase(java.util.Locale.ROOT).startsWith("application/vnd.google-apps.");
    }

    static boolean validName(String name) {
        return name!=null && !name.isBlank() && name.equals(name.strip()) &&
            !name.equals(".") && !name.equals("..") && name.codePointCount(0,name.length())<=255 &&
            name.codePoints().noneMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT ||
                c=='/' || c==92 || (c>=0xd800 && c<=0xdfff));
    }

    static URI sessionUri(String value) {return sessionUri(value,null);}

    static URI sessionUri(String value,String target) {
        if(target!=null&&!GoogleDriveConnectionService.validFolderId(target))throw invalidProvider();
        String expectedPath="/upload/drive/v3/files"+(target==null?"":"/"+target);
        // Never forward unexpected hosts, redirects or fragments to the browser.
        // Exception messages intentionally omit the capability URL.
        if (value==null || value.length()>8192) throw invalidProvider();
        final URI uri;
        try {uri=URI.create(value);}
        catch (IllegalArgumentException ex) {throw invalidProvider();}
        if (!"https".equals(uri.getScheme()) || !"www.googleapis.com".equals(uri.getHost()) ||
            uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getFragment()!=null ||
            !expectedPath.equals(uri.getRawPath()) || uri.getRawQuery()==null)
            throw invalidProvider();
        var query=new HashMap<String,String>();
        for (String pair:uri.getRawQuery().split("&",-1)) {
            String[] parts=pair.split("=",2);
            if (parts.length!=2 || query.putIfAbsent(parts[0],parts[1])!=null) throw invalidProvider();
        }
        if (!query.keySet().stream().allMatch(k->k.equals("uploadType")||k.equals("upload_id")||k.equals("upload_protocol")) ||
            !"resumable".equals(query.get("uploadType")) ||
            !query.getOrDefault("upload_id","").matches("[A-Za-z0-9_-]{1,4096}") ||
            (query.containsKey("upload_protocol") && !"resumable".equals(query.get("upload_protocol"))))
            throw invalidProvider();
        return uri;
    }

    static String generatedFileId(JsonNode response) {
        if (response==null || !response.path("ids").isArray() || response.path("ids").size()!=1 ||
            !response.path("ids").get(0).isTextual()) throw invalidProvider();
        String id=response.path("ids").get(0).asText();
        if (!GoogleDriveConnectionService.validFolderId(id)) throw invalidProvider();
        return id;
    }

    // Pilot: staging and institutional root must be separate direct children of
    // My Drive root. This intentionally rejects nested institutional roots until
    // a more general ancestry verifier is added. It also excludes Shared Drives.
    static void requirePrivateStaging(JsonNode myDrive,JsonNode institutional,JsonNode staging,
            JsonNode permissions,String rootId,String stagingId) {
        if (myDrive==null || institutional==null || staging==null || permissions==null ||
            !GoogleDriveConnectionService.validFolderId(rootId) ||
            !GoogleDriveConnectionService.validFolderId(stagingId)) throw invalidProvider();
        String myId=myDrive.path("id").asText("");
        if (!GoogleDriveConnectionService.validFolderId(myId) || rootId.equals(stagingId) ||
            myId.equals(rootId) || myId.equals(stagingId) ||
            !folder(myDrive,myId) || !folder(institutional,rootId) || !folder(staging,stagingId) ||
            !myId.equals(GoogleDriveBrowserService.singleParent(institutional)) ||
            !myId.equals(GoogleDriveBrowserService.singleParent(staging)) ||
            !falseBoolean(staging,"shared") ||
            !staging.path("capabilities").path("canAddChildren").isBoolean() ||
            !staging.path("capabilities").path("canAddChildren").asBoolean() ||
            (permissions.has("nextPageToken") && !permissions.path("nextPageToken").asText("").isBlank()) ||
            !permissions.path("permissions").isArray() || permissions.path("permissions").size()!=1)
            throw invalidProvider();
        JsonNode owner=permissions.path("permissions").get(0);
        if (!"user".equals(owner.path("type").asText()) || !"owner".equals(owner.path("role").asText()) ||
            owner.path("id").asText("").isBlank() ||
            owner.path("deleted").asBoolean(false) || owner.path("pendingOwner").asBoolean(false))
            throw invalidProvider();
    }

    static void requireStagedFile(JsonNode file,String providerId,String sessionId,String stagingId,
            String name,String mime,long expectedSize) {
        if (file==null || !GoogleDriveConnectionService.validFolderId(providerId) ||
            !GoogleDriveConnectionService.validFolderId(stagingId) || !validSessionId(sessionId) ||
            !validName(name) || !validMime(mime) || expectedSize<=0 ||
            !providerId.equals(file.path("id").asText()) ||
            !name.equals(file.path("name").asText()) || !mime.equals(file.path("mimeType").asText()) ||
            !GoogleDriveBrowserService.isOwnedLive(file) || !falseBoolean(file,"shared") ||
            !stagingId.equals(GoogleDriveBrowserService.singleParent(file)) ||
            !sessionId.equals(file.path("appProperties").path("brafurriesUploadSession").asText()) ||
            !file.path("size").isTextual() || !Long.toString(expectedSize).equals(file.path("size").asText()) ||
            !file.path("version").isTextual() || file.path("version").asText().isBlank())
            throw invalidProvider();
    }

    static void requirePublishedFile(JsonNode file,GoogleDriveUploadLedger.Reservation r) {
        // Publication pilot permits private destinations only. Same identity and
        // bytes as staged file; the sole allowed parent change is the destination.
        requireStagedFile(file,r.providerId(),r.id(),r.destination(),r.name(),r.mime(),r.size());
    }

    static boolean identifiableStagedFile(JsonNode file,GoogleDriveUploadLedger.Reservation r) {
        // Cleanup may receive wrong size/MIME; identity and isolation matter,
        // not successful upload validation. Never touch a moved or shared file.
        return file!=null && r.providerId().equals(file.path("id").asText()) &&
            file.path("ownedByMe").isBoolean() && file.path("ownedByMe").asBoolean() &&
            file.path("trashed").isBoolean() && falseBoolean(file,"shared") &&
            r.staging().equals(GoogleDriveBrowserService.singleParent(file)) &&
            r.id().equals(file.path("appProperties").path("brafurriesUploadSession").asText()) &&
            validMime(file.path("mimeType").asText(""));
    }

    private static boolean folder(JsonNode node,String expectedId) {
        return expectedId.equals(node.path("id").asText()) && FOLDER.equals(node.path("mimeType").asText()) &&
            GoogleDriveBrowserService.isOwnedLive(node);
    }
    private static boolean falseBoolean(JsonNode node,String field) {
        return node.path(field).isBoolean() && !node.path(field).asBoolean();
    }
    private static ResponseStatusException invalidProvider() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Não foi possível comprovar a segurança da sessão de upload");
    }
}
