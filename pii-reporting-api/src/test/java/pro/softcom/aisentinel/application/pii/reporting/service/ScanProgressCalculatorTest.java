package pro.softcom.aisentinel.application.pii.reporting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("ScanProgressCalculator")
class ScanProgressCalculatorTest {

    private ScanProgressCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ScanProgressCalculator();
    }

    @ParameterizedTest(name = "analyzed={0}, total={1} -> expected={2}%")
    @CsvSource({
        "0, 10, 0.0",
        "5, 10, 50.0",
        "10, 10, 100.0",
        "1, 4, 25.0",
        "3, 4, 75.0"
    })
    @DisplayName("Should_CalculateCorrectProgress_When_NominalValues")
    void Should_CalculateCorrectProgress_When_NominalValues(int analyzed, int total, double expected) {
        double result = calculator.calculateProgress(analyzed, total);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should_Return100_When_TotalIsZero")
    void Should_Return100_When_TotalIsZero() {
        // Total = 0 means nothing to scan → consider it complete
        double result = calculator.calculateProgress(0, 0);
        assertThat(result).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Should_Return100_When_TotalIsNegative")
    void Should_Return100_When_TotalIsNegative() {
        double result = calculator.calculateProgress(0, -5);
        assertThat(result).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Should_ClampToZero_When_AnalyzedIsNegative")
    void Should_ClampToZero_When_AnalyzedIsNegative() {
        double result = calculator.calculateProgress(-1, 10);
        assertThat(result).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should_ClampTo100_When_AnalyzedExceedsTotal")
    void Should_ClampTo100_When_AnalyzedExceedsTotal() {
        // Edge case: analyzed > total (should not exceed 100%)
        double result = calculator.calculateProgress(15, 10);
        assertSoftly(softly -> {
            softly.assertThat(result).isLessThanOrEqualTo(100.0);
            softly.assertThat(result).isGreaterThanOrEqualTo(0.0);
        });
    }

    @Test
    @DisplayName("Should_ReturnBetween0And100_When_TypicalScanProgress")
    void Should_ReturnBetweenZeroAnd100_When_TypicalScanProgress() {
        for (int analyzed = 0; analyzed <= 100; analyzed += 10) {
            double result = calculator.calculateProgress(analyzed, 100);
            assertSoftly(softly -> {
                softly.assertThat(result).isGreaterThanOrEqualTo(0.0);
                softly.assertThat(result).isLessThanOrEqualTo(100.0);
            });
        }
    }
}
