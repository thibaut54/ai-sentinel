package pro.softcom.aisentinel.application.sharepoint.service;

import java.io.InputStream;

/**
 * Application-level port for extracting text content from SharePoint files.
 * Infrastructure adapter (Tika) provides the implementation.
 */
public interface SharePointTextExtractorPort {

    /**
     * Extract text content from a file input stream.
     *
     * @param inputStream the file content stream
     * @param fileName    the file name (used for type detection)
     * @param mimeType    the MIME type (used for type detection)
     * @return extracted text content, or empty string if extraction fails
     */
    String extractText(InputStream inputStream, String fileName, String mimeType);
}
