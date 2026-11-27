package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Fixed Google endpoints, no redirects or logging of provider bodies/credentials. */
@Component
class GoogleDriveOAuthClient {
    static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    GoogleDriveOAuthClient(ObjectMapper json) { this.json = json; }

    static String form(Map<String,String> parameters) {
        return parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    JsonNode token(Map<String,String> parameters) {
        return send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(parameters))));
    }

    JsonNode identity(String token) {
        return send(HttpRequest.newBuilder(URI.create("https://openidconnect.googleapis.com/v1/userinfo"))
            .header("Authorization", "Bearer " + token).GET());
    }

    JsonNode root(String token, String folderId) {
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"
            + folderId + "?fields=id,name,parents,mimeType,trashed,ownedByMe,capabilities(canShare)"))
            .header("Authorization", "Bearer " + token).GET());
    }

    JsonNode children(String token,String parent,String pageToken) {
        if (!GoogleDriveConnectionService.validFolderId(parent)) throw new IllegalArgumentException("Invalid parent");
        var params=new java.util.HashMap<String,String>();
        params.put("q","'"+parent+"' in parents and trashed = false");
        params.put("pageSize","100");
        params.put("orderBy","folder,name");
        params.put("fields","nextPageToken,incompleteSearch,files(id,name,mimeType,parents,trashed,ownedByMe,size,modifiedTime,version)");
        if (pageToken!=null && !pageToken.isBlank()) params.put("pageToken",pageToken);
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files?"+form(params)))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode fileMetadata(String token,String id) {
        if (!GoogleDriveConnectionService.validFolderId(id)) throw new IllegalArgumentException("Invalid id");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,name,mimeType,parents,trashed,ownedByMe,size,version,capabilities(canDownload)"))
            .header("Authorization","Bearer "+token).GET());
    }

    byte[] content(String token,String id,String exportMime,int limit) {
        if (!GoogleDriveConnectionService.validFolderId(id)) throw new IllegalArgumentException("Invalid id");
        String url="https://www.googleapis.com/drive/v3/files/"+id+
            (exportMime==null?"?alt=media":"/export?"+form(Map.of("mimeType",exportMime)));
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Authorization","Bearer "+token).GET().build();
        var future=http.sendAsync(request,info->new BoundedBody(info.statusCode()==200?limit:8192));
        try {
            var response=future.get(30,java.util.concurrent.TimeUnit.SECONDS);
            if (response.statusCode()!=200) throw new ProviderFailure(response.statusCode());
            return response.body();
        } catch (InterruptedException ex) {
            future.cancel(true);Thread.currentThread().interrupt();throw new ProviderFailure(0);
        } catch (java.util.concurrent.TimeoutException ex) {
            future.cancel(true);throw new ProviderFailure(0);
        } catch (java.util.concurrent.ExecutionException ex) {
            for (Throwable cause=ex;cause!=null;cause=cause.getCause())
                if (cause instanceof SizeLimit) throw new ProviderFailure(413);
            throw new ProviderFailure(0);
        }
    }

    private static final class SizeLimit extends RuntimeException {}
    // Reject before buffering an oversized response, including chunked bodies.
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private long received;
        private java.util.concurrent.Flow.Subscription subscription;
        BoundedBody(int limit) {this.limit=limit;}
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
            for (var buffer:buffers) received+=buffer.remaining();
            if (received>limit) {
                subscription.cancel();delegate.onError(new SizeLimit());return;
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error){delegate.onError(error);}
        public void onComplete(){delegate.onComplete();}
    }

    JsonNode generateUploadId(String token) {
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files"))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode uploadFolderMetadata(String token,String id) {
        if (!"root".equals(id) && !GoogleDriveConnectionService.validFolderId(id))
            throw new IllegalArgumentException("Invalid folder");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,mimeType,parents,trashed,ownedByMe,shared,capabilities(canAddChildren)"))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode uploadFolderPermissions(String token,String id) {
        if (!GoogleDriveConnectionService.validFolderId(id)) throw new IllegalArgumentException("Invalid folder");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "/permissions?pageSize=100&fields=nextPageToken,permissions(id,type,role,deleted,pendingOwner)"))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode uploadedFile(String token,String id) {
        if (!GoogleDriveConnectionService.validFolderId(id)) throw new IllegalArgumentException("Invalid file");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,name,mimeType,parents,trashed,ownedByMe,shared,size,version,appProperties"))
            .header("Authorization","Bearer "+token).GET());
    }

    URI beginResumableUpload(String token,String fileId,String staging,String sessionId,
            String name,String mime,long size) {
        if (!GoogleDriveConnectionService.validFolderId(fileId) ||
            !GoogleDriveConnectionService.validFolderId(staging) || size<=0 ||
            !GoogleDriveUploadPolicy.validMime(mime) || !GoogleDriveUploadPolicy.validName(name) ||
            !GoogleDriveUploadPolicy.validSessionId(sessionId)) throw new IllegalArgumentException("Invalid upload metadata");
        var metadata=json.createObjectNode().put("id",fileId).put("name",name).put("mimeType",mime);
        metadata.putArray("parents").add(staging);
        metadata.putObject("appProperties").put("brafurriesUploadSession",sessionId);
        var request=HttpRequest.newBuilder(URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"))
            .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token)
            .header("Content-Type","application/json; charset=UTF-8")
            .header("X-Upload-Content-Type",mime).header("X-Upload-Content-Length",Long.toString(size))
            .POST(HttpRequest.BodyPublishers.ofString(metadata.toString())).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.discarding());
            if (response.statusCode()!=200) throw new ProviderFailure(response.statusCode());
            return GoogleDriveUploadPolicy.sessionUri(response.headers().firstValue("Location").orElse(""));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();throw new ProviderFailure(0);
        } catch (java.io.IOException ex) {throw new ProviderFailure(0);}
    }

    JsonNode moveUploadedFile(String token,String id,String from,String to) {
        if (!GoogleDriveConnectionService.validFolderId(id) ||
            !GoogleDriveConnectionService.validFolderId(from) ||
            !GoogleDriveConnectionService.validFolderId(to) || from.equals(to))
            throw new IllegalArgumentException("Invalid move");
        String query=form(Map.of("addParents",to,"removeParents",from,
            "fields","id,name,mimeType,parents,trashed,ownedByMe,shared,size,version,appProperties"));
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+"?"+query))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{}")));
    }

    JsonNode trashStagedFile(String token,String id) {
        if (!GoogleDriveConnectionService.validFolderId(id)) throw new IllegalArgumentException("Invalid file");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,parents,trashed,ownedByMe,appProperties"))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"trashed\":true}")));
    }

    // Fixed provider host, bounded connect/read idle time, no redirects.
    MediaStream openMedia(String token,String id,long expectedSize) {
        if (!GoogleDriveConnectionService.validFolderId(id) || expectedSize<0) throw new IllegalArgumentException("Invalid media");
        java.net.HttpURLConnection request=null;
        try {
            request=(java.net.HttpURLConnection)URI.create("https://www.googleapis.com/drive/v3/files/"+id+"?alt=media").toURL().openConnection();
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(5000);request.setReadTimeout(30000);
            request.setRequestProperty("Authorization","Bearer "+token);
            request.setRequestProperty("Accept-Encoding","identity");
            int status=request.getResponseCode();
            if (status!=200) throw new ProviderFailure(status);
            long length=request.getContentLengthLong();
            if ((length>=0&&length!=expectedSize) ||
                (request.getContentEncoding()!=null&&!"identity".equalsIgnoreCase(request.getContentEncoding())))
                throw new ProviderFailure(0);
            return new MediaStream(request,request.getInputStream());
        } catch (java.io.IOException ex) {
            if(request!=null)request.disconnect();
            throw new ProviderFailure(0);
        } catch (RuntimeException ex) {
            if(request!=null)request.disconnect();
            throw ex;
        }
    }
    record MediaStream(java.net.HttpURLConnection connection,java.io.InputStream input) implements AutoCloseable {
        @Override public void close() {
            try {input.close();}catch(java.io.IOException ignored){}finally{connection.disconnect();}
        }
    }

    JsonNode createFolder(String token,String id,String parent,String name,String operationId) {
        if (!GoogleDriveConnectionService.validFolderId(id)||!GoogleDriveConnectionService.validFolderId(parent)||
            !GoogleDriveUploadPolicy.validName(name)||!GoogleDriveUploadPolicy.validSessionId(operationId))
            throw new IllegalArgumentException("Invalid folder metadata");
        var metadata=json.createObjectNode().put("id",id).put("name",name).put("mimeType","application/vnd.google-apps.folder");
        metadata.putArray("parents").add(parent);
        metadata.putObject("appProperties").put("brafurriesFolderOperation",operationId);
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files?fields=id,name,mimeType,parents,trashed,ownedByMe,shared,appProperties"))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(metadata.toString())));
    }

    JsonNode renameMetadata(String token,String id) {
        if(!GoogleDriveConnectionService.validFolderId(id))throw new IllegalArgumentException("Invalid resource");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,name,mimeType,parents,trashed,ownedByMe,shared,version,capabilities(canRename)"))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode rename(String token,String id,String name) {
        if(!GoogleDriveConnectionService.validFolderId(id)||!GoogleDriveUploadPolicy.validName(name))
            throw new IllegalArgumentException("Invalid rename");
        var metadata=json.createObjectNode().put("name",name);
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,name,mimeType,parents,trashed,ownedByMe,shared,version,capabilities(canRename)"))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method("PATCH",HttpRequest.BodyPublishers.ofString(metadata.toString())));
    }

    JsonNode revisionFile(String token,String id) {
        if(!GoogleDriveConnectionService.validFolderId(id))throw new IllegalArgumentException("Invalid resource");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,name,mimeType,parents,trashed,ownedByMe,shared,version,size,sha256Checksum,headRevisionId,capabilities(canDownload,canEdit,canModifyContent)"))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode revisions(String token,String id,String pageToken) {
        if(!GoogleDriveConnectionService.validFolderId(id))throw new IllegalArgumentException("Invalid resource");
        var params=new java.util.HashMap<String,String>();
        params.put("pageSize","100");params.put("fields","nextPageToken,revisions(id,modifiedTime,size,mimeType,keepForever)");
        if(pageToken!=null&&!pageToken.isBlank())params.put("pageToken",pageToken);
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+"/revisions?"+form(params)))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode revision(String token,String file,String revision) {
        return revisionRequest(token,file,revision,false);
    }

    JsonNode preserveRevision(String token,String file,String revision) {
        return revisionRequest(token,file,revision,true);
    }

    private JsonNode revisionRequest(String token,String file,String revision,boolean preserve) {
        if(!GoogleDriveConnectionService.validFolderId(file)||!GoogleDriveRevisionService.validRevisionId(revision))
            throw new IllegalArgumentException("Invalid revision");
        var builder=HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+file+
            "/revisions/"+revision+"?fields=id,keepForever,size,mimeType"))
            .header("Authorization","Bearer "+token);
        if(preserve)builder.header("Content-Type","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString("{\"keepForever\":true}"));
        else builder.GET();
        return send(builder);
    }

    // This capability is used exclusively by the server, never returned to a browser.
    URI beginReplacementUpdate(String token,String file,String mime,long size) {
        if(!GoogleDriveConnectionService.validFolderId(file)||!GoogleDriveUploadPolicy.validMime(mime)||size<=0)
            throw new IllegalArgumentException("Invalid replacement");
        var request=HttpRequest.newBuilder(URI.create("https://www.googleapis.com/upload/drive/v3/files/"+file+"?uploadType=resumable"))
            .timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token)
            .header("Content-Type","application/json; charset=UTF-8")
            .header("X-Upload-Content-Type",mime).header("X-Upload-Content-Length",Long.toString(size))
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{}")).build();
        try{
            var response=http.send(request,HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()!=200)throw new ProviderFailure(response.statusCode());
            return GoogleDriveUploadPolicy.sessionUri(response.headers().firstValue("Location").orElse(""),file);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new ProviderFailure(0);}
        catch(java.io.IOException ex){throw new ProviderFailure(0);}
    }

    void replacementChunk(URI session,String file,String mime,long offset,long total,byte[] bytes) {
        URI safe=GoogleDriveUploadPolicy.sessionUri(session.toString(),file);
        if(!GoogleDriveUploadPolicy.validMime(mime)||offset<0||total<=0||bytes.length==0||
            bytes.length>8*1024*1024||offset>=total||bytes.length>total-offset||
            (offset+bytes.length<total&&bytes.length%(256*1024)!=0))
            throw new IllegalArgumentException("Invalid replacement chunk");
        long end=offset+bytes.length-1;
        var request=HttpRequest.newBuilder(safe).timeout(Duration.ofSeconds(60))
            .header("Content-Type",mime).header("Content-Range","bytes "+offset+"-"+end+"/"+total)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try{
            var response=http.send(request,HttpResponse.BodyHandlers.discarding());
            // Never blindly resend after an ambiguous provider acknowledgement.
            if(end+1==total){
                if(response.statusCode()!=200&&response.statusCode()!=201)throw new ProviderFailure(response.statusCode());
            }else if(response.statusCode()!=308||
                !("bytes=0-"+end).equals(response.headers().firstValue("Range").orElse("")))
                throw new ProviderFailure(response.statusCode());
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new ProviderFailure(0);}
        catch(java.io.IOException ex){throw new ProviderFailure(0);}
    }

    JsonNode transferMetadata(String token,String id) {
        if(!GoogleDriveConnectionService.validFolderId(id))throw new IllegalArgumentException("Invalid resource");
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+id+
            "?fields=id,name,mimeType,parents,trashed,ownedByMe,shared,version,size,sha256Checksum,capabilities(canCopy,canMoveItemWithinDrive)"))
            .header("Authorization","Bearer "+token).GET());
    }

    JsonNode copyFile(String token,String source,String result,String parent,String name,String operationId) {
        if(!GoogleDriveConnectionService.validFolderId(source)||!GoogleDriveConnectionService.validFolderId(result)||
            !GoogleDriveConnectionService.validFolderId(parent)||!GoogleDriveUploadPolicy.validName(name)||
            !GoogleDriveUploadPolicy.validSessionId(operationId))throw new IllegalArgumentException("Invalid copy");
        var metadata=json.createObjectNode().put("id",result).put("name",name);
        metadata.putArray("parents").add(parent);
        metadata.putObject("appProperties").put("brafurriesTransferOperation",operationId);
        return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/"+source+"/copy?fields=id&ignoreDefaultVisibility=true"))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(metadata.toString())));
    }

    private JsonNode send(HttpRequest.Builder builder) {
        try {
            var response = http.send(builder.timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ProviderFailure(response.statusCode());
            return json.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure(0);
        } catch (java.io.IOException ex) {
            throw new ProviderFailure(0);
        }
    }

    static final class ProviderFailure extends RuntimeException {
        final int status;
        ProviderFailure(int status) { super("Google Drive request failed"); this.status = status; }
    }
}
