package pro.softcom.aisentinel.domain.pii.security;

public class PiiAccessDeniedException extends RuntimeException {
    public PiiAccessDeniedException(String message) {
        super(message);
    }
}
