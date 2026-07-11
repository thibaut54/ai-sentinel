package pro.softcom.aisentinel.domain.pii.detection;

/**
 * Lifecycle of an open-vocabulary label discovered by the MINISTRAL detector.
 * <p>
 * A label starts {@code PENDING} when first seen without a matching
 * {@code pii_type_config} row. An operator either {@code PROMOTED}s it (creating
 * the config, after which the detector reports it as a real finding) or
 * {@code IGNORED}s it to keep noise out of the discovery inbox.
 */
public enum DiscoveredLabelStatus {
    PENDING,
    PROMOTED,
    IGNORED
}
