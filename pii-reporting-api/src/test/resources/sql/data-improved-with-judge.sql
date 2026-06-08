-- ============================================================================
-- data-improved-with-judge.sql
-- Variante corpus IT : configuration "improved" (GLiNER actif) + LLM judge activé.
-- Objectif : exercer le filtrage post-GLiNER via Qwen 3.6 pour mesurer la réduction
--            du FP rate (spec §4.1, §5.1).
-- ============================================================================

BEGIN;

-- Configuration globale : tous les détecteurs actifs + LLM judge activé.
-- llm_judge_enabled=true déclenche le filtre post-GLiNER (spec §2.5).
INSERT INTO pii_detection_config (
    id,
    gliner_enabled,
    presidio_enabled,
    regex_enabled,
    default_threshold,
    nb_of_label_by_pass,
    llm_judge_enabled,
    prefilter_enabled,
    updated_at,
    updated_by
)
VALUES (1, true, true, true, 0.30, 35, true, false, CURRENT_TIMESTAMP, 'test-improved-with-judge')
    ON CONFLICT (id) DO UPDATE
        SET gliner_enabled      = EXCLUDED.gliner_enabled,
            presidio_enabled    = EXCLUDED.presidio_enabled,
            regex_enabled       = EXCLUDED.regex_enabled,
            default_threshold   = EXCLUDED.default_threshold,
            nb_of_label_by_pass = EXCLUDED.nb_of_label_by_pass,
            llm_judge_enabled   = EXCLUDED.llm_judge_enabled,
            prefilter_enabled   = EXCLUDED.prefilter_enabled,
            updated_at          = EXCLUDED.updated_at,
            updated_by          = EXCLUDED.updated_by;

COMMIT;
