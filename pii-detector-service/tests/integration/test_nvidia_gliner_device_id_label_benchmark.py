"""
Benchmark test: evaluate label variants for DEVICE_ID detection on nvidia/gliner-PII.

Problem anticipated: the label "device id" is short (2 words) and "id" is the most
generic identifier suffix. In technical Confluence documentation:
- Server names: srv-app-01, srv-db-02
- Container IDs: container-abc123def456
- Pod names: ai-sentinel-api-7d8f9c-xkm2p
- Hardware references: PA-5250, FAS8300, BIG-IP
- Serial numbers mentioned in inventory docs
- MAC addresses (different PII type)

Real device IDs: IMEI 353456789012345, device UUID a1b2c3d4-e5f6-7890,
Android ID: ABCDEF1234567890, iOS UDID

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_device_id_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_device_id_label_benchmark.py
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
    "device_id":
        "device id",

    # Short variants (2-3 words)
    "device_identifier":
        "device identifier",
    "hardware_device":
        "hardware device",
    "mobile_device":
        "mobile device",

    # Medium variants (3-5 words) -- usually winners on nvidia
    "hardware_device_identifier":
        "hardware device identifier",
    "mobile_device_identifier":
        "mobile device unique identifier",
    "physical_device_serial":
        "physical device serial identifier",
    "endpoint_device_identifier":
        "endpoint device tracking identifier",

    # Long descriptive variants (5+ words)
    "hardware_serial_imei":
        "hardware serial or IMEI device identifier",
    "personal_device_fingerprint":
        "personal device fingerprint identifier",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: Real device IDs in MDM/inventory context ---------------------
CORPUS_REAL_DEVICE_IDS = (
    "Inventaire des appareils mobiles (MDM):\n\n"
    "iPhone 15 Pro - Jean Dupont:\n"
    "  IMEI: 353456789012345\n"
    "  UDID: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0\n"
    "  Numero de serie: ABCD1234EFGH\n\n"
    "Samsung Galaxy S24 - Marie Martin:\n"
    "  IMEI: 867530012345678\n"
    "  Android ID: ABCDEF1234567890\n"
    "  Numero de serie: RF1AB2CD3EF\n\n"
    "MacBook Pro M3 - Pierre Bernard:\n"
    "  Serial: C02XL1YZJGH5\n"
    "  Device UUID: 550e8400-e29b-41d4-a716-446655440000"
)
CORPUS_REAL_DEVICE_IDS_TP = {
    "353456789012345",
    "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
    "ABCD1234EFGH",
    "867530012345678",
    "ABCDEF1234567890",
    "RF1AB2CD3EF",
    "C02XL1YZJGH5",
    "550e8400-e29b-41d4-a716-446655440000",
}
CORPUS_REAL_DEVICE_IDS_FP = {
    "Jean Dupont", "Marie Martin", "Pierre Bernard",
    "iPhone 15 Pro", "Samsung Galaxy S24", "MacBook Pro M3",
}

# -- Corpus 2: Device tracking in security context --------------------------
CORPUS_DEVICE_TRACKING = (
    "Rapport de securite - Appareils enregistres:\n\n"
    "Appareil 1:\n"
    "  Device ID: dev-f47ac10b-58cc-4372-a567\n"
    "  Type: Laptop\n"
    "  Derniere connexion: 2025-03-15 10:23\n\n"
    "Appareil 2:\n"
    "  Device ID: dev-7c9e6679-7425-40de-944b\n"
    "  Type: Mobile\n"
    "  Derniere connexion: 2025-03-14 18:45"
)
CORPUS_DEVICE_TRACKING_TP = {
    "dev-f47ac10b-58cc-4372-a567",
    "dev-7c9e6679-7425-40de-944b",
}
CORPUS_DEVICE_TRACKING_FP = {
    "2025-03-15", "2025-03-14",
    "Laptop", "Mobile",
}

# -- Corpus 3: Server/infrastructure inventory (PURE FP TRAP) ---------------
CORPUS_INFRA_INVENTORY = (
    "Inventaire infrastructure IT:\n\n"
    "Serveurs:\n"
    "- srv-app-01 (Application server, VMware ESXi 8.0)\n"
    "- srv-db-02 (Database server, PostgreSQL 16)\n"
    "- srv-web-03 (Web server, Nginx 1.25)\n\n"
    "Reseau:\n"
    "- Firewall: Palo Alto PA-5250\n"
    "- Switch: Cisco Catalyst 9300\n"
    "- Load Balancer: F5 BIG-IP i4800\n"
    "- Stockage: NetApp FAS8300\n\n"
    "Containers:\n"
    "- container-abc123def456 (api)\n"
    "- container-789ghi012jkl (worker)\n"
    "- Pod: ai-sentinel-api-7d8f9c-xkm2p"
)
CORPUS_INFRA_INVENTORY_TP: Set[str] = set()  # NO personal device IDs
CORPUS_INFRA_INVENTORY_FP = {
    "srv-app-01", "srv-db-02", "srv-web-03",
    "PA-5250", "FAS8300", "BIG-IP",
    "container-abc123def456", "container-789ghi012jkl",
    "ai-sentinel-api-7d8f9c-xkm2p",
    "Catalyst 9300", "i4800",
}

# -- Corpus 4: Software version/build IDs (PURE FP TRAP) --------------------
CORPUS_SOFTWARE_IDS = (
    "Versions deployees en production:\n\n"
    "- ai-sentinel-api: v2.3.1 (build #4567)\n"
    "- pii-detector-service: v1.8.0 (build #8901)\n"
    "- pii-reporting-ui: v3.1.2 (build #2345)\n\n"
    "Dependencies:\n"
    "- Spring Boot: 3.4.1\n"
    "- Angular: 19.0.3\n"
    "- PostgreSQL: 16.2\n"
    "- GLiNER: 0.2.7\n\n"
    "Commit IDs:\n"
    "- 23e2e49c (HEAD)\n"
    "- 4725448d (feature/scan)\n"
    "- 210fbf66 (fix/error)"
)
CORPUS_SOFTWARE_IDS_TP: Set[str] = set()  # NO device IDs
CORPUS_SOFTWARE_IDS_FP = {
    "v2.3.1", "v1.8.0", "v3.1.2",
    "#4567", "#8901", "#2345",
    "3.4.1", "19.0.3", "16.2", "0.2.7",
    "23e2e49c", "4725448d", "210fbf66",
}

# -- Corpus 5: Project management (PURE FP TRAP) ----------------------------
CORPUS_PROJECT_TABLE = (
    "Code projet | Nom projet | Pole | Responsable\n"
    "P01564 | RAPAC | SEJ | Ivan Gouin\n"
    "P01573 | InfoSearch | SEJ | Johnny Beuve\n"
    "P02086 | Autex | TEP | Cecile Cunit\n"
    "M02054 | I2 | SEJ | Hicham Bakir\n"
    "P01924 | SIRH | RH | Thierry Michaud"
)
CORPUS_PROJECT_TABLE_TP: Set[str] = set()
CORPUS_PROJECT_TABLE_FP = {
    "P01564", "P01573", "P02086", "M02054", "P01924",
    "SEJ", "TEP", "RH",
    "Ivan Gouin", "Hicham Bakir",
}

# -- Corpus 6: Abbreviation stress test (PURE FP TRAP) ----------------------
CORPUS_ABBREVIATION_STRESS = (
    "ID UID GID PID TID SID CID IMEI UDID UUID GUID SERIAL "
    "MAC IP DNS DHCP VPN MFA RSA PKI SIEM WAF JWT SSO RBAC "
    "VM ESXi KVM LXC OCI QEMU VBOX HV OVF AMI "
    "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI SOC"
)
CORPUS_ABBREVIATION_STRESS_TP: Set[str] = set()
CORPUS_ABBREVIATION_STRESS_FP = {
    "ID", "IMEI", "UDID", "UUID", "GUID", "SERIAL",
    "MAC", "IP", "DNS", "VM", "ESXi", "KVM",
}

ALL_CORPORA = [
    ("REAL_DEVICE_IDS",      CORPUS_REAL_DEVICE_IDS,      CORPUS_REAL_DEVICE_IDS_TP,      CORPUS_REAL_DEVICE_IDS_FP),
    ("DEVICE_TRACKING",      CORPUS_DEVICE_TRACKING,      CORPUS_DEVICE_TRACKING_TP,      CORPUS_DEVICE_TRACKING_FP),
    ("INFRA_INVENTORY",      CORPUS_INFRA_INVENTORY,      CORPUS_INFRA_INVENTORY_TP,      CORPUS_INFRA_INVENTORY_FP),
    ("SOFTWARE_IDS",         CORPUS_SOFTWARE_IDS,         CORPUS_SOFTWARE_IDS_TP,         CORPUS_SOFTWARE_IDS_FP),
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


PROD_KEY = "device_id"


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
class TestNvidiaGlinerDeviceIdLabelBenchmark:
    """Benchmark label variants for DEVICE_ID PII detection on nvidia/gliner-PII."""

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
        print("nvidia/gliner-PII -- DEVICE_ID Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'device id' is short, 'id' generic -- may flag server names, container IDs, build IDs")
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

        assert best_f1 > 0.0, "No label detected any device ID"

        baseline_03 = results[0.3][PROD_KEY]
        print(f"\n[REGRESSION CHECK] '{PROD_KEY}' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_infrastructure_false_positives(self, nvidia_gliner_model):
        """
        Reproduce likely FP on infrastructure identifiers.
        Text contains ONLY server/container/network IDs -- zero personal device IDs.
        """
        text = (
            "srv-app-01 srv-db-02 srv-web-03\n"
            "container-abc123def456\n"
            "Pod: ai-sentinel-api-7d8f9c-xkm2p\n"
            "Palo Alto PA-5250\n"
            "NetApp FAS8300\n"
            "F5 BIG-IP i4800\n"
            "Build #4567 v2.3.1\n"
            "Commit: 23e2e49c"
        )

        print(f"\n{'='*80}")
        print("Reproducing infrastructure ID FP -- testing all labels")
        print(f"{'='*80}")
        print("Text contains ONLY infra identifiers -- expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_device_ids(self, nvidia_gliner_model):
        """All labels must detect at least some real device IDs at threshold 0.3."""
        text = (
            "Appareils enregistres:\n"
            "IMEI: 353456789012345\n"
            "Android ID: ABCDEF1234567890\n"
            "Device UUID: 550e8400-e29b-41d4-a716-446655440000\n"
            "Serial: C02XL1YZJGH5\n"
        )
        expected = {
            "353456789012345",
            "ABCDEF1234567890",
            "550e8400-e29b-41d4-a716-446655440000",
            "C02XL1YZJGH5",
        }

        print(f"\n{'='*80}")
        print("Real device ID detection -- all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense abbreviation soup -- should detect ZERO device IDs."""
        text = (
            "ID UID GID PID TID SID CID IMEI UDID UUID GUID SERIAL "
            "MAC IP DNS DHCP VPN MFA RSA PKI SIEM WAF JWT SSO RBAC "
            "VM ESXi KVM LXC OCI QEMU VBOX HV OVF AMI "
            "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI SOC"
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

    test = TestNvidiaGlinerDeviceIdLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_infrastructure_false_positives(model)
    test.test_detects_real_device_ids(model)
    test.test_abbreviation_stress_test(model)
