package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveBrowserService.inaccessible;

/** Internal pilot only. No user-facing mutation endpoint until real provider validation. */
@Service
public class GoogleDriveReplacementService {
    static final int CHUNK=8*1024*1024;
    private final GoogleDriveUploadLedger uploads;
    private final GoogleDriveReplacementLedger ledger;
    private final GoogleDriveRevisionService revisions;
    private final GoogleDriveConnectionService connection;
    private final GoogleDriveOAuthClient google;
    private final PlatformAuthorizationService authorization;
    private final String staging;
    private final long maxBytes;
    private final boolean enabled;
    private final Semaphore slots=new Semaphore(2);

    public GoogleDriveReplacementService(GoogleDriveUploadLedger uploads,GoogleDriveReplacementLedger ledger,
            GoogleDriveRevisionService revisions,GoogleDriveConnectionService connection,GoogleDriveOAuthClient google,
            PlatformAuthorizationService authorization,
            @Value("${GOOGLE_DRIVE_UPLOAD_STAGING_FOLDER_ID:}") String staging,
            @Value("${GOOGLE_DRIVE_REPLACEMENT_COMMIT_ENABLED:false}") boolean enabled,
            @Value("${GOOGLE_DRIVE_REPLACEMENT_MAX_BYTES:268435456}") long maxBytes){
        this.uploads=uploads;this.ledger=ledger;this.revisions=revisions;this.connection=connection;this.google=google;
        this.authorization=authorization;this.staging=staging.trim();this.enabled=enabled;this.maxBytes=maxBytes;
    }
    record Result(String sessionId,String revisionId){}
    record Stage(String version,String sha256,String revision){}

    Result apply(Authentication auth,String sessionId){
        authorization.requireOwner(auth); // provider integration pilot, not a general REPLACE endpoint
        if(!enabled||maxBytes<=0)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Substituição indisponível");
        if(!GoogleDriveUploadPolicy.validSessionId(sessionId))throw inaccessible();
        var session=uploads.owned(sessionId,authorization.currentUser(auth).getId());
        var r=session.reservation();
        var intent=ledger.intent(r);
        if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Substituições em andamento");
        boolean claimed=false,mutationAttempted=false;
        try{
            if(!staging.equals(r.staging())||r.size()<=0||r.size()>maxBytes)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Substituição fora dos limites do piloto");
            String token=connection.browserAccessToken(r.scope());
            var original=inspect(auth,r,intent,token);
            var previous=ledger.find(sessionId);
            if(previous!=null){
                if("APPLIED".equals(previous.status()))return new Result(sessionId,previous.newRevision());
                throw conflict();
            }
            if(session.expired()||!"OPEN".equals(session.status())||!matchesIntent(original,intent,r))throw conflict();
            verifyStaging(token,r);
            var source=stage(token,r);
            if(source.sha256().equals(original.sha256()))throw conflict();
            try{ledger.claim(r,intent,source.sha256(),source.version());}
            catch(DuplicateKeyException ex){throw conflict();}
            claimed=true;
            if(!original.equals(inspect(auth,r,intent,token))||!source.equals(stage(token,r)))throw conflict();
            verifyStaging(token,r);
            // Pin the OLD head before creating the new head. Never unpin automatically.
            mutationAttempted=true;
            var old=google.revision(token,intent.target(),intent.previousRevision());
            if(!preserved(old,intent.previousRevision()))
                requirePreserved(google.preserveRevision(token,intent.target(),intent.previousRevision()),intent.previousRevision());
            requirePreserved(google.revision(token,intent.target(),intent.previousRevision()),intent.previousRevision());
            var pinned=inspect(auth,r,intent,token);
            // Pinning may advance the metadata version, but must not change the bytes or head.
            if(!sameOriginalContent(original,pinned))throw conflict();
            if(!source.equals(stage(token,r)))throw conflict();
            verifyStaging(token,r);
            var uri=google.beginReplacementUpdate(token,intent.target(),r.mime(),r.size());
            var digest=sha256();
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(20).toNanos();
            try(var media=google.openMedia(token,r.providerId(),r.size())){
                long offset=0;
                while(offset<r.size()){
                    if(System.nanoTime()-deadline>=0)throw conflict();
                    int length=(int)Math.min(CHUNK,r.size()-offset);
                    byte[] bytes=media.input().readNBytes(length);
                    if(bytes.length!=length)throw conflict();
                    digest.update(bytes);
                    boolean last=offset+length==r.size();
                    if(last&&(media.input().read()!=-1||!HexFormat.of().formatHex(digest.digest()).equals(source.sha256())))
                        throw conflict();
                    String currentToken=connection.browserAccessToken(r.scope());
                    if(!pinned.equals(inspect(auth,r,intent,currentToken)))throw conflict();
                    if(last){
                        verifyStaging(currentToken,r);
                        if(!source.equals(stage(currentToken,r)))throw conflict();
                        requirePreserved(google.revision(currentToken,intent.target(),intent.previousRevision()),intent.previousRevision());
                        if(!pinned.equals(inspect(auth,r,intent,currentToken)))throw conflict();
                    }
                    if(System.nanoTime()-deadline>=0)throw conflict();
                    google.replacementChunk(uri,intent.target(),r.mime(),offset,r.size(),bytes);
                    offset+=length;
                }
            }
            String afterToken=connection.browserAccessToken(r.scope());
            var applied=inspect(auth,r,intent,afterToken);
            if(!original.name().equals(applied.name())||!r.mime().equals(applied.mime())||
                !Long.toString(r.size()).equals(applied.size())||!source.sha256().equals(applied.sha256())||
                original.head().equals(applied.head())||
                new java.math.BigInteger(applied.version()).compareTo(new java.math.BigInteger(pinned.version()))<=0)throw conflict();
            requirePreserved(google.revision(afterToken,intent.target(),intent.previousRevision()),intent.previousRevision());
            if(!applied.equals(inspect(auth,r,intent,afterToken)))throw conflict();
            ledger.complete(r,applied.head());
            return new Result(sessionId,applied.head());
        }catch(IOException|GoogleDriveOAuthClient.ProviderFailure ex){
            if(claimed){if(mutationAttempted)ledger.uncertain(r);else ledger.abort(r);}
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Substituição não confirmada");
        }catch(RuntimeException ex){
            if(claimed){if(mutationAttempted)ledger.uncertain(r);else ledger.abort(r);}
            throw ex;
        }finally{slots.release();}
    }

