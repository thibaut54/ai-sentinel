package pro.softcom.aisentinel.domain.sharepoint;

import lombok.Getter;

@Getter
public sealed class SharePointException extends RuntimeException permits SharePointApiException,
    SharePointAuthenticationException, SharePointConnectionException, SharePointNotFoundException {
    private final int statusCode;
    private final String sharePointMessage;

    public SharePointException(String message, int statusCode, String sharePointMessage) {
        super(message);
        this.statusCode = statusCode;
        this.sharePointMessage = sharePointMessage;
    }

    public SharePointException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.sharePointMessage = null;
    }
}
