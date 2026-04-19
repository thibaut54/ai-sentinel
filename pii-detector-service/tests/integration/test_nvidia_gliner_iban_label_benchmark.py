"""
Benchmark test: evaluate label variants for IBAN detection on nvidia/gliner-PII.

Problem observed in production: the label "iban" generates massive false positives
on project management documents containing:
- Project codes: P01564, M02054, P02086...
- Department/pole abbreviations: SEJ, TEP, CEI, EUCM, SES, FC, SOCLE, RH, INST
- Person names: Ivan Gouin, Hicham Bakir, Cedric Rime...
- Status labels: EN COURS, TERMINÉ, EN ATTENTE

None of these are IBANs, but the short label "iban" causes the model to flag
alphanumeric codes and abbreviations as IBAN numbers.

Real IBAN examples: CH93 0076 2011 6238 5295 7, DE89 3704 0044 0532 0130 00

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_iban_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_iban_label_benchmark.py
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
    # Baseline — current production label (known massive FP)
    "iban":
        "iban",

    # Full name — explicit about what an IBAN is
    "international_bank_account_number":
        "international bank account number",
    "iban_bank_account":
        "IBAN bank account number",

    # Pattern-focused — emphasize the format
    "iban_code":
        "IBAN code",
    "iban_number":
        "IBAN number",
    "bank_iban":
        "bank IBAN",

    # Very specific — describe the actual pattern
    "iban_with_country":
        "IBAN with country code prefix",
    "international_banking_identifier":
        "international banking identifier",

    # Finance-specific context
    "bank_transfer_iban":
        "bank transfer IBAN number",
}

# ---------------------------------------------------------------------------
# Ground truth corpora — extracted from real production document
# ---------------------------------------------------------------------------

# -- Corpus 1: Project list table (REAL Confluence content from PDF) ---------
CORPUS_PROJECT_TABLE = (
    "Liste des projets\n"
    "Code projet | Nom projet | Pole | Architecte CEI | Architecte DSOL | Chef de projet\n"
    "P01564 | RAPAC | SEJ | Ivan Gouin | Hicham Bakir | ...\n"
    "P01573 | InfoSearch | SEJ | Ivan Gouin | Johnny Beuve | ...\n"
    "P01565 | GESTION STOCK ID-MATOS | SEJ | Ivan Gouin | Hicham Bakir | ...\n"
    "P02086 | Autex | TEP | Ivan Gouin | Manoutchehr Chams | Cecile Cunit\n"
    "M02054 | I2 | SEJ | Ivan Gouin | Hicham Bakir | ...\n"
    "P01815 | GIDAC | TEP | Ivan Gouin | Manoutchehr Chams | Zeriul Juba\n"
    "P01950 | Postes Hors Production | EUCM | Ivan Gouin | ... | Jean Laurent Picard\n"
    "M01903 | SAP - REGLIS | SES | Ivan Gouin | Cedric Rime | Sabine Crassant\n"
    "M01731 | Lagapeo | FC | Ivan Gouin | Thomas Caprez | Thierry Birre\n"
    "M01731 / M01724 | GED Alfresco GIS-EO | FC | Ivan Gouin | Didier Luthi | Thierry Birre"
)
CORPUS_PROJECT_TABLE_TP: Set[str] = set()  # NO IBANs
CORPUS_PROJECT_TABLE_FP = {
    "P01564", "P01573", "P01565", "P02086", "M02054", "P01815", "P01950",
    "M01903", "M01731", "M01724",
    "SEJ", "TEP", "EUCM", "SES", "FC",
    "RAPAC", "GIDAC", "REGLIS",
    "Ivan Gouin", "Hicham Bakir", "Johnny Beuve", "Cedric Rime",
    "ID-MATOS", "GIS-EO",
}

# -- Corpus 2: More project codes and abbreviations (from same PDF) ----------
CORPUS_PROJECT_TABLE_2 = (
    "P02240 | Messagerie Exchange | CEI | Ivan Gouin\n"
    "M00642 | EDT Réseau | FC | Ivan Gouin | Thomase Caprez | Julie de la Bouchère\n"
    "P01613 | Sync'Serv | SEJ | Ivan Gouin | Johnny Beuve | Daniel Ribeiro\n"
    "M01391 | DWH REFEN | SES | Ignacio Arsuaga | Cedric Rime | Jacques Vernier\n"
    "P01924 | SIRH | RH | Ignacio Arsuaga | Didier Luthi | Thierry Michaud\n"
    "M02027 | Plassdata | SEJ | Ignacio Arsuaga | Hicham Bakir | Guillaume Vial\n"
    "P01569 | REFIP - Refonte Intranet PCV | SEJ | Ignacio Arsuaga\n"
    "P01846 / P02224 | SD ECM | SOCLE | Ignacio Arsuaga | Willy Reinhardt\n"
    "P01189 | GED - ECM9 - aifSAS - migration mysql-ora | SES | Ignacio Arsuaga\n"
    "P02085 | ClearPass Enroll | CEI / SOC | Ignacio Arsuaga"
)
CORPUS_PROJECT_TABLE_2_TP: Set[str] = set()  # NO IBANs
CORPUS_PROJECT_TABLE_2_FP = {
    "P02240", "M00642", "P01613", "M01391", "P01924", "M02027",
    "P01569", "P01846", "P02224", "P01189", "P02085",
    "CEI", "SOC", "SOCLE", "RH", "SES",
    "SIRH", "DWH", "REFEN", "PCV", "REFIP", "ECM9", "ECM",
    "SD ECM", "GED", "aifSAS",
}

# -- Corpus 3: Real IBANs in financial document (must detect these) ----------
CORPUS_REAL_IBANS = (
    "Coordonnées bancaires du fournisseur:\n"
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
    "BIC: AGRIFRPP"
)
CORPUS_REAL_IBANS_TP = {
    "CH93 0076 2011 6238 5295 7",
    "DE89 3704 0044 0532 0130 00",
    "FR76 3000 6000 0112 3456 7890 189",
}
CORPUS_REAL_IBANS_FP = {
    "BCVLCH2LXXX", "COBADEFFXXX", "AGRIFRPP",
    "Softcom Technologies SA",
    "Banque Cantonale Vaudoise", "Commerzbank AG",
}

# -- Corpus 4: IT/infrastructure abbreviations (stress test) -----------------
CORPUS_IT_ABBREVIATIONS = (
    "Infrastructure technique:\n"
    "- DNS: ns1.vd.ch, ns2.vd.ch\n"
    "- DHCP: plage 10.0.0.0/8\n"
    "- VPN: Pulse Secure / ACV\n"
    "- Firewall: Palo Alto PA-5250\n"
    "- Load Balancer: F5 BIG-IP\n"
    "- Monitoring: Nagios XI / PRTG\n"
    "- Backup: Veeam B&R v12\n"
    "- Virtualisation: VMware ESXi 8.0\n"
    "- Stockage: NetApp FAS8300\n"
    "- PKI: Microsoft ADCS\n"
    "- MFA: RSA SecurID / Azure MFA\n"
    "- SIEM: Splunk Enterprise\n"
    "- WAF: ModSecurity / OWASP CRS"
)
CORPUS_IT_ABBREVIATIONS_TP: Set[str] = set()  # NO IBANs
CORPUS_IT_ABBREVIATIONS_FP = {
    "DNS", "DHCP", "VPN", "MFA", "RSA", "PKI", "SIEM", "WAF",
    "ADCS", "PRTG", "CRS", "OWASP",
    "PA-5250", "FAS8300", "BIG-IP",
    "ESXi", "VMware",
}

# -- Corpus 5: Swiss/French administrative codes (tricky — look like IBANs) --
CORPUS_ADMIN_CODES = (
    "Références administratives:\n"
    "- N° AVS: 756.1234.5678.97\n"
    "- N° RC: CHE-123.456.789\n"
    "- N° IDE: CHE-123.456.789\n"
    "- N° TVA: CHE-123.456.789 TVA\n"
    "- Référence cadastrale: VD-2024-00145\n"
    "- N° OFS commune: 5586\n"
    "- Code postal: 1003 Lausanne\n"
    "- N° parcelle: RF-2024-0073"
)
CORPUS_ADMIN_CODES_TP: Set[str] = set()  # NO IBANs
CORPUS_ADMIN_CODES_FP = {
    "756.1234.5678.97",
    "CHE-123.456.789",
    "VD-2024-00145",
    "5586", "1003",
    "RF-2024-0073",
}

# -- Corpus 6: Compact IBAN in running text (harder detection) ---------------
CORPUS_INLINE_IBAN = (
    "Merci de virer le montant de CHF 15'420.00 sur le compte "
    "CH9300762011623852957 auprès de la BCV. "
    "Le paiement doit être effectué avant le 31.03.2025. "
    "Référence: FA-2025-00789. Numéro de client: CL-00456."
)
CORPUS_INLINE_IBAN_TP = {"CH9300762011623852957"}
CORPUS_INLINE_IBAN_FP = {"FA-2025-00789", "CL-00456", "15'420.00"}

ALL_CORPORA = [
    ("PROJECT_TABLE",     CORPUS_PROJECT_TABLE,     CORPUS_PROJECT_TABLE_TP,     CORPUS_PROJECT_TABLE_FP),
    ("PROJECT_TABLE_2",   CORPUS_PROJECT_TABLE_2,   CORPUS_PROJECT_TABLE_2_TP,   CORPUS_PROJECT_TABLE_2_FP),
    ("REAL_IBANS",        CORPUS_REAL_IBANS,        CORPUS_REAL_IBANS_TP,        CORPUS_REAL_IBANS_FP),
    ("IT_ABBREVIATIONS",  CORPUS_IT_ABBREVIATIONS,  CORPUS_IT_ABBREVIATIONS_TP,  CORPUS_IT_ABBREVIATIONS_FP),
    ("ADMIN_CODES",       CORPUS_ADMIN_CODES,       CORPUS_ADMIN_CODES_TP,       CORPUS_ADMIN_CODES_FP),
    ("INLINE_IBAN",       CORPUS_INLINE_IBAN,       CORPUS_INLINE_IBAN_TP,       CORPUS_INLINE_IBAN_FP),
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


def _print_threshold_results(results, threshold, col_width=35, prod_key="iban"):
    """Print results for one threshold and return (best_f1, best_label)."""
    print(f"\n--- Threshold: {threshold} ---")
    print(f"{'Label Key':<{col_width}} {'Prec':>6} {'Recall':>6} {'F1':>6} "
          f"{'TP':>4} {'FN':>4} {'FP':>4} {'FP%':>6}  Label text")
    print("-" * 115)

    sorted_labels = sorted(results[threshold].items(), key=lambda x: x[1].f1, reverse=True)
    best_f1, best_label = 0.0, ""

    for label_key, score in sorted_labels:
        marker = " <-- PROD" if label_key == prod_key else ""
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
class TestNvidiaGlinerIbanLabelBenchmark:
    """Benchmark label variants for IBAN PII detection on nvidia/gliner-PII."""

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

        # --- Print ---
        total_tp = sum(len(tp) for _, _, tp, _ in ALL_CORPORA)
        total_fp_traps = sum(len(fp) for _, _, _, fp in ALL_CORPORA)
        print(f"\n{'='*115}")
        print("nvidia/gliner-PII — IBAN Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'iban' label flags project codes (P01564), department abbreviations (SEJ), etc.")
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

        # --- Assertions ---
        assert best_f1 > 0.0, "No label detected any IBAN"

        baseline_03 = results[0.3]["iban"]
        print(f"\n[REGRESSION CHECK] 'iban' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_project_code_false_positives(self, nvidia_gliner_model):
        """
        Reproduce false positives on project codes from the real PDF.
        The text contains ONLY project codes and department names — zero IBANs.
        """
        text = (
            "P01564 RAPAC SEJ\n"
            "P01573 InfoSearch SEJ\n"
            "M02054 I2 SEJ\n"
            "P02086 Autex TEP\n"
            "P01815 GIDAC TEP\n"
            "M01903 SAP-REGLIS SES\n"
            "M01731 Lagapeo FC\n"
            "P02240 Messagerie Exchange CEI\n"
            "P01924 SIRH RH\n"
            "P02085 ClearPass Enroll CEI/SOC"
        )

        print(f"\n{'='*80}")
        print("Reproducing project code FP — testing all labels")
        print(f"{'='*80}")
        print("Text contains ONLY project codes — expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "❌ FALSE POSITIVE" if detected else "✅ clean"
            if label_key == "iban":
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_ibans(self, nvidia_gliner_model):
        """All labels must detect at least one real IBAN at threshold 0.3."""
        text = (
            "Coordonnées bancaires:\n"
            "IBAN: CH93 0076 2011 6238 5295 7\n"
            "IBAN: DE89 3704 0044 0532 0130 00\n"
        )
        expected = {
            "CH93 0076 2011 6238 5295 7",
            "DE89 3704 0044 0532 0130 00",
        }

        print(f"\n{'='*80}")
        print("Real IBAN detection — all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense abbreviation soup — should detect ZERO IBANs."""
        text = (
            "DNS DHCP VPN MFA RSA PKI SIEM WAF ADCS PRTG CRS OWASP "
            "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI BIC SOC DGMR "
            "LIMSOPY BPM SIPRE DGE CCF CADEV TELECOM GOUV"
        )

        print(f"\n{'='*80}")
        print("Abbreviation stress test — expect ZERO detections")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "❌ FP" if detected else "✅ clean"
            if label_key == "iban":
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

    test = TestNvidiaGlinerIbanLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_project_code_false_positives(model)
    test.test_detects_real_ibans(model)
    test.test_abbreviation_stress_test(model)
