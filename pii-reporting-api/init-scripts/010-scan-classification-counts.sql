-- ============================================================
-- Migration 010 : Counts par classification juridique dans scan_severity_counts
--
-- Ajoute 8 colonnes de counts RGPD (4) + nLPD (4) a la table existante
-- pour permettre un affichage tri-mode dans le dashboard sans requete
-- additionnelle. Alimentees via UPSERT atomique pendant le scan,
-- en parallele des counts par severity.
-- ============================================================

ALTER TABLE scan_severity_counts
    ADD COLUMN IF NOT EXISTS nb_gdpr_special_category         INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_gdpr_criminal_data            INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_gdpr_personal_data_high_risk  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_gdpr_personal_data            INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_nlpd_sensitive_data           INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_nlpd_high_risk_profiling_data INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_nlpd_personal_data_high_risk  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS nb_nlpd_personal_data            INTEGER NOT NULL DEFAULT 0;
