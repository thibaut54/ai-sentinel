package pro.softcom.aisentinel.application.pii.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.security.EncryptionException;
import pro.softcom.aisentinel.domain.pii.security.EncryptionMetadata;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;

import java.util.List;

/**
 * Service responsible for encrypting and decrypting PII data in scan results.
 * Business purpose: Ensure sensitive PII data is encrypted at rest (in database)
 * and only decrypted for authorized access.
 *
 * <p>Uses AES-GCM encryption with metadata-bound AAD (Additional Authenticated Data)
 * to prevent cut-and-paste attacks by binding ciphertext to its context (PII type and position).
 */
@RequiredArgsConstructor
@Slf4j
public class ScanResultEncryptor {

    private final EncryptionService encryptionService;
    private static final int MAX_ENTITIES_SOFT_CAP = 1000;

    /**
     * Encrypts PII fields in the scan result.
     * Business purpose: Protect sensitive data before storage.
     *
     * @param result the scan result with clear-text PII values
     * @return scan result with encrypted PII values
     */
    public ContentScanResult encrypt(ContentScanResult result) {
        var entities = result.detectedPIIList();
        if (entities == null || entities.isEmpty()) {
            return result;
        }

        var encryptedEntities = encryptEntities(entities);

        return result.toBuilder()
            .detectedPIIList(encryptedEntities)
            .build();
    }

    /**
     * Decrypts encrypted PII entities in the scan result.
     * Business purpose: Restore original values for authorized access and display.
     *
     * @param result the scan result with encrypted PII values
     * @return scan result with decrypted PII values
     * @throws EncryptionException if decryption fails for any entity
     */
    public ContentScanResult decrypt(ContentScanResult result) {
        var entities = result.detectedPIIList();
        if (entities == null || entities.isEmpty()) {
            return result;
        }

        try {
            var decryptedEntities = entities.stream()
                    .map(this::decryptEntity)
                    .toList();

            return result.toBuilder()
                    .detectedPIIList(decryptedEntities)
                    .build();
        } catch (EncryptionException e) {
            log.error("Failed to decrypt PII entities for scanId={}, entityCount={}",
                      result.scanId(), entities.size(), e);
            throw e;
        }
    }

    /**
     * Encrypts a batch of PII entities.
     * Logs a warning if batch size exceeds soft cap for performance monitoring.
     */
    private List<DetectedPersonallyIdentifiableInformation> encryptEntities(List<DetectedPersonallyIdentifiableInformation> entities) {
        if (entities.size() > MAX_ENTITIES_SOFT_CAP) {
            log.warn("Encrypting {} entities (exceeds soft cap of {}). Consider reviewing batch size or enabling parallelization.",
                    entities.size(), MAX_ENTITIES_SOFT_CAP);
        }

        return entities.stream()
                .map(this::encryptEntity)
                .toList();
    }

    /**
     * Encrypts a single PII entity while preserving its metadata.
     * The metadata is used as Additional Authenticated Data (AAD) to ensure integrity.
     * Note: maskedContext is not encrypted as it contains only masked tokens, not real PII values.
     */
    private DetectedPersonallyIdentifiableInformation encryptEntity(
        DetectedPersonallyIdentifiableInformation entity) {
        EncryptionMetadata metadata = buildMetadata(entity);

        var encryptedText = encryptionService.encrypt(entity.sensitiveValue(), metadata);
        var encryptedContext = encryptionService.encrypt(entity.sensitiveContext(), metadata);

        return entity.toBuilder()
                .sensitiveValue(encryptedText)
                .sensitiveContext(encryptedContext)
                .maskedContext(entity.maskedContext()) // Keep masked sensitiveContext in clear text
                .build();
    }

    /**
     * Decrypts a single PII entity if it's encrypted, otherwise returns it unchanged.
     * The metadata is verified during decryption to ensure integrity.
     * Note: maskedContext is never encrypted, so it's preserved as-is.
     */
    private DetectedPersonallyIdentifiableInformation decryptEntity(
        DetectedPersonallyIdentifiableInformation entity) {
        EncryptionMetadata metadata = buildMetadata(entity);

        var decryptedText = entity.sensitiveValue();
        if (encryptionService.isEncrypted(entity.sensitiveValue())) {
            decryptedText = encryptionService.decrypt(entity.sensitiveValue(), metadata);
        }

        var decryptedContext = entity.sensitiveContext();
        if (encryptionService.isEncrypted(entity.sensitiveContext())) {
            decryptedContext = encryptionService.decrypt(entity.sensitiveContext(), metadata);
        }

        return entity.toBuilder()
                .sensitiveValue(decryptedText)
                .sensitiveContext(decryptedContext)
                .maskedContext(entity.maskedContext()) // Preserve masked sensitiveContext unchanged
                .build();
    }

    /**
     * Builds encryption metadata from PII entity for Additional Authenticated Data.
     * This metadata is cryptographically bound to ensure data integrity.
     */
    private EncryptionMetadata buildMetadata(DetectedPersonallyIdentifiableInformation entity) {
        return new EncryptionMetadata(entity.piiType(), entity.startPosition(), entity.endPosition());
    }
}