    private GoogleDriveRevisionService.Snapshot inspect(Authentication auth,GoogleDriveUploadLedger.Reservation r,
            GoogleDriveUploadLedger.Replacement i,String token){
        if(authorization.requireOwner(auth).getId()!=r.actor()||authorization.currentUser(auth).getId()!=r.actor())throw inaccessible();
        return revisions.inspect(auth,r.scope(),token,r.anchor(),r.destination(),i.target(),true).snapshot();
    }
    private void verifyStaging(String token,GoogleDriveUploadLedger.Reservation r){
        GoogleDriveUploadPolicy.requirePrivateStaging(google.uploadFolderMetadata(token,"root"),
            google.uploadFolderMetadata(token,r.scope().rootId()),google.uploadFolderMetadata(token,r.staging()),
            google.uploadFolderPermissions(token,r.staging()),r.scope().rootId(),r.staging());
    }
    private Stage stage(String token,GoogleDriveUploadLedger.Reservation r){
        var metadata=google.uploadedFile(token,r.providerId());
        GoogleDriveUploadPolicy.requireStagedFile(metadata,r.providerId(),r.id(),r.staging(),r.name(),r.mime(),r.size());
        var binary=google.revisionFile(token,r.providerId());
        var snapshot=GoogleDriveRevisionService.verify(binary,r.providerId(),r.staging(),false);
        if(!metadata.path("version").asText().equals(snapshot.version())||!r.name().equals(snapshot.name())||
            !r.mime().equals(snapshot.mime())||!Long.toString(r.size()).equals(snapshot.size())||
            !snapshot.sha256().matches("[0-9a-f]{64}"))throw conflict();
        return new Stage(snapshot.version(),snapshot.sha256(),snapshot.head());
    }
    static boolean matchesIntent(GoogleDriveRevisionService.Snapshot snapshot,GoogleDriveUploadLedger.Replacement i,
            GoogleDriveUploadLedger.Reservation r){
        return snapshot.version().equals(i.expectedVersion())&&snapshot.head().equals(i.previousRevision())&&
            snapshot.sha256().equals(i.previousSha256())&&snapshot.name().equals(i.previousName())&&
            snapshot.name().equals(r.name())&&snapshot.mime().equals(r.mime());
    }
    static boolean sameOriginalContent(GoogleDriveRevisionService.Snapshot a,GoogleDriveRevisionService.Snapshot b){
        return a.name().equals(b.name())&&a.mime().equals(b.mime())&&a.head().equals(b.head())&&
            a.sha256().equals(b.sha256())&&a.size().equals(b.size());
    }
    private static boolean preserved(JsonNode revision,String id){
        return revision!=null&&id.equals(revision.path("id").asText())&&
            revision.path("keepForever").isBoolean()&&revision.path("keepForever").asBoolean();
    }
    private static void requirePreserved(JsonNode revision,String id){if(!preserved(revision,id))throw conflict();}
    private static MessageDigest sha256(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Versão alterada ou substituição exige revisão");}
}
