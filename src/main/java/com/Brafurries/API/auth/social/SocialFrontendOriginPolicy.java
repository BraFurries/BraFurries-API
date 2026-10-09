package com.Brafurries.API.auth.social;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SocialFrontendOriginPolicy {

    public static final String SESSION_ATTRIBUTE = "brafurries.oauth.frontend-origin";
    public static final String FIREBASE_PREVIEW_ORIGIN_PATTERN = "https://brafurries--*.web.app";

    private static final Pattern FIREBASE_PREVIEW_HOST =
        Pattern.compile("^brafurries--[a-z0-9-]+\\.web\\.app$");

    private final String defaultFrontendOrigin;

    public SocialFrontendOriginPolicy(@Value("${app.frontend-url}") String frontendUrl) {
        this.defaultFrontendOrigin = normalizeOrigin(frontendUrl)
            .orElseThrow(() -> new IllegalArgumentException("app.frontend-url deve ser uma origem valida"));
    }

    public String resolve(String candidate) {
        return normalizeOrigin(candidate)
            .filter(this::isAllowed)
            .orElse(defaultFrontendOrigin);
    }

    public String defaultOrigin() {
        return defaultFrontendOrigin;
    }

    private boolean isAllowed(String origin) {
        if (origin.equals(defaultFrontendOrigin)
            || origin.equals("https://brafurries.com.br")
            || origin.equals("https://www.brafurries.com.br")
            || origin.equals("http://localhost:4200")) {
            return true;
        }

        URI uri = URI.create(origin);
        return "https".equals(uri.getScheme())
            && uri.getPort() == -1
            && uri.getHost() != null
            && FIREBASE_PREVIEW_HOST.matcher(uri.getHost()).matches();
    }

    private Optional<String> normalizeOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();

            if (scheme == null
                || host == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isBlank() && !"/".equals(path))) {
                return Optional.empty();
            }

            StringBuilder origin = new StringBuilder(scheme)
                .append("://")
                .append(host);

            if (uri.getPort() != -1) {
                origin.append(':').append(uri.getPort());
            }

            return Optional.of(origin.toString());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
