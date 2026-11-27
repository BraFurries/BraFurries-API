package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.*;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.DOWNLOAD;

@Service
public class GoogleDriveStreamingDownloadService {
    private final GoogleDriveDownloadTicketService tickets;
    private final GoogleDriveFolderAccessService acl;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveBrowserService browser;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final Semaphore slots=new Semaphore(4);
    private final long maxBytes;
    private final boolean enabled;

    public GoogleDriveStreamingDownloadService(GoogleDriveDownloadTicketService tickets,GoogleDriveFolderAccessService acl,
            GoogleDriveConnectionService connection,GoogleDriveBrowserService browser,GoogleDriveOAuthClient google,
            PlatformAuthorizationService authorization,
            @Value("${GOOGLE_DRIVE_STREAM_DOWNLOAD_ENABLED:false}") boolean enabled,
            @Value("${GOOGLE_DRIVE_STREAM_DOWNLOAD_MAX_BYTES:5368709120}") long maxBytes) {
        this.tickets=tickets;this.acl=acl;this.connection=connection;this.browser=browser;this.google=google;
        this.authorization=authorization;this.enabled=enabled;this.maxBytes=maxBytes;
    }
    boolean enabled(){return enabled&&maxBytes>0;}

    record Verified(GoogleDriveConnectionService.FolderScope scope,List<String> path,
                    GoogleDriveContentService.FileInfo meta,long size) {}

    GoogleDriveDownloadTicketService.Ticket issue(Authentication auth,String anchor,String folder,String file) {
        requireEnabled();
        validateIds(anchor,folder,file);
        var policy=acl.browsePolicy(auth);
        requireAnchor(policy,anchor);
        if(!slots.tryAcquire())throw busy();
        try {
            String token=connection.browserAccessToken(policy.scope());
            verify(auth,policy.scope(),token,anchor,folder,file);
            return tickets.issue(auth,policy.scope(),anchor,folder,file);
        } catch(GoogleDriveOAuthClient.ProviderFailure ex){throw provider(ex);}
        finally{slots.release();}
    }

    void download(String secret,HttpServletResponse response) throws IOException {
        requireEnabled();
        if(!slots.tryAcquire())throw busy();
        try {
            var target=tickets.consume(secret);
            var auth=tickets.authenticate(target);
            requireActor(auth,target.actor());
            String token=connection.browserAccessToken(target.scope());
            var verified=verify(auth,target.scope(),token,target.anchor(),target.folder(),target.file());
            try(var media=google.openMedia(token,target.file(),verified.size())) {
                // Before first byte, repeat the live metadata and ACL checks.
                auth=tickets.authenticate(target);requireActor(auth,target.actor());
                if(!verified.equals(verify(auth,target.scope(),token,target.anchor(),target.folder(),target.file())))throw inaccessible();
                response.setStatus(200);
                response.setContentType("application/octet-stream");
                response.setContentLengthLong(verified.size());
                response.setHeader("Content-Disposition",ContentDisposition.attachment()
                    .filename(GoogleDriveContentService.safeName(verified.meta().name(),""),java.nio.charset.StandardCharsets.UTF_8).build().toString());
                response.setHeader("Cache-Control","no-store");
                response.setHeader("X-Content-Type-Options","nosniff");
                response.setHeader("Referrer-Policy","no-referrer");
                response.setHeader("X-Accel-Buffering","no");
                var output=response.getOutputStream();
                byte[] buffer=new byte[64*1024];
                long sent=0,nextCheck=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
                long deadline=System.nanoTime()+java.time.Duration.ofHours(2).toNanos();
                while(sent<verified.size()) {
                    if(System.nanoTime()-deadline>=0)throw new IOException("Download excedeu o prazo");
                    int count=media.input().read(buffer,0,(int)Math.min(buffer.length,verified.size()-sent));
                    if(count<0)throw new IOException("Download incompleto");
                    if(count==0)continue;
                    if(System.nanoTime()-nextCheck>=0) {
                        auth=tickets.authenticate(target);requireActor(auth,target.actor());
                        // Refresh token for metadata checks during a long transfer.
                        String currentToken=connection.browserAccessToken(target.scope());
                        if(!verified.equals(verify(auth,target.scope(),currentToken,target.anchor(),target.folder(),target.file())))
                            throw inaccessible();
                        nextCheck=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
                    }
                    output.write(buffer,0,count);sent+=count;
                }
                output.flush();
            }
        } catch(GoogleDriveOAuthClient.ProviderFailure ex) {
            if(response.isCommitted())throw new IOException("Download interrompido");
            throw provider(ex);
        } catch(ResponseStatusException ex) {
            if(response.isCommitted())throw new IOException("Download interrompido");
            response.reset();throw ex;
        } finally {slots.release();}
    }

    private Verified verify(Authentication auth,GoogleDriveConnectionService.FolderScope expected,
            String token,String anchor,String folder,String file) {
        validateIds(anchor,folder,file);
        var before=acl.browsePolicy(auth);
        if(!expected.equals(before.scope()))throw inaccessible();
        requireAnchor(before,anchor);
        if(folder.equals(expected.rootId())||before.registered().contains(file)||
            connection.hiddenUploadResources(expected).contains(file))throw inaccessible();
        var path=browser.verifyPath(token,before,anchor,folder);
        requireDownload(before,path);
        var meta=GoogleDriveContentService.verifyFile(google.fileMetadata(token,file),file,folder);
        if(meta.mime().startsWith("application/vnd.google-apps.")||meta.size()==null)
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Use a exportação de documentos Google");
        final long size;
        try{size=Long.parseLong(meta.size());}catch(NumberFormatException ex){throw inaccessible();}
        if(size<0||size>maxBytes)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Arquivo acima do limite de download");
        var after=acl.browsePolicy(auth);
        if(!expected.equals(after.scope())||after.registered().contains(file))throw inaccessible();
        requireAnchor(after,anchor);requireDownload(after,path);
        var hidden=connection.hiddenUploadResources(expected);
        if(hidden.contains(file)||path.stream().anyMatch(hidden::contains))throw inaccessible();
        return new Verified(expected,path,meta,size);
    }
    private static void validateIds(String anchor,String folder,String file) {
        if(!GoogleDriveConnectionService.validFolderId(anchor)||!GoogleDriveConnectionService.validFolderId(folder)||
            !GoogleDriveConnectionService.validFolderId(file)||file.equals(folder))throw inaccessible();
    }
    private static void requireDownload(GoogleDriveFolderAccessService.BrowsePolicy policy,List<String> path) {
        for(String id:path)if(policy.registered().contains(id)&&!policy.canView(id))throw inaccessible();
        if(!operationAllowed(policy,path,DOWNLOAD))throw inaccessible();
    }
    private void requireActor(Authentication auth,int actor) {
        if(authorization.currentUser(auth).getId()!=actor)throw inaccessible();
    }
    private void requireEnabled(){if(!enabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Download contínuo indisponível");}
    private static ResponseStatusException busy(){return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitos downloads simultâneos");}
    private static ResponseStatusException provider(GoogleDriveOAuthClient.ProviderFailure ex){
        return ex.status==403||ex.status==404?inaccessible():new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Download indisponível no Google");
    }
}
