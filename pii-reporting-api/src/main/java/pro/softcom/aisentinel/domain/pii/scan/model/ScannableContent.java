package pro.softcom.aisentinel.domain.pii.scan.model;

import java.util.Map;

public interface ScannableContent {
    String getId();
    String getContentBody();
    String getTitle();
    String getSourceId();
    Map<String, Object> getMetadata();
}
