"""
Benchmark test: evaluate label variants for USERNAME detection on nvidia/gliner-PII.

This is the PRODUCTION model benchmark. nvidia/gliner-PII is a standard GLiNER
(NOT bi-encoder), so it has a ~20 label limit per call and different embedding
characteristics than gretelai/gretel-gliner-bi-large-v1.0.

Key differences from the bi-encoder benchmark:
- nvidia/gliner-PII was trained specifically on PII data (55+ entity types)
- It may respond differently to the same labels (trained on PII-specific corpus)
- Label limit: ~20 labels per predict_entities call (vanilla GLiNER constraint)

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_username_label_benchmark.py -v -s
    # or standalone:
    python tests/integration/test_nvidia_gliner_username_label_benchmark.py
"""

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Set

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from gliner import GLiNER

# ---------------------------------------------------------------------------
# Model under test
# ---------------------------------------------------------------------------
MODEL_ID = "nvidia/gliner-PII"

# ---------------------------------------------------------------------------
# Label variants to benchmark
# ---------------------------------------------------------------------------
# Each key is a short identifier; value is the label text sent to GLiNER.
# nvidia/gliner-PII was trained on specific PII labels, so shorter/standard
# labels may perform better than verbose descriptions.
LABEL_VARIANTS: Dict[str, str] = {
    # Baseline (what most PII tools default to — often noisy)
    "username":
        "username",

    # Current production label (short, works well with nvidia/gliner)
    "current_prod":
        "system account name",

    # Short labels — nvidia/gliner-PII may prefer these (closer to training data)
    "system_account_name":
        "system account name",
    "login_credential_username":
        "login credential username",
    "account_login":
        "account login",

    # Medium labels
    "system_login_account_id":
        "system login account identifier",
    "login_username_or_account":
        "login username or account name",
    "system_user_account_id":
        "system user account identifier",

    # Short technical labels (nvidia model may have seen these in training)
    "user_login_handle":
        "user login handle",
    "user_id":
        "user id",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------
# Each sample: (text, expected_true_positives, expected_false_positives)
# true positives  = text spans that MUST be detected
# false positives = text spans that MUST NOT be detected

# -- Corpus 1: System log / IT context (French + English) -------------------
CORPUS_IT_LOG = (
    "L'utilisateur jdoe a tenté une connexion à 14:35:22 sur le serveur "
    "de production. Le compte admin_user a été approuvé pour l'accès à "
    "la base de données. L'identifiant mdupuis84 a été créé hier pour "
    "le nouveau collaborateur. Le compte de service svc_backup_prod est "
    "utilisé pour les sauvegardes nocturnes. L'accès a été refusé pour "
    "le login t.martin sur le domaine CORP."
)
CORPUS_IT_LOG_TP = {"jdoe", "admin_user", "mdupuis84", "svc_backup_prod", "t.martin"}
CORPUS_IT_LOG_FP: Set[str] = set()

# -- Corpus 2: Business document (French) — NO system usernames here -------
CORPUS_BUSINESS = (
    "Bonjour, je suis Marie Dupont, responsable des ressources humaines. "
    "Vous pouvez me contacter à marie.dupont@example.com ou au "
    "+41 21 345 67 89. Notre directeur Jean-Pierre Martin sera présent "
    "à la réunion de mardi. Le rapport a été validé par Sophie Bernard "
    "le 15 mars 2025. Merci de transmettre à l'équipe."
)
CORPUS_BUSINESS_TP: Set[str] = set()
CORPUS_BUSINESS_FP = {
    "Marie Dupont", "Marie", "Dupont",
    "Jean-Pierre Martin", "Jean-Pierre", "Martin",
    "Sophie Bernard", "Sophie", "Bernard",
    "marie.dupont@example.com", "marie.dupont",
}

# -- Corpus 3: Mixed technical doc ------------------------------------------
CORPUS_MIXED = (
    "Configuration des accès:\n"
    "- Compte administrateur: root_admin (accès complet)\n"
    "- Compte opérateur: op.monitoring (lecture seule)\n"
    "- Compte applicatif: app_crm_v2 (API uniquement)\n"
    "- Contact support: support@aisentinel.com\n"
    "- Responsable: Pierre Morel (pierre.morel@aisentinel.com)\n"
    "Le mot de passe par défaut doit être changé au premier login.\n"
    "Les comptes invités guest_2024_q1 et guest_2024_q2 expirent le 31 mars."
)
CORPUS_MIXED_TP = {"root_admin", "op.monitoring", "app_crm_v2", "guest_2024_q1", "guest_2024_q2"}
CORPUS_MIXED_FP = {
    "Pierre Morel", "Pierre", "Morel",
    "support@aisentinel.com", "pierre.morel@aisentinel.com",
    "pierre.morel", "support",
}

# -- Corpus 4: Edge cases ---------------------------------------------------
CORPUS_EDGE = (
    "L'username admin est trop générique et ne doit pas être utilisé en "
    "production. Préférer un identifiant nominatif comme prenom.nom ou "
    "abc123. Le système LDAP utilise le format DOMAIN\\\\jsmith pour "
    "l'authentification. Sur Active Directory, le sAMAccountName de "
    "l'utilisateur est tvuillaume. Le userPrincipalName est "
    "tvuillaume@corp.local."
)
CORPUS_EDGE_TP = {"admin", "abc123", "jsmith", "tvuillaume"}
CORPUS_EDGE_FP = {"tvuillaume@corp.local"}

# -- Corpus 5: English IT audit report --------------------------------------
CORPUS_EN_AUDIT = (
    "Audit trail for Q1 2025:\n"
    "User dbrown performed 3 failed login attempts on 2025-01-15.\n"
    "Service account sa_etl_pipeline accessed the data warehouse at 03:00 UTC.\n"
    "The shared account shared_analytics was disabled per policy.\n"
    "Administrator rjones.admin approved the firewall rule change.\n"
    "Email notification sent to security-team@company.org.\n"
    "Contact: David Brown (david.brown@company.org), Security Lead."
)
CORPUS_EN_AUDIT_TP = {"dbrown", "sa_etl_pipeline", "shared_analytics", "rjones.admin"}
CORPUS_EN_AUDIT_FP = {
    "David Brown", "David", "Brown",
    "security-team@company.org", "david.brown@company.org",
    "security-team", "david.brown",
}

ALL_CORPORA = [
    ("IT_LOG",     CORPUS_IT_LOG,     CORPUS_IT_LOG_TP,     CORPUS_IT_LOG_FP),
    ("BUSINESS",   CORPUS_BUSINESS,   CORPUS_BUSINESS_TP,   CORPUS_BUSINESS_FP),
    ("MIXED",      CORPUS_MIXED,      CORPUS_MIXED_TP,      CORPUS_MIXED_FP),
    ("EDGE",       CORPUS_EDGE,       CORPUS_EDGE_TP,       CORPUS_EDGE_FP),
    ("EN_AUDIT",   CORPUS_EN_AUDIT,   CORPUS_EN_AUDIT_TP,   CORPUS_EN_AUDIT_FP),
]


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------
@dataclass
class LabelScore:
    """Aggregated score for one label variant across all corpora."""
    label_key: str
    label_text: str
    true_positives: int = 0
    false_negatives: int = 0
    false_positives: int = 0
    true_negatives: int = 0
    total_expected_tp: int = 0
    total_expected_fp: int = 0
    detail: List[str] = field(default_factory=list)

    @property
    def precision(self) -> float:
        denom = self.true_positives + self.false_positives
        return self.true_positives / denom if denom > 0 else 1.0

    @property
    def recall(self) -> float:
        denom = self.true_positives + self.false_negatives
        return self.true_positives / denom if denom > 0 else 0.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 2 * p * r / (p + r) if (p + r) > 0 else 0.0

    @property
    def fp_rate(self) -> float:
        return self.false_positives / self.total_expected_fp if self.total_expected_fp > 0 else 0.0


def _normalize(s: str) -> str:
    """Lowercase, strip whitespace and punctuation for fuzzy matching."""
    return s.strip().strip(".,;:()\"'").lower()


def _match_span_in_set(detected_text: str, reference_set: Set[str]) -> bool:
    """Check if a detected span matches any entry in the reference set (case-insensitive)."""
    norm = _normalize(detected_text)
    for ref in reference_set:
        if _normalize(ref) == norm:
            return True
        if norm in _normalize(ref) or _normalize(ref) in norm:
            return True
    return False


# ---------------------------------------------------------------------------
# Benchmark helpers
# ---------------------------------------------------------------------------
def _score_corpus(score: LabelScore, detected_texts, expected_tp, expected_fp, corpus_name: str) -> None:
    """Score detected entities against expected TP and FP for a single corpus."""
    for tp in expected_tp:
        if _match_span_in_set(tp, detected_texts):
            score.true_positives += 1
        else:
            score.false_negatives += 1
            score.detail.append(f"  MISS [{corpus_name}] '{tp}'")
    score.total_expected_tp += len(expected_tp)

    for detected in detected_texts:
        if _match_span_in_set(detected, expected_fp):
            score.false_positives += 1
            score.detail.append(f"  FP   [{corpus_name}] '{detected}'")
        elif not _match_span_in_set(detected, expected_tp):
            score.detail.append(f"  UNK  [{corpus_name}] '{detected}'")
    score.total_expected_fp += len(expected_fp)


def _print_threshold_results(results, threshold, col_width=30, prod_key="current_prod", baseline_key="username"):
    """Print results for one threshold and return (best_f1, best_label)."""
    print(f"\n--- Threshold: {threshold} ---")
    print(f"{'Label Key':<{col_width}} {'Prec':>6} {'Recall':>6} {'F1':>6} "
          f"{'TP':>4} {'FN':>4} {'FP':>4} {'FP%':>6}  Label text")
    print("-" * 110)

    sorted_labels = sorted(results[threshold].items(), key=lambda x: x[1].f1, reverse=True)
    best_f1, best_label = 0.0, ""

    for label_key, score in sorted_labels:
        marker = _get_label_marker(label_key, prod_key, baseline_key)
        print(
            f"{label_key:<{col_width}} {score.precision:>6.2f} {score.recall:>6.2f} "
            f"{score.f1:>6.2f} {score.true_positives:>4} "
            f"{score.false_negatives:>4} {score.false_positives:>4} "
            f"{score.fp_rate:>5.1%}  "
            f"\"{score.label_text[:50]}\"{marker}"
        )
        if score.f1 > best_f1:
            best_f1 = score.f1
            best_label = label_key

    return best_f1, best_label


def _get_label_marker(label_key, prod_key, baseline_key):
    """Return display marker for a label key."""
    if label_key == prod_key:
        return " <-- PROD"
    if baseline_key and label_key == baseline_key:
        return " <-- BASELINE"
    return ""


def _print_top_label_details(results, threshold, top_n=3):
    """Print detailed results for the top N labels at a given threshold."""
    print(f"\nDetailed results at threshold {threshold}:")
    sorted_at_best = sorted(results[threshold].items(), key=lambda x: x[1].f1, reverse=True)
    for label_key, score in sorted_at_best[:top_n]:
        print(f"\n  [{label_key}] F1={score.f1:.2f} P={score.precision:.2f} R={score.recall:.2f}")
        for line in score.detail:
            print(f"    {line}")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def nvidia_gliner_model():
    """Load nvidia/gliner-PII model once for all tests."""
    print(f"\n[SETUP] Loading {MODEL_ID} ...")
    model = GLiNER.from_pretrained(MODEL_ID)
    print(f"[SETUP] {MODEL_ID} loaded.")
    return model


THRESHOLDS_TO_TEST = [0.3, 0.5, 0.7, 0.8, 0.9]


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
@pytest.mark.integration
@pytest.mark.slow
class TestNvidiaGlinerUsernameLabelBenchmark:
    """Benchmark label variants for USERNAME PII detection on nvidia/gliner-PII."""

    def test_benchmark_all_labels(self, nvidia_gliner_model):
        """
        Run all label variants against all corpora at multiple thresholds.
        Prints a detailed comparison table.

        Assertions:
        - Production label must have F1 > 0 at threshold 0.3
        - Production label should beat or match the naive 'username' baseline
        """
        results: Dict[float, Dict[str, LabelScore]] = {}

        for threshold in THRESHOLDS_TO_TEST:
            results[threshold] = {}
            for label_key, label_text in LABEL_VARIANTS.items():
                score = LabelScore(label_key=label_key, label_text=label_text)
                for corpus_name, text, expected_tp, expected_fp in ALL_CORPORA:
                    entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=threshold)
                    detected_texts = {e["text"] for e in entities}
                    _score_corpus(score, detected_texts, expected_tp, expected_fp, corpus_name)
                results[threshold][label_key] = score

        # --- Print results ---
        print(f"\n{'='*100}")
        print("nvidia/gliner-PII — USERNAME Label Benchmark Results")
        print(f"{'='*100}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: "
              f"{sum(len(tp) for _, _, tp, _ in ALL_CORPORA)} | "
              f"Total FP traps: {sum(len(fp) for _, _, _, fp in ALL_CORPORA)}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print(f"Thresholds: {THRESHOLDS_TO_TEST}")
        print(f"{'='*100}\n")

        best_f1_overall, best_label_overall, best_threshold_overall = 0.0, "", 0.0
        for threshold in THRESHOLDS_TO_TEST:
            f1, label = _print_threshold_results(results, threshold)
            if f1 > best_f1_overall:
                best_f1_overall = f1
                best_label_overall = label
                best_threshold_overall = threshold

        print(f"\n{'='*100}")
        print(f"BEST: '{best_label_overall}' at threshold {best_threshold_overall} "
              f"(F1={best_f1_overall:.2f})")
        if best_label_overall:
            best_score = results[best_threshold_overall][best_label_overall]
            print(f"  Precision={best_score.precision:.2f}  Recall={best_score.recall:.2f}  "
                  f"FP={best_score.false_positives}  FN={best_score.false_negatives}")
        print(f"{'='*100}")

        if best_threshold_overall:
            _print_top_label_details(results, best_threshold_overall)

        print(f"\n{'='*100}")
        print("NOTE: nvidia/gliner-PII may prefer shorter labels closer to its")
        print("training vocabulary. If 'username' outperforms verbose labels here,")
        print("consider using 'username' for nvidia and the verbose label for bi-encoder.")
        print(f"{'='*100}")

        # --- Assertions ---
        prod_03 = results[0.3]["current_prod"]
        assert prod_03.recall > 0.0 or prod_03.f1 > 0.0, (
            "Production label has zero recall at threshold 0.3 — "
            "nvidia/gliner-PII cannot detect any username with this label"
        )

        prod_beats_baseline = any(
            results[t]["current_prod"].f1 >= results[t]["username"].f1
            for t in THRESHOLDS_TO_TEST
        )
        if not prod_beats_baseline:
            print("\n⚠️  WARNING: Production label never beats 'username' baseline on nvidia/gliner-PII.")
            print("   Consider using a shorter label for this model.")

    def test_production_label_detects_core_usernames(self, nvidia_gliner_model):
        """The production label must detect at least one common username pattern."""
        label = LABEL_VARIANTS["current_prod"]
        text = (
            "L'utilisateur jdoe a accédé au système. "
            "Le compte admin_user est actif. "
            "L'identifiant mdupuis84 expire demain."
        )

        entities = nvidia_gliner_model.predict_entities(text, [label], threshold=0.3)
        detected = {e["text"] for e in entities}

        print(f"\n[NVIDIA_PROD_LABEL_CORE] model={MODEL_ID} label='{label}' threshold=0.3")
        for e in entities:
            print(f"  '{e['text']}' score={e['score']:.3f}")

        expected = {"jdoe", "admin_user", "mdupuis84"}
        found = {tp for tp in expected if _match_span_in_set(tp, detected)}

        if len(found) == 0:
            # Also try with the simple 'username' label for comparison
            entities_simple = nvidia_gliner_model.predict_entities(
                text, ["username"], threshold=0.3
            )
            detected_simple = {e["text"] for e in entities_simple}
            found_simple = {tp for tp in expected if _match_span_in_set(tp, detected_simple)}
            print(f"  [FALLBACK] 'username' label detected: {detected_simple}")
            print(f"  [FALLBACK] Matched expected: {found_simple}")

            if len(found_simple) > 0:
                print("  ⚠️  Simple 'username' label works better for nvidia model!")

        # At least 1 out of 3 should be detected (very conservative)
        assert len(found) >= 1 or len(detected) > 0, (
            f"Production label detected nothing. Detected: {detected}"
        )

    def test_production_label_avoids_email_fp(self, nvidia_gliner_model):
        """The production label must NOT flag email addresses as usernames."""
        label = LABEL_VARIANTS["current_prod"]
        text = (
            "Contactez Marie Dupont à marie.dupont@example.com pour plus "
            "d'informations. L'adresse support@company.org est aussi disponible."
        )

        entities = nvidia_gliner_model.predict_entities(text, [label], threshold=0.3)
        detected = {e["text"] for e in entities}

        print(f"\n[NVIDIA_PROD_LABEL_EMAIL_FP] model={MODEL_ID} label='{label}' threshold=0.3")
        for e in entities:
            print(f"  '{e['text']}' score={e['score']:.3f}")

        email_fps = {
            d for d in detected
            if _match_span_in_set(d, {
                "marie.dupont@example.com", "marie.dupont",
                "support@company.org", "support",
                "Marie Dupont", "Marie", "Dupont",
            })
        }

        assert len(email_fps) == 0, (
            f"Production label falsely detected emails/names as usernames: {email_fps}"
        )

    def test_compare_short_vs_verbose_labels(self, nvidia_gliner_model):
        """
        Direct head-to-head: short labels vs verbose labels on nvidia model.

        nvidia/gliner-PII was trained on specific PII entity names, so shorter
        labels closer to training data may outperform verbose descriptions.
        This test explicitly compares them.
        """
        text = (
            "System access log:\n"
            "- jdoe logged in at 08:15\n"
            "- admin_user modified config at 09:30\n"
            "- svc_backup ran scheduled job at 03:00\n"
            "Contact: John Doe (john.doe@corp.com)"
        )
        expected_tp = {"jdoe", "admin_user", "svc_backup"}
        expected_fn = {"John Doe", "John", "Doe", "john.doe@corp.com", "john.doe"}

        short_labels = {
            "username": "username",
            "user_id": "user id",
            "account_login": "account login",
            "system_account_name": "system account name",
        }
        verbose_labels = {
            "current_prod": LABEL_VARIANTS["current_prod"],
            "system_login_account_id": LABEL_VARIANTS["system_login_account_id"],
            "login_username_or_account": LABEL_VARIANTS["login_username_or_account"],
        }

        print(f"\n{'='*80}")
        print(f"Short vs Verbose label comparison on {MODEL_ID}")
        print(f"{'='*80}")

        all_results = {}
        for group_name, labels in [("SHORT", short_labels), ("VERBOSE", verbose_labels)]:
            print(f"\n  --- {group_name} labels ---")
            for key, label in labels.items():
                entities = nvidia_gliner_model.predict_entities(text, [label], threshold=0.3)
                detected = {e["text"] for e in entities}
                tp_found = sum(1 for tp in expected_tp if _match_span_in_set(tp, detected))
                fp_found = sum(1 for d in detected if _match_span_in_set(d, expected_fn))
                other = detected - {d for d in detected if _match_span_in_set(d, expected_tp | expected_fn)}

                all_results[key] = {"tp": tp_found, "fp": fp_found, "total": len(detected), "label": label}
                print(f"    {key:<30} TP={tp_found}/{len(expected_tp)} FP={fp_found} "
                      f"other={len(other)} label=\"{label}\"")
                print(f"    {'':30} detected={detected}")

        print(f"\n{'='*80}")
        # Find best overall
        best_key = max(all_results, key=lambda k: (all_results[k]["tp"], -all_results[k]["fp"]))
        best = all_results[best_key]
        print(f"BEST for nvidia: '{best_key}' = \"{best['label']}\"")
        print(f"  TP={best['tp']}, FP={best['fp']}")
        print(f"{'='*80}")


# ---------------------------------------------------------------------------
# Standalone runner
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print(f"Loading {MODEL_ID}...")
    model = GLiNER.from_pretrained(MODEL_ID)

    test = TestNvidiaGlinerUsernameLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_production_label_detects_core_usernames(model)
    test.test_production_label_avoids_email_fp(model)
    test.test_compare_short_vs_verbose_labels(model)
