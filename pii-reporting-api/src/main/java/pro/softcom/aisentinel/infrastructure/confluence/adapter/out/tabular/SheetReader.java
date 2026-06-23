package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.io.IOException;
import java.util.List;

/**
 * Strategy that turns the bytes of a tabular file into reader-agnostic {@link SheetData}.
 *
 * <p>Implementations are technology-specific (xlsx via POI streaming SAX, xls via HSSF, csv via
 * Commons CSV, ods via SAX over the zip). They only parse cells verbatim (RG7); header detection and
 * serialization happen downstream. A reader must not load an entire huge workbook into memory beyond
 * a reasonable safety bound and must read cell values as raw strings.
 */
public interface SheetReader {

    /**
     * Reads the workbook bytes into its sheets.
     *
     * @param bytes the raw file content
     * @return the sheets in document order (possibly empty)
     * @throws IOException when the bytes cannot be parsed (corrupted file); callers fall back to Tika
     */
    List<SheetData> read(byte[] bytes) throws IOException;
}
