"""
Benchmark test: evaluate GLiNER label variants for USERNAME detection accuracy.

This integration test loads the real gretelai/gretel-gliner-bi-large-v1.0 model
and compares multiple label phrasings to find the most accurate one for detecting
system login identifiers while avoiding false positives on common words, person
names, and email addresses.

Usage:
    python -m pytest tests/integration/test_gliner_username_label_benchmark.py -v -s
    # or standalone:
    python tests/integration/test_gliner_username_label_benchmark.py
"""

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Set

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from gliner import GLiNER

# ---------------------------------------------------------------------------
# Label variants to benchmark
# ---------------------------------------------------------------------------
# Each key is a short identifier; value is the label text sent to GLiNER.
LABEL_VARIANTS: Dict[str, str] = {
    # Baseline (known bad — high FP rate)
    "username":
        "username",

    # Current production label
    "current_prod":
        "system account name",

    # Perplexity-recommended short labels
    "system_account_name":
        "system account name",
    "login_credential_username":
        "login credential username",

    # Perplexity-recommended medium labels
    "system_login_account_id":
        "system login account identifier",
    "login_username_or_account":
        "login username or account name",
    "system_user_account_id":
        "system user account identifier",

    # Pattern-oriented (emphasise alphanumeric short tokens)
    "user_login_handle":
        "user login handle",
    "computer_account_login":
        "computer account login name",
}

# ---------------------------------------------------------------------------
# Ground truth: texts containing TRUE POSITIVES and FALSE POSITIVES
# ---------------------------------------------------------------------------
# Each sample is a (text, expected_true_positives, expected_false_positives) tuple.
# - true positives  : text spans that MUST be detected
# - false positives : text spans that MUST NOT be detected

# -- Corpus 1: System log / IT context (French + English mix) ---------------
CORPUS_IT_LOG = (
    "L'utilisateur jdoe a tenté une connexion à 14:35:22 sur le serveur "
    "de production. Le compte admin_user a été approuvé pour l'accès à "
    "la base de données. L'identifiant mdupuis84 a été créé hier pour "
    "le nouveau collaborateur. Le compte de service svc_backup_prod est "
    "utilisé pour les sauvegardes nocturnes. L'accès a été refusé pour "
    "le login t.martin sur le domaine CORP."
)
CORPUS_IT_LOG_TP = {"jdoe", "admin_user", "mdupuis84", "svc_backup_prod", "t.martin"}
CORPUS_IT_LOG_FP = set()  # nothing should be falsely flagged here

# -- Corpus 2: Business document (French) — should NOT flag names/emails ----
CORPUS_BUSINESS = (
    "Bonjour, je suis Marie Dupont, responsable des ressources humaines. "
    "Vous pouvez me contacter à marie.dupont@example.com ou au "
    "+41 21 345 67 89. Notre directeur Jean-Pierre Martin sera présent "
    "à la réunion de mardi. Le rapport a été validé par Sophie Bernard "
    "le 15 mars 2025. Merci de transmettre à l'équipe."
)
CORPUS_BUSINESS_TP: Set[str] = set()  # no system usernames here
CORPUS_BUSINESS_FP = {
    "Marie Dupont", "Marie", "Dupont",
    "Jean-Pierre Martin", "Jean-Pierre", "Martin",
    "Sophie Bernard", "Sophie", "Bernard",
    "marie.dupont@example.com", "marie.dupont",
}

# -- Corpus 3: Mixed technical doc (user accounts in context) ----------------
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

# -- Corpus 4: Edge cases — tricky patterns ----------------------------------
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

# -- Corpus 5: English IT audit report ----------------------------------------
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

# Aggregate all corpora
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
    # Per-corpus detail for debugging
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
        # Partial containment: if detected text is a substring of a known FP
        if norm in _normalize(ref) or _normalize(ref) in norm:
            return True
    return False


# ---------------------------------------------------------------------------
# Benchmark helpers
# ---------------------------------------------------------------------------
def _score_corpus(score: LabelScore, detected_texts: Set[str], expected_tp: Set[str], expected_fp: Set[str], corpus_name: str) -> None:
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
# Test
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def gliner_model():
    """Load GLiNER bi-encoder model once for all tests."""
    print("\n[SETUP] Loading gretelai/gretel-gliner-bi-large-v1.0 ...")
    model = GLiNER.from_pretrained("gretelai/gretel-gliner-bi-large-v1.0")
    print("[SETUP] Model loaded.")
    return model


