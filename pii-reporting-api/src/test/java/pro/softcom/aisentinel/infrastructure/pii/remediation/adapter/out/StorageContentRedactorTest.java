package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.softcom.aisentinel.domain.pii.remediation.RedactionToken;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.StorageContentRedactor.RedactionGuardConfig;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.StorageContentRedactor.RedactionResult;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.StorageContentRedactor.ValueReplacement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("StorageContentRedactor")
class StorageContentRedactorTest {

    private final StorageContentRedactor redactor = new StorageContentRedactor();

    @Nested
    @DisplayName("Corpus coverage - every spike category re-anchors and redacts")
    class CorpusCoverage {

        static Stream<Arguments> corpusCases() {
            return Stream.of(
                Arguments.of("01-plain-paragraphs", "jean.dupont@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("01-plain-paragraphs", "Jean Dupont", "PERSON_NAME", 1),
                Arguments.of("02-plain-phone-avs", "021 316 40 00", "PHONE_NUMBER", 1),
                Arguments.of("02-plain-phone-avs", "756.1234.5678.97", "AVS_NUMBER", 1),
                Arguments.of("03-plain-iban", "CH93 0076 2011 6238 5295 7", "IBAN", 1),
                Arguments.of("04-inline-strong-name", "Jean Dupont", "PERSON_NAME", 1),
                Arguments.of("05-inline-em-email", "jean.dupont@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("06-inline-span-phone", "079 555 12 34", "PHONE_NUMBER", 1),
                Arguments.of("07-inline-nested", "Marie-Claire Rochat", "PERSON_NAME", 1),
                Arguments.of("08-inline-link-boundary", "Pierre Martin", "PERSON_NAME", 1),
                Arguments.of("09-table-simple", "alice.bernard@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("10-table-iban-styled", "CH93 0076 2011 6238 5295 7", "IBAN", 1),
                Arguments.of("10-table-iban-styled", "Jean Dupont", "PERSON_NAME", 1),
                Arguments.of("14-entity-amp", "Etude Dupont & Associes SA", "ORGANIZATION", 1),
                Arguments.of("14-entity-amp", "contact@dupont-associes.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("15-entity-apostrophe", "Liam O'Connor", "PERSON_NAME", 1),
                Arguments.of("16-entity-accents", "José García", "PERSON_NAME", 1),
                Arguments.of("17-nbsp-phone", "021 316 40 00", "PHONE_NUMBER", 1),
                Arguments.of("18-nbsp-mixed-phone", "+41 79 555 12 34", "PHONE_NUMBER", 1),
                Arguments.of("19-narrow-nbsp-phone", "021 316 40 01", "PHONE_NUMBER", 1),
                Arguments.of("20-unordered-list", "Claude Perrin", "PERSON_NAME", 1),
                Arguments.of("20-unordered-list", "claude.perrin@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("21-ordered-list-inline", "Nina Keller", "PERSON_NAME", 1),
                Arguments.of("21-ordered-list-inline", "756.9876.5432.10", "AVS_NUMBER", 1),
                Arguments.of("22-macro-info-richtext", "Sophie Blanc", "PERSON_NAME", 1),
                Arguments.of("22-macro-info-richtext", "021 555 66 77", "PHONE_NUMBER", 1),
                Arguments.of("23-macro-panel-richtext", "CH56 0483 5012 3456 7800 9", "IBAN", 1),
                Arguments.of("27-punctuation-adjacent", "marc.weber@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("27-punctuation-adjacent", "Anne Morel", "PERSON_NAME", 1),
                Arguments.of("28-heading", "Paul Girard", "PERSON_NAME", 1),
                Arguments.of("29-layout-sections", "David Rossi", "PERSON_NAME", 1),
                Arguments.of("29-layout-sections", "756.1111.2222.33", "AVS_NUMBER", 1),
                Arguments.of("30-task-list", "Eva Steiner", "PERSON_NAME", 1),
                Arguments.of("30-task-list", "079 222 33 44", "PHONE_NUMBER", 1),
                Arguments.of("31-br-lines", "Hugo Meier", "PERSON_NAME", 1),
                Arguments.of("32-entity-plus-inline-split", "François Lévêque", "PERSON_NAME", 1),
                Arguments.of("36-emoticon-adjacent", "Lena Vogt", "PERSON_NAME", 1),
                Arguments.of("37-blockquote-pre", "theo.nguyen@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("37-blockquote-pre", "simon.roth@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("38-whitespace-noise", "Ines Costa", "PERSON_NAME", 1),
                Arguments.of("39-comment-split-value", "Jean Dupont", "PERSON_NAME", 1),
                Arguments.of("41-deep-nesting", "Yann Berger", "PERSON_NAME", 1),
                Arguments.of("42-inline-code", "diego.silva@example.ch", "EMAIL_ADDRESS", 1),
                Arguments.of("45-status-macro-and-mention", "Omar Haddad", "PERSON_NAME", 1));
        }

        @ParameterizedTest(name = "{0}: {1}")
        @MethodSource("corpusCases")
        @DisplayName("Should_ReplaceValueWithToken_When_ValuePresentInStorage")
        void Should_ReplaceValueWithToken_When_ValuePresentInStorage(
            String fixtureName, String value, String piiType, int expectedOccurrences) {
            // Given
            String storage = fixture(fixtureName);
            String token = RedactionToken.forType(piiType);

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement(value, token)), RedactionGuardConfig.defaults());

            // Then
            String visibleTextAfter = extractedText(result.redactedXhtml());
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes()).hasSize(1);
                softly.assertThat(result.outcomes().getFirst().found()).isTrue();
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(expectedOccurrences);
                softly.assertThat(visibleTextAfter).contains(token);
                softly.assertThat(containsWordBounded(visibleTextAfter, value))
                    .as("value must be gone from the extracted text (word-bounded check)")
                    .isFalse();
                softly.assertThat(elementCount(result.redactedXhtml()))
                    .as("element structure must be preserved")
                    .isEqualTo(elementCount(storage));
            });
        }
    }

    @Nested
    @DisplayName("Cross-node re-projection")
    class CrossNodeReProjection {

        @Test
        @DisplayName("Should_PutTokenInFirstCellAndClearSecond_When_ValueSpansTwoTableCells")
        void Should_PutTokenInFirstCellAndClearSecond_When_ValueSpansTwoTableCells() {
            // Given
            String storage = fixture("11-table-value-across-cells");
            String token = RedactionToken.forType("PHONE_NUMBER");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("021 316 40 00", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("<td>" + token + "</td>");
                softly.assertThat(result.redactedXhtml()).doesNotContain("021 316").doesNotContain("40 00");
                softly.assertThat(elementCount(result.redactedXhtml())).isEqualTo(elementCount(storage));
            });
        }
    }

