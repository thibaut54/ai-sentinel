"""
Comprehensive benchmark: evaluate ALL remaining GLiNER label variants on nvidia/gliner-PII.

Tests 21 labels that haven't been optimized yet. Loads model ONCE for efficiency.

Labels already optimized (excluded):
- PASSWORD: "account password or PIN code"
- IBAN: "international banking identifier"
- USERNAME: "system account name"
- BIC_SWIFT: "swift code" (kept)
- API_KEY: "API authentication credential" (changed)
- ACCESS_TOKEN: "access token" (kept)
- ACCOUNT_ID: "customer account" (changed)
- SESSION_ID: "web session" (changed)
- DEVICE_ID: "mobile device unique identifier" (changed)

Usage:
    python tests/integration/test_nvidia_gliner_all_remaining_labels_benchmark.py
    python -m pytest tests/integration/test_nvidia_gliner_all_remaining_labels_benchmark.py -v -s
"""

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from gliner import GLiNER

MODEL_ID = "nvidia/gliner-PII"

# ---------------------------------------------------------------------------
# Label definitions: current label + proposed variants
# ---------------------------------------------------------------------------
LABELS_TO_BENCHMARK: Dict[str, Dict[str, str]] = {
    # ===== IDENTITY =====
    "NATIONAL_ID": {
        "baseline": "national identity number",
        "national_id_card": "national identity card number",
        "government_id": "government-issued identity number",
        "citizen_id": "citizen identification number",
    },
    "SSN": {
        "baseline": "social security number",
        "social_insurance": "social insurance number",
        "social_security_id": "social security identification",
        "government_social_id": "government social security identifier",
    },
    "PASSPORT_NUMBER": {
        "baseline": "passport number",
        "travel_passport": "travel passport document number",
        "passport_id": "passport identification number",
        "international_passport": "international travel passport number",
    },
    "DRIVER_LICENSE_NUMBER": {
        "baseline": "driver license number",
        "driving_permit": "driving permit number",
        "driver_license_id": "driver license identification",
        "motor_vehicle_license": "motor vehicle driver license number",
    },
    # ===== FINANCIAL =====
    "CREDIT_CARD_NUMBER": {
        "baseline": "credit card number",
        "payment_card": "payment card number",
        "credit_debit_card": "credit or debit card number",
        "bank_card": "bank payment card number",
    },
    "BANK_ACCOUNT_NUMBER": {
        "baseline": "bank account number",
        "banking_account": "banking account number",
        "financial_account": "financial institution account number",
        "deposit_account": "bank deposit account number",
    },
    "TAX_ID": {
        "baseline": "tax identification number",
        "tax_id": "tax identifier",
        "taxpayer_id": "taxpayer identification number",
        "fiscal_id": "fiscal identification number",
    },
    # ===== MEDICAL (AVS_NUMBER is CRITICAL) =====
    "AVS_NUMBER": {
        "baseline": "avs number",
        "swiss_social_insurance": "Swiss social insurance number",
        "ahv_avs_number": "AHV/AVS social insurance number",
        "swiss_ahv": "Swiss AHV number",
        "social_insurance_13digit": "13-digit Swiss social insurance identifier",
        "swiss_avs_13": "Swiss AVS 13-digit personal number",
    },
    "PATIENT_ID": {
        "baseline": "patient id",
        "hospital_patient": "hospital patient identifier",
        "medical_patient": "medical patient record identifier",
        "patient_record": "patient medical record number",
    },
    "MEDICAL_RECORD_NUMBER": {
        "baseline": "medical record number",
        "medical_file": "medical file number",
        "health_record": "health record identifier",
        "clinical_record": "clinical medical record number",
    },
    "HEALTH_INSURANCE_NUMBER": {
        "baseline": "health insurance number",
        "health_policy": "health insurance policy number",
        "medical_insurance": "medical insurance member number",
        "health_coverage": "health coverage identification number",
    },
    "DIAGNOSIS": {
        "baseline": "medical diagnosis",
        "clinical_diagnosis": "clinical diagnosis",
        "medical_condition": "medical condition or diagnosis",
        "disease_diagnosis": "disease or medical diagnosis",
    },
    "MEDICATION": {
        "baseline": "medication name",
        "prescription_drug": "prescription medication name",
        "pharmaceutical": "pharmaceutical drug name",
        "prescribed_medicine": "prescribed medicine name",
    },
    # ===== IT (IP_ADDRESS + MAC_ADDRESS) =====
    "MAC_ADDRESS": {
        "baseline": "mac address",
        "network_mac": "network MAC hardware address",
        "hardware_mac": "hardware MAC address identifier",
        "ethernet_mac": "ethernet MAC address",
    },
    "IP_ADDRESS": {
        "baseline": "ip address",
        "network_ip": "network IP address",
        "internet_protocol": "Internet Protocol address",
        "ipv4_ipv6": "IPv4 or IPv6 network address",
    },
    # ===== LEGAL_ASSET =====
    "CASE_NUMBER": {
        "baseline": "case number",
        "legal_case": "legal case file number",
        "court_case": "court case reference number",
        "judicial_case": "judicial case docket number",
    },
    "LICENSE_NUMBER": {
        "baseline": "license number",
        "professional_license": "professional license number",
        "business_license": "business or professional license number",
        "regulatory_license": "regulatory license identifier",
    },
    "CRIMINAL_RECORD": {
        "baseline": "criminal record",
        "criminal_history": "criminal history record",
        "criminal_background": "criminal background record",
        "police_record": "police criminal record",
    },
    "VEHICLE_REGISTRATION": {
        "baseline": "vehicle registration number",
        "car_registration": "car registration number",
        "vehicle_plate_reg": "vehicle registration plate number",
        "motor_vehicle_reg": "motor vehicle registration identifier",
    },
    "LICENSE_PLATE": {
        "baseline": "license plate number",
        "vehicle_plate": "vehicle license plate",
        "car_plate": "car license plate number",
        "registration_plate": "registration plate number",
    },
    "VIN": {
        "baseline": "vehicle identification number",
        "vin_number": "VIN vehicle identification",
        "chassis_number": "vehicle chassis identification number",
        "vehicle_serial": "vehicle serial identification number",
    },
    "INSURANCE_POLICY_NUMBER": {
        "baseline": "insurance policy number",
        "insurance_contract": "insurance contract number",
        "policy_id": "insurance policy identifier",
        "insurance_certificate": "insurance certificate number",
    },
}

