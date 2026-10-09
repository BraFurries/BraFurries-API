package com.Brafurries.API.common;

public final class BotBaseUrlNormalizer {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:18088";

    private BotBaseUrlNormalizer() {
    }

    public static String normalize(String configuredBaseUrl) {
        String value = configuredBaseUrl == null || configuredBaseUrl.isBlank()
            ? DEFAULT_BASE_URL
            : configuredBaseUrl.trim();
        if (!hasHttpScheme(value)) {
            value = "http://" + value;
        }
        return value.replaceAll("/+$", "");
    }

    private static boolean hasHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, "http://".length())
            || value.regionMatches(true, 0, "https://", 0, "https://".length());
    }
}
