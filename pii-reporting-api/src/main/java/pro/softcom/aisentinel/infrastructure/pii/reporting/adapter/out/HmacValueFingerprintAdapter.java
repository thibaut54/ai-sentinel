package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ValueFingerprintCalculator;
import pro.softcom.aisentinel.domain.pii.security.CryptographicOperationException;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.config.EncryptionKeyProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Computes finding fingerprints as HMAC-SHA256 over the normalized detected value.
 *
 * <p>Normalization contract: Unicode NFC, trim, and whitespace-run collapse, so the
 * same value extracted with different spacing or encoding forms yields one fingerprint.</p>
 *
 * <p>The MAC key is derived from the PII encryption KEK through HKDF-SHA256 (RFC 5869)
 * with a dedicated context label, so the raw KEK is never used directly as a MAC key
 * and fingerprints live in a key domain separate from encryption.</p>
 */
@Component
public class HmacValueFingerprintAdapter implements ValueFingerprintCalculator {

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte[] HKDF_INFO_FINGERPRINT =
            "pii-finding-fingerprint".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_EXTRACT_SALT = new byte[32];

    private final SecretKeySpec fingerprintKey;

    public HmacValueFingerprintAdapter(EncryptionKeyProvider keyProvider) {
        this.fingerprintKey = deriveFingerprintKey(keyProvider.getKey().getEncoded());
    }

    @Override
    public String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(fingerprintKey);
            byte[] digest = mac.doFinal(normalize(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new CryptographicOperationException("Value fingerprint computation failed", e);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
    }

    private static SecretKeySpec deriveFingerprintKey(byte[] kek) {
        if (kek == null) {
            throw new IllegalStateException("KEK is not extractable");
        }
        byte[] prk = null;
        try {
            // HKDF extract then single-block expand, mirroring AesGcmEncryptionAdapter
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(HKDF_EXTRACT_SALT, MAC_ALGORITHM));
            prk = mac.doFinal(kek);

            mac.init(new SecretKeySpec(prk, MAC_ALGORITHM));
            mac.update(HKDF_INFO_FINGERPRINT);
            mac.update((byte) 0x01);
            return new SecretKeySpec(mac.doFinal(), MAC_ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new CryptographicOperationException("Fingerprint key derivation failed", e);
        } finally {
            wipe(kek, prk);
        }
    }

    private static void wipe(byte[]... buffers) {
        for (byte[] buffer : buffers) {
            if (buffer != null) {
                Arrays.fill(buffer, (byte) 0);
            }
        }
    }
}
