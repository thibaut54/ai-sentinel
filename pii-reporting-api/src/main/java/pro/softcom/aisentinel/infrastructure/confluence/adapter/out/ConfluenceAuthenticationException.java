package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

public final class ConfluenceAuthenticationException extends ConfluenceException {
    public ConfluenceAuthenticationException(String message, int statusCode) {
        super(message, statusCode, null);
    }
}
