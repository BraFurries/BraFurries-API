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
        params.put("fields","nextPageToken,incompleteSearch,files(id,name,mimeType,parents,trashed,ownedByMe,size,modifiedTime)");
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
