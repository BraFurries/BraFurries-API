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