# ---------------------------------------------------------------------------
# Test corpora — realistic Swiss/EU administrative documents
# ---------------------------------------------------------------------------

# --- TRUE POSITIVE corpora (should be detected) ---

TP_CORPORA = {
    "NATIONAL_ID": [
        "Le numero d'identite nationale du citoyen est 1234567890123.",
        "National ID card: 85 1234 5678 9, delivered in Paris.",
        "Carte d'identite numero: AB-123456, valable jusqu'au 31.12.2027.",
    ],
    "SSN": [
        "Son numero de securite sociale est 1 85 12 75 108 012 45.",
        "Social Security Number: 078-05-1120.",
        "Le numero SS du collaborateur: 2 93 06 69 123 456 78.",
    ],
    "PASSPORT_NUMBER": [
        "Passeport numero: C3M1T5KR7, expire le 15.06.2028.",
        "Passport number: 12AB34567, issued by Switzerland.",
        "Le passeport du voyageur porte le numero X1234567.",
    ],
    "DRIVER_LICENSE_NUMBER": [
        "Permis de conduire numero: 12345678901, categorie B.",
        "Driver's license: DL-2024-789456, valid until 2030.",
        "Son permis numero F1234567 a ete delivre le 01.03.2020.",
    ],
    "CREDIT_CARD_NUMBER": [
        "Carte de credit: 4532 1234 5678 9012, expiration 12/26.",
        "Numero de carte Visa: 4916-3389-2145-7801.",
        "Master Card 5425 2334 1098 7654, CVV 321.",
    ],
    "BANK_ACCOUNT_NUMBER": [
        "Compte bancaire: 12345-67890-12345678901-23.",
        "Bank account number: 001-234567-89.",
        "Numero de compte: CH-12345.67890.1.",
    ],
    "TAX_ID": [
        "Numero fiscal: CHE-123.456.789 TVA.",
        "Tax ID: 12-3456789, filed with the IRS.",
        "Numero d'identification fiscale: FR 12 345678901.",
    ],
    "AVS_NUMBER": [
        "Numero AVS: 756.1234.5678.97.",
        "Son numero AHV/AVS est 756.9876.5432.10.",
        "Le numero d'assurance sociale est 756.1111.2222.33.",
    ],
    "PATIENT_ID": [
        "Patient ID: PAT-2024-78901, service de cardiologie.",
        "Dossier patient numero: 12345678, Dr. Muller.",
        "Le patient identifie sous MRN-456789 est admis.",
    ],
    "MEDICAL_RECORD_NUMBER": [
        "Dossier medical numero: MED-2024-123456.",
        "Medical record #789012 contains radiology results.",
        "Numero de dossier clinique: DC-2024-0045.",
    ],
    "HEALTH_INSURANCE_NUMBER": [
        "Numero d'assurance maladie: 80756012345678.",
        "Health insurance card: 1234567890.",
        "Carte d'assure: 756.1234.5678.97 (CSS).",
    ],
    "DIAGNOSIS": [
        "Diagnostic: diabete de type 2, HbA1c = 7.8%.",
        "Le patient presente un carcinome pulmonaire stade III.",
        "Diagnosis: major depressive disorder, recurrent episode.",
    ],
    "MEDICATION": [
        "Prescription: Metformine 850mg, 2x/jour.",
        "Le patient prend du Lisinopril 10mg et de l'Aspirine 100mg.",
        "Medication: Amoxicilline 500mg trois fois par jour pendant 7 jours.",
    ],
    "MAC_ADDRESS": [
        "MAC address of the server: 00:1A:2B:3C:4D:5E.",
        "L'adresse MAC du poste: AA-BB-CC-DD-EE-FF.",
        "Device MAC: 01:23:45:67:89:AB, connected to VLAN 42.",
    ],
    "IP_ADDRESS": [
        "Adresse IP du serveur: 192.168.1.100.",
        "The server responds at 10.0.0.1 on port 443.",
        "IPv6 address: 2001:0db8:85a3::8a2e:0370:7334.",
    ],
    "CASE_NUMBER": [
        "Dossier judiciaire numero: TC/2024/12345.",
        "Case number: 2:24-cv-01234-ABC.",
        "Affaire numero 600/2024/456 du Tribunal cantonal.",
    ],
    "LICENSE_NUMBER": [
        "Numero d'autorisation professionnelle: LP-2024-5678.",
        "Professional license: MD-12345, State of California.",
        "Licence d'exploitation numero: LE-VD-2024-0089.",
    ],
    "CRIMINAL_RECORD": [
        "Extrait du casier judiciaire: aucune condamnation inscrite.",
        "Criminal record check: no convictions found for applicant.",
        "Le casier judiciaire du candidat est vierge.",
    ],
    "VEHICLE_REGISTRATION": [
        "Certificat d'immatriculation: VD 123456.",
        "Vehicle registration: ZH-789012, Toyota Corolla 2023.",
        "Numero d'immatriculation: GE 456789.",
    ],
    "LICENSE_PLATE": [
        "Plaque d'immatriculation: VD 345 678.",
        "License plate: ZH 12345, blue sedan.",
        "La voiture immatriculee GE 67890 est signalee.",
    ],
    "VIN": [
        "Numero de chassis: WVWZZZ3CZWE123456.",
        "VIN: 1HGCM82633A123456.",
        "Vehicle identification: SALGA2BF3FA123456.",
    ],
    "INSURANCE_POLICY_NUMBER": [
        "Police d'assurance numero: PA-2024-123456.",
        "Insurance policy: POL-RC-789012, Zurich Assurances.",
        "Contrat d'assurance: 12.345.678-9, valable des 01.01.2024.",
    ],
}

