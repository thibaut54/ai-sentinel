package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import lombok.Getter;

@Getter
public sealed class ConfluenceException extends RuntimeException permits ConfluenceApiException,
    ConfluenceAuthenticationException, ConfluenceConnectionException, ConfluenceNotFoundException,
    ConfluenceDateParseException {
    private final int statusCode;
    private final String confluenceMessage;

    public ConfluenceException(String message, int statusCode, String confluenceMessage) {
        super(message);
        this.statusCode = statusCode;
        this.confluenceMessage = confluenceMessage;
    }

    public ConfluenceException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.confluenceMessage = null;
    }
}