    @Nested
    @DisplayName("CDATA handling")
    class CdataHandling {

        @Test
        @DisplayName("Should_RedactInsideCdataPreservingEnvelope_When_ValueInPlainTextLinkBody")
        void Should_RedactInsideCdataPreservingEnvelope_When_ValueInPlainTextLinkBody() {
            // Given
            String storage = fixture("13-ac-link-cdata-body");
            String token = RedactionToken.forType("PERSON_NAME");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("Julie Moret", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("<![CDATA[");
                softly.assertThat(result.redactedXhtml()).doesNotContain("Julie Moret");
                softly.assertThat(extractedText(result.redactedXhtml())).contains(token);
            });
        }

        @Test
        @DisplayName("Should_RedactOnlyMatchedLine_When_ValueInCodeMacroCdata")
        void Should_RedactOnlyMatchedLine_When_ValueInCodeMacroCdata() {
            // Given
            String storage = fixture("25-code-macro-cdata");
            String token = RedactionToken.forType("EMAIL_ADDRESS");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("fatima.zahra@example.ch", token)),
                RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("<![CDATA[");
                softly.assertThat(result.redactedXhtml()).contains("ldap.bind.user=cn=admin");
                softly.assertThat(result.redactedXhtml()).contains("ldaps://ldap.example.ch");
                softly.assertThat(result.redactedXhtml()).doesNotContain("fatima.zahra@example.ch");
                softly.assertThat(extractedText(result.redactedXhtml())).contains(token);
            });
        }
    }

    @Nested
    @DisplayName("Guard rails")
    class GuardRails {

        @Test
        @DisplayName("Should_ReportNotFound_When_ValueShorterThanMinLength")
        void Should_ReportNotFound_When_ValueShorterThanMinLength() {
            // Given
            String storage = fixture("34-short-value-guard");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("JD", "[INITIALS]")), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().found()).isFalse();
                softly.assertThat(result.redactedXhtml()).isEqualTo(storage);
            });
        }

        @Test
        @DisplayName("Should_RedactShortValue_When_MinLengthLowered")
        void Should_RedactShortValue_When_MinLengthLowered() {
            // Given
            String storage = fixture("34-short-value-guard");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("JD", "[INITIALS]")), new RedactionGuardConfig(2, true));

            // Then
            assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should_NotRedactSubstringOfLargerValue_When_WordBoundariesEnabled")
        void Should_NotRedactSubstringOfLargerValue_When_WordBoundariesEnabled() {
            // Given
            String storage = fixture("35-substring-guard");
            String token = RedactionToken.forType("EMAIL_ADDRESS");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("anna@example.ch", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("marianna@example.ch");
            });
        }

        @Test
        @DisplayName("Should_RedactEmbeddedOccurrences_When_WordBoundariesDisabled")
        void Should_RedactEmbeddedOccurrences_When_WordBoundariesDisabled() {
            // Given
            String storage = fixture("35-substring-guard");
            String token = RedactionToken.forType("EMAIL_ADDRESS");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("anna@example.ch", token)), new RedactionGuardConfig(4, false));

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(2);
                softly.assertThat(result.redactedXhtml()).doesNotContain("anna@example.ch");
            });
        }

        @Test
        @DisplayName("Should_IgnoreDigitAdjacentOccurrence_When_ValueEmbeddedInLongerNumber")
        void Should_IgnoreDigitAdjacentOccurrence_When_ValueEmbeddedInLongerNumber() {
            // Given
            String storage = fixture("43-numeric-boundary-trap");
            String token = RedactionToken.forType("AVS_NUMBER");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("756.1234.5678.97", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("1756.1234.5678.971");
                softly.assertThat(extractedText(result.redactedXhtml())).contains(token);
            });
        }
    }

    @Nested
    @DisplayName("Metadata exclusion and residual carriers")
    class MetadataExclusionAndResiduals {

        @Test
        @DisplayName("Should_ReportNotFound_When_ValueOnlyInMacroParameter")
        void Should_ReportNotFound_When_ValueOnlyInMacroParameter() {
            // Given
            String storage = fixture("24-macro-parameter-value");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("karim.benali@example.ch", "[EMAIL_ADDRESS]")),
                RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().found()).isFalse();
                softly.assertThat(result.redactedXhtml()).isEqualTo(storage);
            });
        }

        @Test
        @DisplayName("Should_ReportNotFound_When_ValueSpansTwoBlocks")
        void Should_ReportNotFound_When_ValueSpansTwoBlocks() {
            // Given
            String storage = fixture("33-cross-block-name");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("Jean Dupont", "[PERSON_NAME]")),
                RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().found()).isFalse();
                softly.assertThat(result.redactedXhtml()).isEqualTo(storage);
            });
        }

        @Test
        @DisplayName("Should_RedactParameterDuplicate_When_ValueAlsoFoundInBody")
        void Should_RedactParameterDuplicate_When_ValueAlsoFoundInBody() {
            // Given
            String storage = fixture("40-macro-param-and-body-dup");
            String token = RedactionToken.forType("PERSON_NAME");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("Sophie Blanc", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).doesNotContain("Sophie Blanc");
                softly.assertThat(result.redactedXhtml())
                    .contains("<ac:parameter ac:name=\"title\">" + token + "</ac:parameter>");
            });
        }

        @Test
        @DisplayName("Should_RedactMailtoHref_When_LinkBodyMatches")
        void Should_RedactMailtoHref_When_LinkBodyMatches() {
            // Given
            String storage = fixture("12-link-mailto");
            String token = RedactionToken.forType("EMAIL_ADDRESS");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("support.rh@example.ch", token)),
                RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("href=\"mailto:" + token + "\"");
                softly.assertThat(result.redactedXhtml()).doesNotContain("support.rh@example.ch");
            });
        }
    }

    @Nested
    @DisplayName("Multi-occurrence and multi-value")
    class MultiOccurrenceAndMultiValue {

        @Test
        @DisplayName("Should_ReplaceAllOccurrences_When_ValueRepeatsAcrossBlocks")
        void Should_ReplaceAllOccurrences_When_ValueRepeatsAcrossBlocks() {
            // Given
            String storage = fixture("26-multi-occurrence");
            String token = RedactionToken.forType("EMAIL_ADDRESS");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("laura.moser@example.ch", token)),
                RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(3);
                softly.assertThat(result.redactedXhtml()).doesNotContain("laura.moser@example.ch");
            });
        }

        @Test
        @DisplayName("Should_ReplaceAllOccurrences_When_EncodingsDifferBetweenOccurrences")
        void Should_ReplaceAllOccurrences_When_EncodingsDifferBetweenOccurrences() {
            // Given
            String storage = fixture("44-mixed-encoding-occurrences");
            String token = RedactionToken.forType("PHONE_NUMBER");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("021 316 40 00", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(2);
                softly.assertThat(containsWordBounded(extractedText(result.redactedXhtml()), "021 316 40 00"))
                    .isFalse();
            });
        }

        @Test
        @DisplayName("Should_RedactEachValue_When_MultipleReplacementsSubmitted")
        void Should_RedactEachValue_When_MultipleReplacementsSubmitted() {
            // Given
            String storage = fixture("09-table-simple");
            List<ValueReplacement> replacements = List.of(
                new ValueReplacement("Alice Bernard", "[PERSON_NAME]"),
                new ValueReplacement("alice.bernard@example.ch", "[EMAIL_ADDRESS]"),
                new ValueReplacement("bob.favre@example.ch", "[EMAIL_ADDRESS]"));

            // When
            RedactionResult result = redactor.redact(storage, replacements, RedactionGuardConfig.defaults());

            // Then
            String visibleTextAfter = extractedText(result.redactedXhtml());
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes()).hasSize(3);
                softly.assertThat(result.outcomes()).allSatisfy(outcome -> {
                    assertThat(outcome.found()).isTrue();
                    assertThat(outcome.occurrencesReplaced()).isEqualTo(1);
                });
                softly.assertThat(visibleTextAfter).contains("[PERSON_NAME]").contains("[EMAIL_ADDRESS]");
                softly.assertThat(visibleTextAfter)
                    .doesNotContain("Alice Bernard")
                    .doesNotContain("alice.bernard@example.ch")
                    .doesNotContain("bob.favre@example.ch");
            });
        }

        @Test
        @DisplayName("Should_RedactBothValues_When_OneValueIsWordBoundedSubstringOfAnother")
        void Should_RedactBothValues_When_OneValueIsWordBoundedSubstringOfAnother() {
            // Given
            String storage = fixture("46-overlapping-values");
            String token = RedactionToken.forType("PERSON_NAME");
            List<ValueReplacement> replacements = List.of(
                new ValueReplacement("Meier", token),
                new ValueReplacement("Anna Meier", token));

            // When
            RedactionResult result = redactor.redact(storage, replacements, RedactionGuardConfig.defaults());

            // Then
            String visibleTextAfter = extractedText(result.redactedXhtml());
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().get(0).found()).isTrue();
                softly.assertThat(result.outcomes().get(1).found()).isTrue();
                softly.assertThat(containsWordBounded(visibleTextAfter, "Anna Meier")).isFalse();
                softly.assertThat(containsWordBounded(visibleTextAfter, "Meier")).isFalse();
                softly.assertThat(visibleTextAfter).doesNotContain("Anna");
            });
        }

        @Test
        @DisplayName("Should_ReturnOutcomesInInputOrder_When_SomeValuesNotFound")
        void Should_ReturnOutcomesInInputOrder_When_SomeValuesNotFound() {
            // Given
            String storage = fixture("26-multi-occurrence");
            List<ValueReplacement> replacements = List.of(
                new ValueReplacement("laura.moser@example.ch", "[EMAIL_ADDRESS]"),
                new ValueReplacement("ghost@example.ch", "[EMAIL_ADDRESS]"),
                new ValueReplacement("ab", "[SHORT]"));

            // When
            RedactionResult result = redactor.redact(storage, replacements, RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes()).hasSize(3);
                softly.assertThat(result.outcomes().get(0).occurrencesReplaced()).isEqualTo(3);
                softly.assertThat(result.outcomes().get(1).found()).isFalse();
                softly.assertThat(result.outcomes().get(2).found()).isFalse();
            });
        }
    }

    @Nested
    @DisplayName("Round-trip integrity")
    class RoundTripIntegrity {

        @Test
        @DisplayName("Should_ReturnInputVerbatim_When_NoReplacementMatches")
        void Should_ReturnInputVerbatim_When_NoReplacementMatches() {
            // Given
            String storage = fixture("41-deep-nesting");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("Person Inexistante", "[PERSON_NAME]")),
                RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.redactedXhtml()).isEqualTo(storage);
                softly.assertThat(result.outcomes().getFirst().found()).isFalse();
            });
        }

        @Test
        @DisplayName("Should_ReturnInputVerbatim_When_NoReplacementsGiven")
        void Should_ReturnInputVerbatim_When_NoReplacementsGiven() {
            // Given
            String storage = fixture("22-macro-info-richtext");

            // When
            RedactionResult result = redactor.redact(storage, List.of(), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.redactedXhtml()).isEqualTo(storage);
                softly.assertThat(result.outcomes()).isEmpty();
            });
        }

        @Test
        @DisplayName("Should_KeepXmlSerializationSemantics_When_PageHasNbspAndSelfClosedMacro")
        void Should_KeepXmlSerializationSemantics_When_PageHasNbspAndSelfClosedMacro() {
            // Given
            String storage = fixture("47-xml-serialization");
            String token = RedactionToken.forType("PERSON_NAME");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("Jean Dupont", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains(token);
                softly.assertThat(result.redactedXhtml())
                    .as("U+00A0 must keep XML numeric-entity escaping, not HTML &nbsp;")
                    .contains("&#xa0;")
                    .doesNotContain("&nbsp;");
                softly.assertThat(result.redactedXhtml())
                    .as("self-closed macro must stay self-closed under XML syntax")
                    .doesNotContain("</ac:image>");
            });
        }

        @Test
        @DisplayName("Should_PreserveUntouchedMacrosAndResourceIdentifiers_When_RedactingNearbyText")
        void Should_PreserveUntouchedMacrosAndResourceIdentifiers_When_RedactingNearbyText() {
            // Given
            String storage = fixture("45-status-macro-and-mention");
            String token = RedactionToken.forType("PERSON_NAME");

            // When
            RedactionResult result = redactor.redact(
                storage, List.of(new ValueReplacement("Omar Haddad", token)), RedactionGuardConfig.defaults());

            // Then
            assertSoftly(softly -> {
                softly.assertThat(result.outcomes().getFirst().occurrencesReplaced()).isEqualTo(1);
                softly.assertThat(result.redactedXhtml()).contains("ac:name=\"status\"");
                softly.assertThat(result.redactedXhtml())
                    .contains("<ac:parameter ac:name=\"title\">EN COURS</ac:parameter>");
                softly.assertThat(result.redactedXhtml()).contains("ri:userkey=\"abcd1234\"");
                softly.assertThat(result.redactedXhtml()).doesNotContain("Omar Haddad");
            });
        }
    }

    private static String fixture(String name) {
        String resource = "/storage-redaction-corpus/" + name + ".xhtml";
        try (InputStream in = Objects.requireNonNull(
            StorageContentRedactorTest.class.getResourceAsStream(resource), resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Approximates the production extraction pipeline (HtmlContentParser): HTML parse,
     * Confluence metadata tags removed, entities decoded, whitespace variants collapsed.
     * Absence checks below MUST be word-bounded: a plain contains() check is wrong when the
     * page legitimately keeps a larger token embedding the value (spike-proven trap).
     */
    private static String extractedText(String xhtml) {
        Document doc = Jsoup.parse(xhtml);
        doc.getAllElements().stream()
            .filter(element -> isMetadataTag(element.tagName()))
            .toList()
            .forEach(Element::remove);
        return doc.text()
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\u2007', ' ')
            .replace('\u2009', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static boolean isMetadataTag(String tagName) {
        return tagName.equals("ac:parameter")
            || tagName.equals("ac:image")
            || tagName.equals("ac:emoticon")
            || tagName.startsWith("ri:");
    }

    private static boolean containsWordBounded(String haystack, String needle) {
        int from = 0;
        int index;
        while ((index = haystack.indexOf(needle, from)) >= 0) {
            if (isWordBounded(haystack, index, needle.length())) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isWordBounded(String haystack, int start, int length) {
        boolean beforeOk = start == 0 || !Character.isLetterOrDigit(haystack.charAt(start - 1));
        int end = start + length;
        boolean afterOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
        return beforeOk && afterOk;
    }

    private static int elementCount(String xhtml) {
        return Jsoup.parse(xhtml, "", Parser.xmlParser()).getAllElements().size();
    }
}
