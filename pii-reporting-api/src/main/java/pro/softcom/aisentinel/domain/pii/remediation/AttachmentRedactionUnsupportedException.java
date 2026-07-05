package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Thrown when an automatic redaction is requested on an attachment: binary rewriting
 * is not supported, only manual handling or false-positive reporting apply.
 */
public class AttachmentRedactionUnsupportedException extends RuntimeException {

    public AttachmentRedactionUnsupportedException(String message) {
        super(message);
    }
}
