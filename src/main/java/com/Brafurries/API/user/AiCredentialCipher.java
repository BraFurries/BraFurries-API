package com.Brafurries.API.user;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fernet-compatible credential encryption shared with the Coddy runtime.
 *
 * The configured value follows the same semantics as the Python runtime:
 * a URL-safe base64 key that decodes to 32 bytes is used directly; any other
 * non-blank value is deterministically SHA-256-derived into a Fernet key.
 */
@Component
public class AiCredentialCipher {
    private static final byte FERNET_VERSION = (byte) 0x80;
    private static final int SIGNING_KEY_BYTES = 16;
    private static final int ENCRYPTION_KEY_BYTES = 16;
    private static final int IV_BYTES = 16;
    private static final int HMAC_BYTES = 32;
    private static final int MIN_TOKEN_BYTES = 1 + Long.BYTES + IV_BYTES + 16 + HMAC_BYTES;

    private final String configuredKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiCredentialCipher(
        @Value("${OPENAI_TOKEN_ENCRYPTION_KEY:}") String configuredKey
    ) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    public String encrypt(String plaintext) {
        String normalized = plaintext == null ? "" : plaintext.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Credencial OpenAI obrigatória");
        }

        byte[] key = requireKey();
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(key, SIGNING_KEY_BYTES, SIGNING_KEY_BYTES + ENCRYPTION_KEY_BYTES), "AES"),
                new IvParameterSpec(iv)
            );
            byte[] ciphertext = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));

            ByteBuffer signed = ByteBuffer.allocate(1 + Long.BYTES + IV_BYTES + ciphertext.length);
            signed.put(FERNET_VERSION);
            signed.putLong(Instant.now().getEpochSecond());
            signed.put(iv);
            signed.put(ciphertext);

            byte[] signedBytes = signed.array();
            byte[] signature = hmac(Arrays.copyOfRange(key, 0, SIGNING_KEY_BYTES), signedBytes);
            ByteBuffer token = ByteBuffer.allocate(signedBytes.length + signature.length);
            token.put(signedBytes);
            token.put(signature);
            return Base64.getUrlEncoder().encodeToString(token.array());
        } catch (GeneralSecurityException exception) {
            throw unavailable("Não foi possível cifrar a credencial de IA", exception);
        }
    }

    public String decrypt(String encryptedValue) {
        String normalized = encryptedValue == null ? "" : encryptedValue.trim();
        if (normalized.isEmpty()) {
            throw unavailable("Credencial de IA não configurada", null);
        }

        byte[] key = requireKey();
        String fernet = decryptFernet(normalized, key);
        if (fernet != null) {
            return fernet;
        }

        String legacy = decryptLegacy(normalized);
        if (legacy != null) {
            return legacy;
        }

        throw unavailable("Não foi possível descriptografar a credencial de IA", null);
    }

    private String decryptFernet(String value, byte[] key) {
        try {
            byte[] token = Base64.getUrlDecoder().decode(value);
            if (token.length < MIN_TOKEN_BYTES || token[0] != FERNET_VERSION) {
                return null;
            }

            int signedLength = token.length - HMAC_BYTES;
            byte[] signed = Arrays.copyOfRange(token, 0, signedLength);
            byte[] actualSignature = Arrays.copyOfRange(token, signedLength, token.length);
            byte[] expectedSignature = hmac(
                Arrays.copyOfRange(key, 0, SIGNING_KEY_BYTES),
                signed
            );
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                return null;
            }

            byte[] iv = Arrays.copyOfRange(token, 1 + Long.BYTES, 1 + Long.BYTES + IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(
                token,
                1 + Long.BYTES + IV_BYTES,
                signedLength
            );

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(key, SIGNING_KEY_BYTES, SIGNING_KEY_BYTES + ENCRYPTION_KEY_BYTES), "AES"),
                new IvParameterSpec(iv)
            );
            String plaintext = decodeUtf8(cipher.doFinal(ciphertext));
            return plaintext == null || plaintext.isBlank() ? null : plaintext.trim();
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return null;
        }
    }

    private String decryptLegacy(String value) {
        try {
            byte[] encrypted = Base64.getUrlDecoder().decode(value);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
            byte[] decrypted = new byte[encrypted.length];
            for (int index = 0; index < encrypted.length; index++) {
                decrypted[index] = (byte) (encrypted[index] ^ digest[index % digest.length]);
            }
            String plaintext = decodeUtf8(decrypted);
            return plaintext == null || plaintext.isBlank() ? null : plaintext.trim();
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return null;
        }
    }

    private byte[] requireKey() {
        if (configuredKey.isBlank()) {
            throw unavailable("Chave de criptografia da IA não configurada", null);
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(configuredKey);
            if (decoded.length == SIGNING_KEY_BYTES + ENCRYPTION_KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Match the Coddy runtime: derive arbitrary configured secrets below.
        }

        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw unavailable("Não foi possível inicializar a cifra da IA", exception);
        }
    }

    private byte[] hmac(byte[] signingKey, byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private ResponseStatusException unavailable(String message, Exception cause) {
        return cause == null
            ? new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message)
            : new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
