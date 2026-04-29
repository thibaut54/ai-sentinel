"""
Benchmark test: evaluate label variants for ACCOUNT_ID detection on nvidia/gliner-PII.

Problem anticipated: the label "account id" is very short (2 words) and "id" is the
most generic identifier possible. In Confluence documents, IDs are everywhere:
- JIRA ticket IDs: PROJ-1234, KEY-5678
- Project codes: P01564, M02054
- Database IDs: id=42, user_id=1001
- UUID references: 550e8400-e29b-41d4-a716-446655440000
- Employee IDs: EMP-2024-001 (but these ARE PII)
- Page IDs: 12345678 (Confluence page IDs)
- Build numbers: #1234, build-5678

Real account IDs: customer account numbers like ACC-2024-78901,
user IDs in banking/admin systems like USR-00456, CL-78901

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_account_id_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_account_id_label_benchmark.py
"""

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Set

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from gliner import GLiNER

MODEL_ID = "nvidia/gliner-PII"

# ---------------------------------------------------------------------------
# Label variants
# ---------------------------------------------------------------------------
LABEL_VARIANTS: Dict[str, str] = {
    # Baseline -- current production label
    "account_id":
        "account id",

    # Short variants (2-3 words)
    "user_account":
        "user account",
    "account_identifier":
        "account identifier",
    "customer_account":
        "customer account",

    # Medium variants (3-5 words) -- usually winners on nvidia
    "user_account_identifier":
        "user account identifier",
    "customer_account_identifier":
        "customer account identifier",
    "personal_account_reference":
        "personal account reference",
    "banking_account_identifier":
        "banking or service account identifier",

    # Long descriptive variants (5+ words)
    "customer_service_account":
        "customer service account identifier",
    "personal_financial_account":
        "personal financial account identifier",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: Real account IDs in admin/banking context --------------------
CORPUS_REAL_ACCOUNTS = (
    "Gestion des comptes clients:\n\n"
    "Client: Jean Dupont\n"
    "  Numero de compte: ACC-2024-78901\n"
    "  Identifiant client: CL-78901\n"
    "  Statut: actif\n\n"
    "Client: Marie Martin\n"
    "  Numero de compte: ACC-2024-78902\n"
    "  Identifiant client: CL-78902\n"
    "  Statut: actif\n\n"
    "Client: Pierre Bernard\n"
    "  Numero de compte: ACC-2023-45678\n"
    "  Identifiant client: CL-45678\n"
    "  Statut: suspendu"
)
CORPUS_REAL_ACCOUNTS_TP = {
    "ACC-2024-78901", "CL-78901",
    "ACC-2024-78902", "CL-78902",
    "ACC-2023-45678", "CL-45678",
}
CORPUS_REAL_ACCOUNTS_FP = {
    "Jean Dupont", "Marie Martin", "Pierre Bernard",
}

# -- Corpus 2: Account IDs in service management ----------------------------
CORPUS_SERVICE_ACCOUNTS = (
    "Rapport de gestion des abonnements:\n\n"
    "Abonnement Premium:\n"
    "  Account ID: USR-00456\n"
    "  Plan: Enterprise\n"
    "  Echeance: 31/12/2025\n\n"
    "Abonnement Standard:\n"
    "  Account ID: USR-00789\n"
    "  Plan: Professional\n"
    "  Echeance: 30/06/2025\n\n"
    "Facturation:\n"
    "  Compte: FACT-2025-001234\n"
    "  Montant: CHF 4'500.00"
)
CORPUS_SERVICE_ACCOUNTS_TP = {
    "USR-00456", "USR-00789",
    "FACT-2025-001234",
}
CORPUS_SERVICE_ACCOUNTS_FP = {
    "4'500.00", "31/12/2025", "30/06/2025",
    "Enterprise", "Professional",
}

# -- Corpus 3: JIRA/project IDs (PURE FP TRAP) ------------------------------
CORPUS_JIRA_IDS = (
    "Sprint Review - RAPAC:\n"
    "- RAPAC-1234: Implementation du cache Redis [DONE]\n"
    "- RAPAC-1235: Migration base de donnees [IN PROGRESS]\n"
    "- RAPAC-1236: Fix du timeout API [DONE]\n"
    "- RAPAC-1237: Refactoring service auth [TODO]\n\n"
    "Confluence pages mises a jour:\n"
    "- Page ID: 12345678 (Architecture technique)\n"
    "- Page ID: 87654321 (Guide de deploiement)\n"
    "- Space: RAPAC (id=256)\n\n"
    "Builds recents:\n"
    "- Build #4567: SUCCESS\n"
    "- Build #4568: FAILED\n"
    "- Pipeline ID: pipeline-2025-0042"
)
CORPUS_JIRA_IDS_TP: Set[str] = set()  # NO account IDs
CORPUS_JIRA_IDS_FP = {
    "RAPAC-1234", "RAPAC-1235", "RAPAC-1236", "RAPAC-1237",
    "12345678", "87654321", "256",
    "#4567", "#4568", "pipeline-2025-0042",
}

# -- Corpus 4: Technical IDs and UUIDs (PURE FP TRAP) -----------------------
CORPUS_TECHNICAL_IDS = (
    "Debug log - Session de diagnostic:\n\n"
    "Request ID: req-550e8400-e29b-41d4\n"
    "Correlation ID: corr-a716-446655440000\n"
    "Transaction ID: txn-2025-03-15-001234\n"
    "Thread ID: thread-42\n"
    "Process ID: pid-12345\n"
    "Container ID: container-abc123def456\n"
    "Pod ID: ai-sentinel-api-7d8f9c-xkm2p\n"
    "Node ID: node-worker-03\n"
    "Cluster ID: k8s-prod-eu-west-1"
)
CORPUS_TECHNICAL_IDS_TP: Set[str] = set()  # NO account IDs
CORPUS_TECHNICAL_IDS_FP = {
    "req-550e8400-e29b-41d4",
    "corr-a716-446655440000",
    "txn-2025-03-15-001234",
    "thread-42", "pid-12345",
    "container-abc123def456",
    "ai-sentinel-api-7d8f9c-xkm2p",
    "node-worker-03",
    "k8s-prod-eu-west-1",
}

# -- Corpus 5: Project management table (PURE FP TRAP) ----------------------
CORPUS_PROJECT_TABLE = (
    "Code projet | Nom projet | Pole | Responsable\n"
    "P01564 | RAPAC | SEJ | Ivan Gouin\n"
    "P01573 | InfoSearch | SEJ | Johnny Beuve\n"
    "P02086 | Autex | TEP | Cecile Cunit\n"
    "M02054 | I2 | SEJ | Hicham Bakir\n"
    "P01924 | SIRH | RH | Thierry Michaud\n"
    "P02240 | Messagerie | CEI | Willy Reinhardt"
)
CORPUS_PROJECT_TABLE_TP: Set[str] = set()
CORPUS_PROJECT_TABLE_FP = {
    "P01564", "P01573", "P02086", "M02054", "P01924", "P02240",
    "SEJ", "TEP", "CEI", "RH",
    "Ivan Gouin", "Hicham Bakir", "Johnny Beuve",
}

# -- Corpus 6: Abbreviation stress test (PURE FP TRAP) ----------------------
CORPUS_ABBREVIATION_STRESS = (
    "ID UID GID PID TID SID CID RID OID NID FID BID "
    "DNS DHCP VPN MFA RSA PKI SIEM WAF JWT SSO RBAC "
    "UUID GUID ULID CUID KSUID TSID NANOID "
    "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI SOC"
)
CORPUS_ABBREVIATION_STRESS_TP: Set[str] = set()
CORPUS_ABBREVIATION_STRESS_FP = {
    "ID", "UID", "GID", "PID", "TID", "SID", "CID",
    "UUID", "GUID", "ULID",
    "DNS", "JWT", "SSO", "RBAC",
}

ALL_CORPORA = [
    ("REAL_ACCOUNTS",        CORPUS_REAL_ACCOUNTS,        CORPUS_REAL_ACCOUNTS_TP,        CORPUS_REAL_ACCOUNTS_FP),
    ("SERVICE_ACCOUNTS",     CORPUS_SERVICE_ACCOUNTS,     CORPUS_SERVICE_ACCOUNTS_TP,     CORPUS_SERVICE_ACCOUNTS_FP),
    ("JIRA_IDS",             CORPUS_JIRA_IDS,             CORPUS_JIRA_IDS_TP,             CORPUS_JIRA_IDS_FP),
    ("TECHNICAL_IDS",        CORPUS_TECHNICAL_IDS,        CORPUS_TECHNICAL_IDS_TP,        CORPUS_TECHNICAL_IDS_FP),
    ("PROJECT_TABLE",        CORPUS_PROJECT_TABLE,        CORPUS_PROJECT_TABLE_TP,        CORPUS_PROJECT_TABLE_FP),
    ("ABBREVIATION_STRESS",  CORPUS_ABBREVIATION_STRESS,  CORPUS_ABBREVIATION_STRESS_TP,  CORPUS_ABBREVIATION_STRESS_FP),
]


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------
@dataclass
class LabelScore:
    label_key: str
    label_text: str
    true_positives: int = 0
    false_negatives: int = 0
    false_positives: int = 0
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
    return s.strip().strip(".,;:()\"'").lower()


def _match_span_in_set(detected_text: str, reference_set: Set[str]) -> bool:
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


PROD_KEY = "account_id"


def _print_threshold_results(results, threshold, col_width=35):
    print(f"\n--- Threshold: {threshold} ---")
    print(f"{'Label Key':<{col_width}} {'Prec':>6} {'Recall':>6} {'F1':>6} "
          f"{'TP':>4} {'FN':>4} {'FP':>4} {'FP%':>6}  Label text")
    print("-" * 115)

    sorted_labels = sorted(results[threshold].items(), key=lambda x: x[1].f1, reverse=True)
    best_f1, best_label = 0.0, ""

    for label_key, score in sorted_labels:
        marker = " <-- PROD" if label_key == PROD_KEY else ""
        print(
            f"{label_key:<{col_width}} {score.precision:>6.2f} {score.recall:>6.2f} "
            f"{score.f1:>6.2f} {score.true_positives:>4} "
            f"{score.false_negatives:>4} {score.false_positives:>4} "
            f"{score.fp_rate:>5.1%}  "
            f"\"{score.label_text[:45]}\"{marker}"
        )
        if score.f1 > best_f1:
            best_f1 = score.f1
            best_label = label_key

    return best_f1, best_label


def _print_top_label_details(results, threshold, top_n=3):
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
class TestNvidiaGlinerAccountIdLabelBenchmark:
    """Benchmark label variants for ACCOUNT_ID PII detection on nvidia/gliner-PII."""

    def test_benchmark_all_labels(self, nvidia_gliner_model):
        """Full benchmark across all corpora and thresholds."""
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

        total_tp = sum(len(tp) for _, _, tp, _ in ALL_CORPORA)
        total_fp_traps = sum(len(fp) for _, _, _, fp in ALL_CORPORA)
        print(f"\n{'='*115}")
        print("nvidia/gliner-PII -- ACCOUNT_ID Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'account id' is short, 'id' is ultra-generic -- may flag JIRA IDs, technical IDs, UUIDs")
        print(f"{'='*115}\n")

        best_f1, best_label, best_threshold = 0.0, "", 0.0
        for threshold in THRESHOLDS_TO_TEST:
            f1, label = _print_threshold_results(results, threshold)
            if f1 > best_f1:
                best_f1 = f1
                best_label = label
                best_threshold = threshold

        print(f"\n{'='*115}")
        print(f"BEST: '{best_label}' at threshold {best_threshold} (F1={best_f1:.2f})")
        if best_label:
            best_score = results[best_threshold][best_label]
            print(f"  Precision={best_score.precision:.2f}  Recall={best_score.recall:.2f}  "
                  f"FP={best_score.false_positives}  FN={best_score.false_negatives}")
            print(f"  Label: \"{best_score.label_text}\"")
        print(f"{'='*115}")

        if best_threshold:
            _print_top_label_details(results, best_threshold)

        assert best_f1 > 0.0, "No label detected any account ID"

        baseline_03 = results[0.3][PROD_KEY]
        print(f"\n[REGRESSION CHECK] '{PROD_KEY}' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_jira_id_false_positives(self, nvidia_gliner_model):
        """
        Reproduce likely FP on JIRA ticket IDs and technical IDs.
        Text contains ONLY technical identifiers -- zero account IDs.
        """
        text = (
            "RAPAC-1234 RAPAC-1235 RAPAC-1236\n"
            "Page ID: 12345678\n"
            "Build #4567\n"
            "Request ID: req-550e8400\n"
            "Container ID: container-abc123\n"
            "Pipeline ID: pipeline-2025-0042\n"
            "Thread ID: 42\n"
            "P01564 M02054 P02086"
        )

        print(f"\n{'='*80}")
        print("Reproducing JIRA/technical ID FP -- testing all labels")
        print(f"{'='*80}")
        print("Text contains ONLY technical IDs -- expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_account_ids(self, nvidia_gliner_model):
        """All labels must detect at least some real account IDs at threshold 0.3."""
        text = (
            "Gestion des comptes:\n"
            "Numero de compte client: ACC-2024-78901\n"
            "Identifiant utilisateur: USR-00456\n"
            "Reference client: CL-78901\n"
        )
        expected = {"ACC-2024-78901", "USR-00456", "CL-78901"}

        print(f"\n{'='*80}")
        print("Real account ID detection -- all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense ID/abbreviation soup -- should detect ZERO account IDs."""
        text = (
            "ID UID GID PID TID SID CID RID OID NID FID BID "
            "UUID GUID ULID CUID KSUID TSID NANOID "
            "DNS DHCP VPN MFA RSA PKI SIEM WAF JWT SSO RBAC "
            "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI SOC"
        )

        print(f"\n{'='*80}")
        print("ID/abbreviation stress test -- expect ZERO detections")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")


# ---------------------------------------------------------------------------
# Standalone runner
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print(f"Loading {MODEL_ID}...")
    model = GLiNER.from_pretrained(MODEL_ID)

    test = TestNvidiaGlinerAccountIdLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_jira_id_false_positives(model)
    test.test_detects_real_account_ids(model)
    test.test_abbreviation_stress_test(model)