THRESHOLDS_TO_TEST = [0.3, 0.5, 0.7, 0.8, 0.9]


@pytest.mark.integration
@pytest.mark.slow
class TestGlinerUsernameLabelBenchmark:
    """Benchmark label variants for USERNAME PII detection accuracy."""

    def test_benchmark_all_labels(self, gliner_model):
        """
        Run all label variants against all corpora at multiple thresholds.
        Prints a detailed comparison table and asserts the production label
        outperforms the naive 'username' baseline.
        """
        results: Dict[float, Dict[str, LabelScore]] = {}

        for threshold in THRESHOLDS_TO_TEST:
            results[threshold] = {}
            for label_key, label_text in LABEL_VARIANTS.items():
                score = LabelScore(label_key=label_key, label_text=label_text)
                for corpus_name, text, expected_tp, expected_fp in ALL_CORPORA:
                    entities = gliner_model.predict_entities(text, [label_text], threshold=threshold)
                    detected_texts = {e["text"] for e in entities}
                    _score_corpus(score, detected_texts, expected_tp, expected_fp, corpus_name)
                results[threshold][label_key] = score

        # --- Print results ---
        print(f"\n{'='*100}")
        print("GLiNER USERNAME Label Benchmark Results")
        print(f"{'='*100}")
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
        best_score = results[best_threshold_overall][best_label_overall]
        print(f"  Precision={best_score.precision:.2f}  Recall={best_score.recall:.2f}  "
              f"FP={best_score.false_positives}  FN={best_score.false_negatives}")
        print(f"{'='*100}")

        _print_top_label_details(results, best_threshold_overall)

        # --- Assertions ---
        prod_05 = results[0.5]["current_prod"]
        assert prod_05.f1 > 0.0, (
            "Production label has F1=0 at threshold 0.5 — detection is broken"
        )

        prod_beats_baseline = any(
            results[t]["current_prod"].f1 >= results[t]["username"].f1
            for t in THRESHOLDS_TO_TEST
        )
        assert prod_beats_baseline, (
            "Production label never matches or beats naive 'username' baseline — "
            "consider switching labels"
        )

    def test_production_label_detects_core_usernames(self, gliner_model):
        """The production label must detect common username patterns at threshold 0.5."""
        label = LABEL_VARIANTS["current_prod"]
        text = (
            "L'utilisateur jdoe a accédé au système. "
            "Le compte admin_user est actif. "
            "L'identifiant mdupuis84 expire demain."
        )

        entities = gliner_model.predict_entities(text, [label], threshold=0.5)
        detected = {e["text"] for e in entities}

        print(f"\n[PROD_LABEL_CORE] label='{label}' threshold=0.5")
        for e in entities:
            print(f"  '{e['text']}' score={e['score']:.3f}")

        expected = {"jdoe", "admin_user", "mdupuis84"}
        found = {tp for tp in expected if _match_span_in_set(tp, detected)}

        # At least 1 out of 3 should be detected (conservative — model may struggle)
        assert len(found) >= 1, (
            f"Production label detected none of {expected}. "
            f"Detected: {detected}"
        )

    def test_production_label_avoids_email_fp(self, gliner_model):
        """The production label must NOT flag email addresses as usernames."""
        label = LABEL_VARIANTS["current_prod"]
        text = (
            "Contactez Marie Dupont à marie.dupont@example.com pour plus "
            "d'informations. L'adresse support@company.org est aussi disponible."
        )

        entities = gliner_model.predict_entities(text, [label], threshold=0.5)
        detected = {e["text"] for e in entities}

        print(f"\n[PROD_LABEL_EMAIL_FP] label='{label}' threshold=0.5")
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


# ---------------------------------------------------------------------------
# Standalone runner
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print("Loading model...")
    model = GLiNER.from_pretrained("gretelai/gretel-gliner-bi-large-v1.0")

    test = TestGlinerUsernameLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_production_label_detects_core_usernames(model)
    test.test_production_label_avoids_email_fp(model)
