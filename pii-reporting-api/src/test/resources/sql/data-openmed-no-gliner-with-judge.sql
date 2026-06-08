-- ============================================================================
-- data-openmed-no-gliner-with-judge.sql
-- Variante corpus IT : OpenMed actif, GLiNER désactivé, LLM judge activé.
-- Objectif : prouver que le judge fonctionne sans GLiNER dans la mêlée (spec §4.1).
-- Le judge n'audite QUE les entités GLiNER (spec §2.5) — sans GLiNER, aucun
-- rejet ne doit survenir (recall absolu sur les détecteurs restants).
-- ============================================================================

BEGIN;

-- Configuration : presidio + regex actifs, gliner désactivé, llm_judge activé.
-- Avec gliner_enabled=false, le judge ne recevra aucune entité à auditer
-- → recall 100% préservé sur Presidio/Regex (AC §5.1).
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
VALUES (1, false, true, true, 0.30, 35, true, false, CURRENT_TIMESTAMP, 'test-openmed-no-gliner-with-judge')
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
