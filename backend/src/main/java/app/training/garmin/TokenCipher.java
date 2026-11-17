package app.training.garmin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for the Garmin token bundle (ADR 0011 D2). A fresh random 12-byte nonce per
 * encryption, a 128-bit tag, and the athlete id bound in as additional data so a ciphertext
 * copied to another athlete's row does not decrypt. Stored value: nonce || ciphertext || tag.
 */
public final class TokenCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Wrong key, another athlete's row, or a damaged value. Carries no detail on purpose. */
    public static final class DecryptFailed extends Exception {
        private static final long serialVersionUID = 1L;

        DecryptFailed() {
            super("Garmin token could not be decrypted");
        }
    }

    private final SecretKeySpec key;
    private final String keyId;

    public TokenCipher(byte[] key) {
        if (key == null || key.length != GarminConfigCheck.KEY_BYTES) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
        this.keyId = keyId(key);
    }

    /** First 8 hex characters of SHA-256 of the key: tells "different key" from "damaged row" (D2, D7). */
    public static String keyId(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String keyId() {
        return keyId;
    }

    static byte[] aad(long athleteId) {
        return ("garmin-token:v1:athlete:" + athleteId).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] encrypt(long athleteId, byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(athleteId));
            byte[] sealed = c.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    public byte[] decrypt(long athleteId, byte[] stored) throws DecryptFailed {
        if (stored == null || stored.length < NONCE_BYTES + TAG_BITS / 8) throw new DecryptFailed();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            c.updateAAD(aad(athleteId));
            return c.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new DecryptFailed();
        }
    }

    @Override
    public String toString() {
        return "TokenCipher[keyId=" + keyId + "]";
    }
}
