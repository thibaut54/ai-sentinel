package pro.softcom.aisentinel.application.pii.detection.port.in;

import pro.softcom.aisentinel.domain.pii.detection.PiiDetectionConfig;

import java.math.BigDecimal;

/**
 * Port IN for managing PII detection configuration.
 * Defines use cases for retrieving and updating PII detection configuration.
 */
public interface ManagePiiDetectionConfigPort {

    /**
     * Retrieves the current PII detection configuration.
     *
     * @return The current configuration
     */
    PiiDetectionConfig getConfig();

    /**
     * Updates the PII detection configuration.
     *
     * @param command The update command containing new configuration values
     * @return The updated configuration
     * @throws IllegalArgumentException if command validation fails
     */
    PiiDetectionConfig updateConfig(UpdatePiiDetectionConfigCommand command);

    /**
     * Command to update PII detection configuration.
     *
     * @param glinerEnabled    Whether GLiNER detector should be enabled
     * @param presidioEnabled  Whether Presidio detector should be enabled
     * @param regexEnabled     Whether custom regex detector should be enabled
     * @param openmedEnabled   Whether OpenMed detector should be enabled
     * @param gliner2Enabled   Whether GLiNER2 detector should be enabled
     * @param defaultThreshold Default confidence threshold (0.0 to 1.0)
     * @param nbOfLabelByPass     Maximum labels per detector batch
     * @param llmJudgeEnabled     Derived global LLM-judge guard (OR of the five per-detector flags)
     * @param glinerJudgeEnabled  Whether the LLM-as-Judge stage runs on GLiNER findings
     * @param presidioJudgeEnabled Whether the LLM-as-Judge stage runs on Presidio findings
     * @param regexJudgeEnabled   Whether the LLM-as-Judge stage runs on regex findings
     * @param openmedJudgeEnabled Whether the LLM-as-Judge stage runs on OpenMed findings
     * @param gliner2JudgeEnabled Whether the LLM-as-Judge stage runs on GLiNER2 findings
     * @param prefilterEnabled    Whether the deterministic format pre-filter stage is enabled
     * @param updatedBy           User identifier who is updating the configuration
     */
    record UpdatePiiDetectionConfigCommand(
            boolean glinerEnabled,
            boolean presidioEnabled,
            boolean regexEnabled,
            boolean openmedEnabled,
            boolean gliner2Enabled,
            BigDecimal defaultThreshold,
            Integer nbOfLabelByPass,
            boolean llmJudgeEnabled,
            boolean glinerJudgeEnabled,
            boolean presidioJudgeEnabled,
            boolean regexJudgeEnabled,
            boolean openmedJudgeEnabled,
            boolean gliner2JudgeEnabled,
            boolean prefilterEnabled,
            String updatedBy
    ) {
    }
}