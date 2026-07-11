package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.config.EncryptionConfig;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.config.EncryptionKeyProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HmacValueFingerprintAdapter")
class HmacValueFingerprintAdapterTest {

    private static final byte[] KEY_A = keyFilledWith((byte) 0x11);
    private static final byte[] KEY_B = keyFilledWith((byte) 0x22);

    private final HmacValueFingerprintAdapter adapter = adapterWithKey(KEY_A);

    private static byte[] keyFilledWith(byte filler) {
        byte[] key = new byte[32];
        Arrays.fill(key, filler);
        return key;
    }

    private static HmacValueFingerprintAdapter adapterWithKey(byte[] key) {
        EncryptionConfig config = new EncryptionConfig(Base64.getEncoder().encodeToString(key));
        return new HmacValueFingerprintAdapter(new EncryptionKeyProvider(config));
    }

    @Test
    @DisplayName("Should_ReturnSameFingerprint_When_ValueDiffersOnlyByTrimAndWhitespaceRuns")
    void Should_ReturnSameFingerprint_When_ValueDiffersOnlyByTrimAndWhitespaceRuns() {
        assertThat(adapter.fingerprint("  John\t  Doe \n"))
                .isEqualTo(adapter.fingerprint("John Doe"));
    }

    @Test
    @DisplayName("Should_ReturnSameFingerprint_When_ValueDiffersOnlyByUnicodeNormalizationForm")
    void Should_ReturnSameFingerprint_When_ValueDiffersOnlyByUnicodeNormalizationForm() {
        // "José" precomposed (NFC) vs "José" decomposed (NFD)
        assertThat(adapter.fingerprint("José"))
                .isEqualTo(adapter.fingerprint("José"));
    }

    @Test
    @DisplayName("Should_ReturnDifferentFingerprints_When_ValuesDiffer")
    void Should_ReturnDifferentFingerprints_When_ValuesDiffer() {
        assertThat(adapter.fingerprint("john.doe@example.com"))
                .isNotEqualTo(adapter.fingerprint("jane.doe@example.com"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("Should_ReturnNull_When_ValueIsNullOrBlank")
    void Should_ReturnNull_When_ValueIsNullOrBlank(String value) {
        assertThat(adapter.fingerprint(value)).isNull();
    }

    @Test
    @DisplayName("Should_ReturnCompactLowercaseHex_When_ValueIsFingerprinted")
    void Should_ReturnCompactLowercaseHex_When_ValueIsFingerprinted() {
        assertThat(adapter.fingerprint("john.doe@example.com")).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("Should_ReturnSameFingerprint_When_ComputedByTwoInstancesWithSameKey")
    void Should_ReturnSameFingerprint_When_ComputedByTwoInstancesWithSameKey() {
        assertThat(adapterWithKey(KEY_A).fingerprint("John Doe"))
                .isEqualTo(adapterWithKey(KEY_A).fingerprint("John Doe"));
    }

    @Test
    @DisplayName("Should_ReturnDifferentFingerprints_When_KeysDiffer")
    void Should_ReturnDifferentFingerprints_When_KeysDiffer() {
        assertThat(adapterWithKey(KEY_A).fingerprint("John Doe"))
                .isNotEqualTo(adapterWithKey(KEY_B).fingerprint("John Doe"));
    }

    @Test
    @DisplayName("Should_NotUseRawKekAsMacKey_When_Computing")
    void Should_NotUseRawKekAsMacKey_When_Computing() throws Exception {
        // HMAC with the raw KEK: the adapter must use a derived key instead
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY_A, "HmacSHA256"));
        String rawKekHmac = HexFormat.of()
                .formatHex(mac.doFinal("John Doe".getBytes(StandardCharsets.UTF_8)));

        assertThat(adapter.fingerprint("John Doe")).isNotEqualTo(rawKekHmac);
    }
}
