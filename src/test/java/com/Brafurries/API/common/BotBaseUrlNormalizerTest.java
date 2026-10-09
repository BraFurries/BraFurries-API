package com.Brafurries.API.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BotBaseUrlNormalizerTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void usesDefaultForMissingConfiguration(String configuredBaseUrl) {
        assertEquals("http://127.0.0.1:18088", BotBaseUrlNormalizer.normalize(configuredBaseUrl));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "coddy:18088",
        "http://coddy:18088",
        "http://coddy:18088/",
        "http://coddy:18088///",
        "  http://coddy:18088/  "
    })
    void normalizesSupportedBotBaseUrls(String configuredBaseUrl) {
        assertEquals("http://coddy:18088", BotBaseUrlNormalizer.normalize(configuredBaseUrl));
    }

    @ParameterizedTest
    @CsvSource({
        "HTTP://coddy:18088, HTTP://coddy:18088",
        "HTTPS://coddy:18088, HTTPS://coddy:18088",
        "HtTp://coddy:18088, HtTp://coddy:18088",
        "hTTps://coddy:18088/, hTTps://coddy:18088"
    })
    void recognizesHttpSchemesIgnoringCase(String configuredBaseUrl, String expectedBaseUrl) {
        assertEquals(expectedBaseUrl, BotBaseUrlNormalizer.normalize(configuredBaseUrl));
    }
}
