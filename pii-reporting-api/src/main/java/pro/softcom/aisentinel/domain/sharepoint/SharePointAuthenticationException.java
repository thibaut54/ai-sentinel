package pro.softcom.aisentinel.domain.sharepoint;

public final class SharePointAuthenticationException extends SharePointException {
    public SharePointAuthenticationException(String message, int statusCode) {
        super(message, statusCode, null);
    }
}
