package pro.softcom.aisentinel.domain.database;

import lombok.Builder;
import lombok.Value;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;

import java.util.Map;

@Value
@Builder
public class DatabaseScannableContent implements ScannableContent {
    String id;
    String contentBody;
    String title;
    String sourceId;
    Map<String, Object> metadata;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getContentBody() {
        return contentBody;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return metadata != null ? metadata : Map.of();
    }
}
