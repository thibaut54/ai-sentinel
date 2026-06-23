package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.extraction.ExtractedContent;
import pro.softcom.aisentinel.application.pii.reporting.service.DetectionOffsetRemapper;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.CompositeAttachmentTextExtractorAdapter;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.TabularAttachmentTextExtractorAdapter;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.TikaAttachmentTextExtractorAdapter;
import pro.softcom.aisentinel.infrastructure.document.config.TextQualityThresholds;
import pro.softcom.aisentinel.infrastructure.document.validator.TextQualityValidator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * End-to-end test of tabular extraction through the real composite (tabular extractor first, Tika
 * fallback) and the offset remapping, with xlsx fixtures generated programmatically.
 */
@DisplayName("Tabular extraction - integration")
class TabularExtractionIntegrationTest {

    private static final String AVS = "756.1234.5678.97";

    private final CompositeAttachmentTextExtractorAdapter composite = new CompositeAttachmentTextExtractorAdapter(
        List.of(
            new TabularAttachmentTextExtractorAdapter(new TabularContentSerializer()),
            new TikaAttachmentTextExtractorAdapter(new TextQualityValidator(new TextQualityThresholds()))));

    private final TabularContentSerializer serializer = new TabularContentSerializer();

    @Test
    @DisplayName("Should pair each value with its header in analysis text and keep raw values in context")
    void Should_SerializeTabular_When_XlsxHasHeader() throws IOException {
        byte[] xlsx = xlsxWithHeader();
        AttachmentInfo info = new AttachmentInfo("clients.xlsx", "xlsx", "application/vnd.ms-excel", "http://x");

        ExtractedContent content = composite.extractText(info, xlsx).orElseThrow();

        assertSoftly(soft -> {
            soft.assertThat(content.analysisText()).contains("Nom : Dupont", "N° AVS : " + AVS);
            // The report-facing context must keep the raw values, without the "header : value" decoration
            soft.assertThat(content.contextText()).doesNotContain(" : ").contains("Dupont", AVS);
            soft.assertThat(content.isIdentity()).isFalse();
        });
    }

    @Test
    @DisplayName("Should keep detector offsets coherent: substring(contextText, remapped) equals the PII value")
    void Should_RemapOffsets_When_PiiDetectedOnAnalysisText() throws IOException {
        byte[] xlsx = xlsxWithHeader();
        AttachmentInfo info = new AttachmentInfo("clients.xlsx", "xlsx", "application/vnd.ms-excel", "http://x");
        ExtractedContent content = composite.extractText(info, xlsx).orElseThrow();

        // Simulate a detector flagging the AVS value at its position in the ANALYSIS text
        int analysisStart = content.analysisText().indexOf(AVS);
        SensitiveData detected = new SensitiveData(
            "SSN", "Numéro AVS", AVS, "", analysisStart, analysisStart + AVS.length(), 0.95, "sel",
            DetectorSource.GLINER2);
        ContentPiiDetection detection = ContentPiiDetection.builder()
            .sensitiveDataFound(List.of(detected))
            .build();

        ContentPiiDetection remapped = DetectionOffsetRemapper.remap(detection, content.offsetMapping());

        assertThat(remapped.sensitiveDataFound()).hasSize(1);
        SensitiveData entity = remapped.sensitiveDataFound().getFirst();
        // The ScanEventFactory invariant: substring(sourceContent, pos, end) == value
        assertThat(content.contextText().substring(entity.position(), entity.end())).isEqualTo(AVS);
    }

    @Test
    @DisplayName("RG4: Should leave a header-less xlsx to the Tika fallback (no tabular serialization)")
    void Should_FallBackToTika_When_XlsxHasNoHeader() throws IOException {
        byte[] xlsx = xlsxWithoutHeader();
        AttachmentInfo info = new AttachmentInfo("data.xlsx", "xlsx", "application/vnd.ms-excel", "http://x");

        // The tabular serializer yields nothing for a header-less table...
        assertThat(serializer.serialize("xlsx", xlsx)).isEmpty();

        // ...so the composite either falls back to Tika (identity content) or yields nothing,
        // but never produces the tabular "header : value" decoration.
        Optional<ExtractedContent> result = composite.extractText(info, xlsx);
        result.ifPresent(content -> assertSoftly(soft -> {
            soft.assertThat(content.isIdentity()).isTrue();
            soft.assertThat(content.contextText()).isEqualTo(content.analysisText());
        }));
    }

    private static byte[] xlsxWithHeader() throws IOException {
        return xlsx(new String[][] {
            {"Nom", "Code postal", "N° AVS"},
            {"Dupont", "75019", AVS},
            {"Martin", "1003", "756.9876.5432.10"}
        });
    }

    private static byte[] xlsxWithoutHeader() throws IOException {
        return xlsx(new String[][] {
            {"756.1234.5678.97", "75019"},
            {"Informations completes pour le dossier de Monsieur Dupont domicilie a Lyon", "Suite du texte"},
            {"Adresse complete et coordonnees diverses pour analyse documentaire", "Fin du document"}
        });
    }

    private static byte[] xlsx(String[][] data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Clients");
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]); // string cells -> verbatim round-trip
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