# --- FALSE POSITIVE corpora (should NOT be detected) ---

FP_CORPUS_ADMIN = (
    "Rapport de gestion 2024 - Departement de la sante et de l'action sociale (DSAS)\n\n"
    "1. Periodes occasionnelles d'etablissement - SESAF\n"
    "Des 2016-2017, il sera possible de mettre en reserve des POE SESAF (C1 et C2) "
    "via l'ecran des allocations.\n"
    "2. Allocations - SESAF\n"
    "Des 2016-2017, le SESAF fournira les periodes de soutien hors-enveloppe via des allocations.\n"
    "Vous aurez l'obligation de lier les allocations aux differentes periodes de soutien SESAF "
    "de la prestation.\n"
    "Les allocations devront etre liees a l'allocation SESAF relative.\n"
    "Le suivi de la consommation des allocations SESAF sera disponible dans l'ecran 'Allocations'.\n"
    "Ici, par exemple, cet etablissement surconsomme une allocation SESAF de 7 periodes.\n\n"
    "Budget total DSAS: CHF 2.8 milliards. ETP: 450.\n"
    "Contact: info@dsas.vd.ch, T. 021 316 50 00.\n"
)

FP_CORPUS_TECH_DOC = (
    "Architecture technique AI Sentinel v2.3\n\n"
    "1. Vue d'ensemble\n"
    "1.1 Composants principaux\n"
    "1.1.1 Backend API (Spring Boot 3.2)\n"
    "1.1.2 Frontend (Angular 19)\n"
    "1.2 Infrastructure\n"
    "1.2.1 Serveurs de production\n"
    "1.2.1.1 Cluster Kubernetes\n"
    "1.2.1.2 Base de donnees PostgreSQL\n"
    "1.2.1.3 Cache Redis\n"
    "1.2.2 Monitoring\n"
    "1.3 Securite\n"
    "1.3.1 Authentification OAuth2\n"
    "1.3.2 Chiffrement TLS 1.3\n\n"
    "Version: 2.3.1 | Build: 20240315-001 | SHA: 4725448d\n"
    "RFC 7519 (JWT), ISO 27001, NIST 800-53\n"
    "HTTP 200/201/400/401/403/404/500\n"
    "API endpoints: /api/v2/scans, /api/v2/reports\n"
)

