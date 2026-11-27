package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.*;

@Service
public class GoogleDriveContentService {
    private static final int LIMIT=10*1024*1024;
    private static final int TEXT_LIMIT=1024*1024;
    private static final String GOOGLE="application/vnd.google-apps.";
    private static final Set<String> IMAGES=Set.of("image/png","image/jpeg","image/gif","image/webp");
    private static final Map<String,String> OFFICE=Map.of(
        GOOGLE+"document","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        GOOGLE+"spreadsheet","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        GOOGLE+"presentation","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveOAuthClient google;
    private final GoogleDriveBrowserService browser;
    private final Semaphore slots=new Semaphore(4);

    public GoogleDriveContentService(GoogleDriveFolderAccessService acl,GoogleDriveConnectionService connection,
            GoogleDriveOAuthClient google,GoogleDriveBrowserService browser) {
        this.acl=acl;this.connection=connection;this.google=google;this.browser=browser;
    }

    public record Content(byte[] bytes,String mime,String name) {}
    record Format(String exportMime,String outputMime,String suffix,int limit) {}
    record FileInfo(String name,String mime,String version,String size) {}

    static Format format(String mime,boolean preview) {
        if (preview) {
            if (IMAGES.contains(mime)) return new Format(null,mime,"",LIMIT);
            if (Set.of("text/plain","text/csv").contains(mime))
                return new Format(null,"text/plain","",TEXT_LIMIT);
            if ((GOOGLE+"document").equals(mime)) return new Format("text/plain","text/plain","",TEXT_LIMIT);
            if ((GOOGLE+"spreadsheet").equals(mime)) return new Format("text/csv","text/plain","",TEXT_LIMIT);
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Prévia indisponível para este formato");
        }
        if (OFFICE.containsKey(mime)) return new Format(OFFICE.get(mime),"application/octet-stream",
            switch(mime) {case GOOGLE+"document"->".docx";case GOOGLE+"spreadsheet"->".xlsx";default->".pptx";},LIMIT);
        if (mime.startsWith(GOOGLE)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Exportação indisponível");
        return new Format(null,"application/octet-stream","",LIMIT);
    }

    public Content read(Authentication auth,String anchor,String folder,String file,boolean preview) {
        if (!GoogleDriveConnectionService.validFolderId(anchor) ||
            !GoogleDriveConnectionService.validFolderId(folder) ||
            !GoogleDriveConnectionService.validFolderId(file) || file.equals(folder))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Identificadores inválidos");
        var before=acl.browsePolicy(auth);
        requireAnchor(before,anchor);
        if (folder.equals(before.scope().rootId()) ||
            (before.registered().contains(folder) && !before.canView(folder))) throw inaccessible();
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitos arquivos em processamento");
        try {
            String access=connection.browserAccessToken(before.scope());
            List<String> path=browser.verifyPath(access,before,anchor,folder);
            requireOperation(before,path,preview);
            if (before.registered().contains(file) || connection.hiddenUploadResources(before.scope()).contains(file)) throw inaccessible();
            FileInfo meta=verifyFile(google.fileMetadata(access,file),file,folder);
            Format format=format(meta.mime(),preview);
            if (format.exportMime()==null && meta.size()!=null) {
                try {
                    long size=Long.parseLong(meta.size());
                    if (size<0 || size>format.limit()) throw tooLarge();
                } catch (NumberFormatException ex) {throw inaccessible();}
            }
            // Do not release any bytes until the resource and policy are rechecked.
            byte[] bytes=google.content(access,file,format.exportMime(),format.limit());
            if (bytes.length>format.limit()) throw tooLarge();
            FileInfo current=verifyFile(google.fileMetadata(access,file),file,folder);
            if (!meta.equals(current) || !path.equals(browser.verifyPath(access,before,anchor,folder))) throw inaccessible();
            var after=acl.browsePolicy(auth);
            if (!before.scope().equals(after.scope()) || after.registered().contains(file)) throw inaccessible();
            var hidden=connection.hiddenUploadResources(after.scope());
            if (hidden.contains(file) || path.stream().anyMatch(hidden::contains)) throw inaccessible();
            requireAnchor(after,anchor);
            requireOperation(after,path,preview);
            if (preview && IMAGES.contains(format.outputMime()) && !validImage(bytes,format.outputMime()))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Imagem inválida");
            return new Content(bytes,format.outputMime(),safeName(meta.name(),format.suffix()));
        } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
            if (ex.status==413) throw tooLarge();
            if (ex.status==403 || ex.status==404) throw inaccessible();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível obter o arquivo no Google");
        } finally {slots.release();}
    }

    private static void requireOperation(GoogleDriveFolderAccessService.BrowsePolicy policy,List<String> path,boolean preview) {
        if (path.isEmpty()) throw inaccessible();
        for (String id:path) if (policy.registered().contains(id)&&!policy.canView(id)) throw inaccessible();
        if (!operationAllowed(policy,path,preview?VIEW:DOWNLOAD)) throw inaccessible();
    }

    static FileInfo verifyFile(JsonNode node,String id,String folder) {
        if (node==null || !id.equals(node.path("id").asText()) || !folder.equals(singleParent(node)) ||
            !isOwnedLive(node) || !node.path("name").isTextual() || !node.path("mimeType").isTextual() ||
            !node.path("capabilities").path("canDownload").isBoolean() ||
            !node.path("capabilities").path("canDownload").asBoolean()) throw inaccessible();
        String mime=node.path("mimeType").asText(),version=node.path("version").asText("");
        if (mime.isBlank() || mime.equals(GOOGLE+"folder") || mime.equals(GOOGLE+"shortcut") ||
            version.isBlank()) throw inaccessible();
        return new FileInfo(node.path("name").asText(),mime,version,node.path("size").isTextual()?node.path("size").asText():null);
    }
    static String safeName(String name,String suffix) {
        String safe=name.replaceAll("[\\p{Cntrl}/\\\\]","_").replaceAll("[\\p{Cf}]","_").strip();
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe="arquivo";
        int count=safe.codePointCount(0,safe.length());
        if (count>180) safe=safe.substring(0,safe.offsetByCodePoints(0,180));
        if (!suffix.isEmpty()&&!safe.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) safe+=suffix;
        return safe;
    }
    static boolean validImage(byte[] bytes,String mime) {
        return switch(mime) {
            case "image/png" -> starts(bytes,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a);
            case "image/jpeg" -> starts(bytes,0xff,0xd8,0xff);
            case "image/gif" -> starts(bytes,0x47,0x49,0x46,0x38,0x37,0x61)||starts(bytes,0x47,0x49,0x46,0x38,0x39,0x61);
            case "image/webp" -> bytes.length>=12 && starts(bytes,0x52,0x49,0x46,0x46)&&
                bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P';
            default -> false;
        };
    }
    private static boolean starts(byte[] bytes,int... prefix) {
        if (bytes.length<prefix.length) return false;
        for (int i=0;i<prefix.length;i++) if ((bytes[i]&255)!=prefix[i]) return false;
        return true;
    }
    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Arquivo excede o limite desta versão");
    }
}
