package pro.softcom.aisentinel.domain.sharepoint;

public final class SharePointApiException extends SharePointException {
    public SharePointApiException(String message, int statusCode, String sharePointMessage) {
        super(message, statusCode, sharePointMessage);
    }
}