FP_CORPUS_PROJECT = (
    "Tableau de bord des projets - Canton de Vaud\n\n"
    "| Projet | Chef de projet | Budget (CHF) | Statut |\n"
    "| P01564 | M. Dupont | 250'000 | En cours |\n"
    "| M02054 | Mme Rochat | 180'000 | Termine |\n"
    "| P02086 | M. Favre | 320'000 | En cours |\n"
    "| P01573 | Mme Weber | 95'000 | Planifie |\n\n"
    "Poles: SEJ, TEP, CEI, EUCM, SPJ, DGEO\n"
    "Services: SESAF, SASH, SSP, SPAS, DGCS, OAI, OFAS\n"
    "Acronymes: LAGAPEO, LHPS, LStup, LAIH, LEPM, LPPS\n"
    "Comites: COPIL, CODIR, GT, GTSI, CCSI\n"
)

FP_CORPUS_LEGAL = (
    "Resume des activites juridiques - Service juridique et legislatif\n\n"
    "Articles de loi cites:\n"
    "Art. 12 al. 3 LPPS, Art. 45 LPers, Art. 7 RLPERS\n"
    "Loi federale sur les stupéfiants (LStup), RS 812.121\n"
    "Ordonnance sur l'assurance-maladie (OAMal), RS 832.102\n"
    "Code civil suisse (CC), RS 210, art. 307-317\n\n"
    "Arrets du Tribunal federal:\n"
    "ATF 148 II 233, consid. 4.2\n"
    "ATF 147 IV 145, consid. 3.1\n\n"
    "References internes:\n"
    "Note SASH/2024/045, Note DGCS/2024/012\n"
    "Decision CD/2024/789, Avis SJL/2024/056\n"
)

