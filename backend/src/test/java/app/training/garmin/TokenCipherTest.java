package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/** ADR 0011 D2: AES-256-GCM, random nonce, athlete id bound in, tampering and other keys rejected. */
class TokenCipherTest {
    private static final byte[] TOKENS = "{\"di_token\":\"fake-access\",\"di_refresh_token\":\"fake-refresh\"}"
            .getBytes(StandardCharsets.UTF_8);

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsAndHidesThePlaintext() throws Exception {
        var c = new TokenCipher(randomKey());
        byte[] sealed = c.encrypt(1, TOKENS);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("fake-refresh");
        assertThat(sealed).hasSize(12 + TOKENS.length + 16);
        assertThat(c.decrypt(1, sealed)).isEqualTo(TOKENS);
    }

    @Test
    void freshNonceEveryTime() {
        var c = new TokenCipher(randomKey());
        assertThat(c.encrypt(1, TOKENS)).isNotEqualTo(c.encrypt(1, TOKENS));
    }

    @Test
    void anotherAthletesRowDoesNotDecrypt() {
        var c = new TokenCipher(randomKey());
        byte[] sealed = c.encrypt(1, TOKENS);
        assertThatThrownBy(() -> c.decrypt(2, sealed)).isInstanceOf(TokenCipher.DecryptFailed.class);
    }

    @Test
    void anotherKeyOrTamperingIsRejected() {
        var c = new TokenCipher(randomKey());
        byte[] sealed = c.encrypt(1, TOKENS);
        assertThatThrownBy(() -> new TokenCipher(randomKey()).decrypt(1, sealed)).isInstanceOf(TokenCipher.DecryptFailed.class);
        sealed[20] ^= 1;
        assertThatThrownBy(() -> c.decrypt(1, sealed)).isInstanceOf(TokenCipher.DecryptFailed.class);
        assertThatThrownBy(() -> c.decrypt(1, new byte[5])).isInstanceOf(TokenCipher.DecryptFailed.class);
        assertThatThrownBy(() -> c.decrypt(1, null)).isInstanceOf(TokenCipher.DecryptFailed.class);
    }

    @Test
    void keyIdIsStableShortAndDiffersBetweenKeys() {
        byte[] k = randomKey();
        assertThat(new TokenCipher(k).keyId()).matches("[0-9a-f]{8}").isEqualTo(TokenCipher.keyId(k));
        assertThat(TokenCipher.keyId(randomKey())).isNotEqualTo(TokenCipher.keyId(k));
        assertThat(new TokenCipher(k).toString()).doesNotContain(java.util.Base64.getEncoder().encodeToString(k));
    }

    @Test
    void refusesWrongKeyLength() {
        assertThatThrownBy(() -> new TokenCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
