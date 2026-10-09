package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiCredentialCipherTest {
    private static final String RAW_KEY = "brafurries-test-shared-key";

    @Test
    void decryptsPythonFernetCiphertextWithSharedKeySemantics() {
        AiCredentialCipher cipher = new AiCredentialCipher(RAW_KEY);

        String plaintext = cipher.decrypt(
            "gAAAAABqxQCJ4LD_F7tiC6nlwqyNfcYB8avceZ70lLJo8QSahvpRcmABkoNsEaB7901LTuVenD85axXiyQD2Esf6V8Rjp4Tpfg=="
        );

        assertEquals("sk-test-secret", plaintext);
    }

    @Test
    void encryptedCredentialRoundTripsThroughFernet() {
        AiCredentialCipher cipher = new AiCredentialCipher(RAW_KEY);

        String encrypted = cipher.encrypt("sk-round-trip");

        assertNotEquals("sk-round-trip", encrypted);
        assertEquals("sk-round-trip", cipher.decrypt(encrypted));
    }

    @Test
    void decryptsLegacyXorCiphertextDuringTransition() {
        AiCredentialCipher cipher = new AiCredentialCipher(RAW_KEY);

        assertEquals(
            "sk-legacy-secret",
            cipher.decrypt("9fubYAWGacJ-r4V-SFXFWQ==")
        );
    }
}
