"""
Benchmark test: evaluate label variants for BIC_SWIFT detection on nvidia/gliner-PII.

Problem anticipated: the label "swift code" contains "code" which is known to cause
false positives on any alphanumeric code pattern. This is the same root cause as the
old "iban" label that flagged project codes (P01564) and department abbreviations (SEJ).

Expected false positives with "swift code":
- Project codes: P01564, M02054, P02086
- Department/pole codes: SEJ, TEP, CEI, EUCM
- Technical codes: HTTP 200, ISO 27001, RFC 7519
- Version strings: v2.3.1, 8.0.1
- Reference codes: FA-2025-00789, VD-2024-00145

Real BIC/SWIFT examples: BCVLCH2LXXX, COBADEFFXXX, AGRIFRPP, UBSWCHZH80A

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_bic_swift_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_bic_swift_label_benchmark.py
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
    "swift_code":
        "swift code",

    # Short variants (2-3 words)
    "bic_swift":
        "BIC SWIFT",
    "swift_identifier":
        "SWIFT identifier",
    "bank_swift":
        "bank SWIFT",

    # Medium variants (3-5 words) -- usually winners on nvidia
    "swift_banking_identifier":
        "SWIFT banking identifier",
    "bank_routing_swift":
        "bank routing SWIFT identifier",
    "interbank_swift_identifier":
        "interbank SWIFT identifier",
    "swift_bank_routing":
        "SWIFT bank routing identifier",

    # Long descriptive variants (5+ words)
    "international_bank_swift":
        "international bank SWIFT routing identifier",
    "swift_financial_institution":
        "SWIFT financial institution identifier",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: Real BIC/SWIFT codes in financial documents --------------------
CORPUS_REAL_SWIFT = (
    "Coordonnees bancaires du fournisseur:\n"
    "Nom: Softcom Technologies SA\n"
    "IBAN: CH93 0076 2011 6238 5295 7\n"
    "BIC/SWIFT: BCVLCH2LXXX\n"
    "Banque: Banque Cantonale Vaudoise\n\n"
    "Pour le paiement en EUR, utiliser:\n"
    "IBAN: DE89 3704 0044 0532 0130 00\n"
    "BIC: COBADEFFXXX\n"
    "Banque: Commerzbank AG\n\n"
    "Paiement alternatif:\n"
    "IBAN: FR76 3000 6000 0112 3456 7890 189\n"
    "BIC: AGRIFRPP\n"
    "Banque: Credit Agricole"
)
CORPUS_REAL_SWIFT_TP = {
    "BCVLCH2LXXX",
    "COBADEFFXXX",
    "AGRIFRPP",
}
CORPUS_REAL_SWIFT_FP = {
    "CH93 0076 2011 6238 5295 7",
    "DE89 3704 0044 0532 0130 00",
    "FR76 3000 6000 0112 3456 7890 189",
    "Softcom Technologies SA",
    "Banque Cantonale Vaudoise",
    "Commerzbank AG",
    "Credit Agricole",
}

# -- Corpus 2: More SWIFT codes in wire transfer instructions -----------------
CORPUS_WIRE_TRANSFER = (
    "Instructions de virement international:\n"
    "Beneficiaire: Dupont & Fils SARL\n"
    "Banque: UBS Switzerland AG\n"
    "SWIFT/BIC: UBSWCHZH80A\n"
    "Compte: 0230-123456.01\n\n"
    "Banque intermediaire:\n"
    "SWIFT: CHASUS33\n"
    "JPMorgan Chase, New York\n\n"
    "Frais: SHA (partages)\n"
    "Montant: EUR 45'200.00"
)
CORPUS_WIRE_TRANSFER_TP = {
    "UBSWCHZH80A",
    "CHASUS33",
}
CORPUS_WIRE_TRANSFER_FP = {
    "Dupont & Fils SARL",
    "0230-123456.01",
    "45'200.00",
    "JPMorgan Chase",
    "UBS Switzerland AG",
}

# -- Corpus 3: Project management table (PURE FP TRAP -- zero SWIFT) ---------
CORPUS_PROJECT_CODES = (
    "Code projet | Nom projet | Pole | Chef de projet\n"
    "P01564 | RAPAC | SEJ | Ivan Gouin\n"
    "P01573 | InfoSearch | SEJ | Johnny Beuve\n"
    "P02086 | Autex | TEP | Cecile Cunit\n"
    "M02054 | I2 | SEJ | Hicham Bakir\n"
    "P01815 | GIDAC | TEP | Zeriul Juba\n"
    "P01924 | SIRH | RH | Thierry Michaud\n"
    "P02240 | Messagerie Exchange | CEI | Willy Reinhardt\n"
    "P02085 | ClearPass Enroll | CEI/SOC | Ignacio Arsuaga"
)
CORPUS_PROJECT_CODES_TP: Set[str] = set()  # NO SWIFT codes
CORPUS_PROJECT_CODES_FP = {
    "P01564", "P01573", "P02086", "M02054", "P01815", "P01924", "P02240", "P02085",
    "SEJ", "TEP", "CEI", "SOC", "RH",
    "RAPAC", "GIDAC", "SIRH",
    "Ivan Gouin", "Hicham Bakir", "Johnny Beuve",
}

# -- Corpus 4: Technical documentation with codes (PURE FP TRAP) -------------
CORPUS_TECHNICAL_CODES = (
    "Architecture technique - Standards:\n"
    "- HTTP status codes: 200, 301, 404, 500\n"
    "- Norme ISO 27001:2022 applicable\n"
    "- Protocole OAuth 2.0 / RFC 7519 (JWT)\n"
    "- Version API: v2.3.1\n"
    "- Build: 8.0.1-SNAPSHOT\n"
    "- Java code coverage: 87.3%\n"
    "- SonarQube quality gate: PASSED\n"
    "- Error code: ERR-4001-AUTH\n"
    "- Reference: FA-2025-00789\n"
    "- Code postal: 1003 Lausanne\n"
    "- Code pays: CH, DE, FR, AT\n"
    "- Code devise: CHF, EUR, USD"
)
CORPUS_TECHNICAL_CODES_TP: Set[str] = set()  # NO SWIFT codes
CORPUS_TECHNICAL_CODES_FP = {
    "200", "301", "404", "500",
    "ISO 27001", "RFC 7519",
    "v2.3.1", "8.0.1", "8.0.1-SNAPSHOT",
    "ERR-4001-AUTH", "FA-2025-00789",
    "1003", "CH", "DE", "FR", "AT",
    "CHF", "EUR", "USD",
    "87.3%", "PASSED",
}

# -- Corpus 5: Abbreviation stress test (PURE FP TRAP) -----------------------
CORPUS_ABBREVIATION_STRESS = (
    "DNS DHCP VPN MFA RSA PKI SIEM WAF ADCS PRTG CRS OWASP "
    "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI BIC SOC DGMR "
    "HTTP HTTPS TLS SSL SSH FTP SFTP SMTP POP IMAP LDAP "
    "JWT OAuth SAML SSO MFA RBAC ACL CORS CSRF XSS SQL "
    "AWS GCP AZ VM LB CDN WAF ALB NLB ELB S3 EC2 RDS SQS SNS "
    "LIMSOPY BPM SIPRE DGE CCF CADEV TELECOM GOUV ERR REF CODE"
)
CORPUS_ABBREVIATION_STRESS_TP: Set[str] = set()  # NO SWIFT codes
CORPUS_ABBREVIATION_STRESS_FP = {
    "DNS", "DHCP", "VPN", "MFA", "RSA", "PKI", "SIEM", "WAF",
    "BIC", "SOC", "HTTP", "HTTPS", "TLS", "SSL", "SSH",
    "JWT", "SSO", "RBAC", "AWS", "GCP",
    "ERR", "REF", "CODE",
}

# -- Corpus 6: Mixed content with inline SWIFT (harder detection) ------------
CORPUS_INLINE_SWIFT = (
    "Pour le reglement de la facture FA-2025-00345, merci d'effectuer "
    "un virement via le code SWIFT PABORSP1XXX de la banque Banco de Portugal. "
    "Le delai de traitement est de 2-3 jours ouvrables. "
    "Reference interne: REF-2025-SWIFT-001. Code comptable: 6100."
)
CORPUS_INLINE_SWIFT_TP = {"PABORSP1XXX"}
CORPUS_INLINE_SWIFT_FP = {
    "FA-2025-00345", "REF-2025-SWIFT-001", "6100",
    "Banco de Portugal",
}

ALL_CORPORA = [
    ("REAL_SWIFT",           CORPUS_REAL_SWIFT,           CORPUS_REAL_SWIFT_TP,           CORPUS_REAL_SWIFT_FP),
    ("WIRE_TRANSFER",        CORPUS_WIRE_TRANSFER,        CORPUS_WIRE_TRANSFER_TP,        CORPUS_WIRE_TRANSFER_FP),
    ("PROJECT_CODES",        CORPUS_PROJECT_CODES,        CORPUS_PROJECT_CODES_TP,        CORPUS_PROJECT_CODES_FP),
    ("TECHNICAL_CODES",      CORPUS_TECHNICAL_CODES,      CORPUS_TECHNICAL_CODES_TP,      CORPUS_TECHNICAL_CODES_FP),
    ("ABBREVIATION_STRESS",  CORPUS_ABBREVIATION_STRESS,  CORPUS_ABBREVIATION_STRESS_TP,  CORPUS_ABBREVIATION_STRESS_FP),
    ("INLINE_SWIFT",         CORPUS_INLINE_SWIFT,         CORPUS_INLINE_SWIFT_TP,         CORPUS_INLINE_SWIFT_FP),
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


PROD_KEY = "swift_code"


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
class TestNvidiaGlinerBicSwiftLabelBenchmark:
    """Benchmark label variants for BIC/SWIFT PII detection on nvidia/gliner-PII."""

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
        print("nvidia/gliner-PII -- BIC/SWIFT Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'swift code' contains 'code' -- may flag project codes, technical codes, etc.")
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

        assert best_f1 > 0.0, "No label detected any SWIFT code"

        baseline_03 = results[0.3][PROD_KEY]
        print(f"\n[REGRESSION CHECK] '{PROD_KEY}' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_technical_code_false_positives(self, nvidia_gliner_model):
        """
        Reproduce likely FP on technical/project codes.
        Text contains ONLY codes and abbreviations -- zero SWIFT/BIC codes.
        """
        text = (
            "Code projet: P01564 RAPAC SEJ\n"
            "Code erreur: ERR-4001-AUTH\n"
            "Code postal: 1003 Lausanne\n"
            "Code pays: CH, DE, FR\n"
            "Code devise: CHF, EUR, USD\n"
            "HTTP status code: 200, 404, 500\n"
            "Norme ISO 27001:2022\n"
            "Version: v2.3.1\n"
            "Build code: 8.0.1-SNAPSHOT"
        )

        print(f"\n{'='*80}")
        print("Reproducing technical code FP -- testing all labels")
        print(f"{'='*80}")
        print("Text contains ONLY technical codes -- expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_swift_codes(self, nvidia_gliner_model):
        """All labels must detect at least some real SWIFT codes at threshold 0.3."""
        text = (
            "Coordonnees bancaires:\n"
            "BIC/SWIFT: BCVLCH2LXXX (Banque Cantonale Vaudoise)\n"
            "SWIFT: COBADEFFXXX (Commerzbank AG)\n"
            "BIC: UBSWCHZH80A (UBS Switzerland)\n"
        )
        expected = {"BCVLCH2LXXX", "COBADEFFXXX", "UBSWCHZH80A"}

        print(f"\n{'='*80}")
        print("Real SWIFT code detection -- all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense abbreviation soup -- should detect ZERO SWIFT codes."""
        text = (
            "DNS DHCP VPN MFA RSA PKI SIEM WAF ADCS PRTG CRS OWASP "
            "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI BIC SOC DGMR "
            "HTTP HTTPS TLS SSL SSH FTP SFTP SMTP POP IMAP LDAP "
            "JWT OAuth SAML SSO MFA RBAC ACL CORS CSRF XSS SQL "
            "ERR REF CODE ISO RFC URI OID MAC TCP UDP VLAN MTU"
        )

        print(f"\n{'='*80}")
        print("Abbreviation stress test -- expect ZERO detections")
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

    test = TestNvidiaGlinerBicSwiftLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_technical_code_false_positives(model)
    test.test_detects_real_swift_codes(model)
    test.test_abbreviation_stress_test(model)
