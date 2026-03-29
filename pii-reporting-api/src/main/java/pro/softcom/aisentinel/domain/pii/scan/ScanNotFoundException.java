package pro.softcom.aisentinel.domain.pii.scan;

public class ScanNotFoundException extends RuntimeException {
    public ScanNotFoundException(String scanId) {
        super("Scan not found: " + scanId);
    }
}
