package com.Brafurries.API.platform.drive;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned AES-256-GCM envelope. Purpose-bound AAD prevents cross-use. */
final class DriveTokenCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    DriveTokenCipher(String encodedKey) {
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) throw new IllegalArgumentException("Invalid Drive encryption key");
        key = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
    }

    byte[] encrypt(String value, String purpose) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[13 + encrypted.length];
            result[0] = 1;
            System.arraycopy(nonce, 0, result, 1, nonce.length);
            System.arraycopy(encrypted, 0, result, 13, encrypted.length);
            return result;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Drive encryption failed");
        }
    }

    String decrypt(byte[] envelope, String purpose) {
        if (envelope == null || envelope.length < 29 || envelope[0] != 1)
            throw new IllegalStateException("Invalid Drive encrypted credential");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(envelope, 13, envelope.length - 13), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Invalid Drive encrypted credential");
        }
    }
}