FP_CORPUS_MEDICAL_CONTEXT = (
    "Rapport annuel du Service de la sante publique (SSP) 2024\n\n"
    "Thematiques principales:\n"
    "- Prevention du diabete: programme cantonal 'Ca marche!'\n"
    "- Vaccination grippe: 45% de couverture atteint\n"
    "- Sante mentale: renforcement du dispositif ambulatoire\n"
    "- Lutte contre le cancer: depistage organise du sein et du colon\n"
    "- Medicaments: controle des prix et generiques encourages\n\n"
    "Indicateurs:\n"
    "- Taux d'hospitalisation: 12.3 pour 1000 habitants\n"
    "- Duree moyenne de sejour: 5.2 jours\n"
    "- Consultations ambulatoires: +8% vs 2023\n"
    "- Budget SSP: CHF 145 millions\n"
)

FP_CORPUS_VEHICLE = (
    "Parc automobile du canton - Inventaire 2024\n\n"
    "Categories de vehicules:\n"
    "- Berlines: 45 unites (Toyota, Skoda, VW)\n"
    "- Utilitaires: 23 unites (Renault Master, VW Transporter)\n"
    "- Electriques: 12 unites (Tesla Model 3, VW ID.4)\n\n"
    "Normes et standards:\n"
    "- Euro 6d-TEMP minimum pour nouveaux achats\n"
    "- Norme SN 640 850 pour signalisation\n"
    "- Catalogue OFS: codes 10.21, 29.10, 45.11\n"
    "- Protocole OBD-II pour diagnostics\n"
)

ALL_FP_CORPORA = {
    "ADMIN": FP_CORPUS_ADMIN,
    "TECH_DOC": FP_CORPUS_TECH_DOC,
    "PROJECT": FP_CORPUS_PROJECT,
    "LEGAL": FP_CORPUS_LEGAL,
    "MEDICAL_CONTEXT": FP_CORPUS_MEDICAL_CONTEXT,
    "VEHICLE": FP_CORPUS_VEHICLE,
}


@dataclass
class LabelResult:
    pii_type: str
    label_key: str
    label_text: str
    threshold: float
    tp: int = 0
    fn: int = 0
    fp: int = 0
    total_fp_traps: int = 0
    tp_details: List[str] = field(default_factory=list)
    fp_details: List[str] = field(default_factory=list)
    fn_details: List[str] = field(default_factory=list)

    @property
    def precision(self) -> float:
        return self.tp / (self.tp + self.fp) if (self.tp + self.fp) > 0 else 0.0

    @property
    def recall(self) -> float:
        return self.tp / (self.tp + self.fn) if (self.tp + self.fn) > 0 else 0.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 2 * p * r / (p + r) if (p + r) > 0 else 0.0

    @property
    def fp_pct(self) -> float:
        return (self.fp / self.total_fp_traps * 100) if self.total_fp_traps > 0 else 0.0


@pytest.fixture(scope="module")
def model():
    print(f"\n[SETUP] Loading {MODEL_ID} ...")
    m = GLiNER.from_pretrained(MODEL_ID)
    print(f"[SETUP] {MODEL_ID} loaded.")
    return m


