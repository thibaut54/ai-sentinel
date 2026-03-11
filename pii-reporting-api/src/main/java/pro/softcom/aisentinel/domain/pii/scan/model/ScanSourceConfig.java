package pro.softcom.aisentinel.domain.pii.scan.model;

import java.util.Map;

public record ScanSourceConfig(
    DatabaseSourceType type,
    Map<String, String> properties
) {
    public ScanSourceConfig {
        if (type == null) {
            throw new IllegalArgumentException("SourceType cannot be null");
        }
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }
}