def _count_fp_tokens(corpus: str) -> int:
    """Rough estimate of distinct FP-trap tokens in a corpus."""
    return max(1, len(corpus.split()) // 10)


def _run_label(
    model: GLiNER,
    pii_type: str,
    label_text: str,
    tp_texts: List[str],
    fp_corpora: Dict[str, str],
    threshold: float,
) -> LabelResult:
    """Run a single label against TP and FP corpora."""
    result = LabelResult(
        pii_type=pii_type,
        label_key="",
        label_text=label_text,
        threshold=threshold,
    )

    # Count total TP expected (1 per TP text)
    total_tp = len(tp_texts)

    # Test true positives
    tp_found = 0
    for text in tp_texts:
        entities = model.predict_entities(text, [label_text], threshold=threshold)
        if entities:
            tp_found += 1
            result.tp_details.append(f"HIT: {entities[0]['text'][:50]}")
        else:
            result.fn_details.append(f"MISS: {text[:60]}")

    result.tp = tp_found
    result.fn = total_tp - tp_found

    # Test false positives
    total_fp_traps = 0
    for corpus_name, corpus_text in fp_corpora.items():
        trap_count = _count_fp_tokens(corpus_text)
        total_fp_traps += trap_count
        entities = model.predict_entities(corpus_text, [label_text], threshold=threshold)
        for ent in entities:
            result.fp += 1
            result.fp_details.append(f"FP[{corpus_name}]: '{ent['text'][:40]}' ({ent['score']:.2f})")

    result.total_fp_traps = total_fp_traps
    return result


class TestNvidiaGlinerAllRemainingLabels:
    """Benchmark all 21 remaining GLiNER labels."""

    def test_benchmark_all_labels(self, model):
        """Main benchmark: test all labels with all variants at multiple thresholds."""
        thresholds = [0.3, 0.5, 0.7, 0.8, 0.9]

        print("\n" + "=" * 120)
        print("nvidia/gliner-PII -- COMPREHENSIVE REMAINING LABELS BENCHMARK")
        print("=" * 120)
        print(f"Model: {MODEL_ID}")
        print(f"PII types: {len(LABELS_TO_BENCHMARK)} | Thresholds: {thresholds}")
        print("=" * 120)

        recommendations = {}
        for pii_type, variants in LABELS_TO_BENCHMARK.items():
            rec = self._benchmark_pii_type(model, pii_type, variants, thresholds)
            if rec:
                recommendations[pii_type] = rec

        self._print_final_summary(recommendations)

    def _benchmark_pii_type(self, model, pii_type, variants, thresholds):
        tp_texts = TP_CORPORA.get(pii_type, [])
        if not tp_texts:
            print(f"\n  [SKIP] {pii_type} -- no TP corpus defined")
            return None

        print(f"\n{'-' * 120}")
        print(f"  {pii_type} ({len(variants)} variants, {len(tp_texts)} TP texts)")
        print(f"{'-' * 120}")

        best_f1, best_result, best_key, best_threshold = 0.0, None, None, 0.0

        for threshold in thresholds:
            results = self._run_threshold(model, pii_type, variants, tp_texts, threshold)
            self._print_threshold_results(threshold, results)
            best_f1, best_result, best_key, best_threshold = self._track_best(
                results, best_f1, best_result, best_key, best_threshold, threshold
            )

        if not best_result:
            return None

        status = "CHANGE" if best_key != "baseline" else "KEEP"
        print(f"\n  >>> BEST for {pii_type}: '{best_key}' at threshold {best_threshold} "
              f"(F1={best_f1:.2f}, P={best_result.precision:.2f}, "
              f"R={best_result.recall:.2f}, FP={best_result.fp}) [{status}]")

        return {
            "key": best_key, "label": best_result.label_text,
            "threshold": best_threshold, "f1": best_f1,
            "precision": best_result.precision, "recall": best_result.recall,
            "fp": best_result.fp, "fn": best_result.fn,
            "is_change": best_key != "baseline",
        }

    @staticmethod
    def _run_threshold(model, pii_type, variants, tp_texts, threshold):
        results = []
        for key, label_text in variants.items():
            r = _run_label(model, pii_type, label_text, tp_texts, ALL_FP_CORPORA, threshold)
            r.label_key = key
            results.append(r)
        results.sort(key=lambda x: (-x.f1, x.fp))
        return results

    @staticmethod
    def _print_threshold_results(threshold, results):
        print(f"\n  --- Threshold: {threshold} ---")
        print(f"  {'Label':<40s} {'Prec':>5s} {'Recall':>6s} {'F1':>6s} {'TP':>4s} {'FN':>4s} {'FP':>4s} {'FP%':>6s}  Label text")
        print(f"  {'-' * 110}")
        for r in results:
            marker = " <-- PROD" if r.label_key == "baseline" else ""
            print(
                f"  {r.label_key:<40s} {r.precision:>5.2f} {r.recall:>6.2f} {r.f1:>6.2f} "
                f"{r.tp:>4d} {r.fn:>4d} {r.fp:>4d} {r.fp_pct:>5.1f}%  "
                f'"{r.label_text}"{marker}'
            )

    @staticmethod
    def _track_best(results, best_f1, best_result, best_key, best_threshold, threshold):
        top = results[0]
        prev_fp = best_result.fp if best_result else 999
        if top.f1 > best_f1 or (top.f1 == best_f1 and top.fp < prev_fp):
            return top.f1, top, top.label_key, threshold
        return best_f1, best_result, best_key, best_threshold

    @staticmethod
    def _print_final_summary(recommendations):
        print("\n" + "=" * 120)
        print("FINAL RECOMMENDATIONS")
        print("=" * 120)
        print(f"{'PII Type':<30s} {'Status':<8s} {'Current Label':<35s} {'Recommended Label':<45s} {'F1':>5s} {'FP':>3s}")
        print("-" * 120)

        changes = []
        for pii_type, rec in sorted(recommendations.items()):
            baseline_label = LABELS_TO_BENCHMARK[pii_type]["baseline"]
            status = "CHANGE" if rec["is_change"] else "KEEP"
            rec_label = rec["label"]
            print(
                f"{pii_type:<30s} {status:<8s} "
                f'"{baseline_label}"'
                f'{" " * max(1, 33 - len(baseline_label))}'
                f'"{rec_label}"'
                f'{" " * max(1, 43 - len(rec_label))}'
                f'{rec["f1"]:>5.2f} {rec["fp"]:>3d}'
            )
            if rec["is_change"]:
                changes.append(pii_type)

        print(f"\nTotal: {len(changes)} labels to change, "
              f"{len(recommendations) - len(changes)} to keep")
        print("=" * 120)

    def test_reproduce_sesaf_avs_false_positives(self, model):
        """Reproduce the SESAF false positive on AVS_NUMBER (from user screenshot)."""
        print("\n" + "=" * 80)
        print("Reproducing SESAF -> AVS_NUMBER false positive (user-reported)")
        print("=" * 80)

        sesaf_text = FP_CORPUS_ADMIN

        for key, label in LABELS_TO_BENCHMARK["AVS_NUMBER"].items():
            entities = model.predict_entities(sesaf_text, [label], threshold=0.3)
            if entities:
                detected = {f"{e['text'][:30]}({e['score']:.2f})" for e in entities}
                marker = " <-- PROD" if key == "baseline" else ""
                print(f"  {key:<35s} FP DETECTED: {detected}{marker}")
            else:
                marker = " <-- PROD" if key == "baseline" else ""
                print(f"  {key:<35s} clean{marker}")

    def test_reproduce_section_number_ip_false_positives(self, model):
        """Reproduce section numbers (1.2.1.3) being flagged as IP addresses."""
        print("\n" + "=" * 80)
        print("Reproducing section numbers -> IP_ADDRESS false positive")
        print("=" * 80)

        section_text = FP_CORPUS_TECH_DOC

        for key, label in LABELS_TO_BENCHMARK["IP_ADDRESS"].items():
            entities = model.predict_entities(section_text, [label], threshold=0.3)
            if entities:
                detected = {f"{e['text'][:30]}({e['score']:.2f})" for e in entities}
                marker = " <-- PROD" if key == "baseline" else ""
                print(f"  {key:<35s} FP DETECTED: {detected}{marker}")
            else:
                marker = " <-- PROD" if key == "baseline" else ""
                print(f"  {key:<35s} clean{marker}")


if __name__ == "__main__":
    # Allow running directly
    m = GLiNER.from_pretrained(MODEL_ID)

    test = TestNvidiaGlinerAllRemainingLabels()
    test.test_benchmark_all_labels(m)
    test.test_reproduce_sesaf_avs_false_positives(m)
    test.test_reproduce_section_number_ip_false_positives(m)
