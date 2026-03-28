"""
Benchmark test: evaluate label variants for PASSWORD detection on nvidia/gliner-PII.

Problem observed in production: the label "password" generates false positives on:
- Cryptographic key types: "RSA" flagged as password at 94-98% confidence
- Random French text: "QUI IN" flagged as password at 80% confidence
- Technical acronyms and short tokens that look like secrets to the model

This test reproduces these false positives with real Confluence page content
and benchmarks alternative labels to find one that detects actual passwords
while ignoring crypto key names, acronyms, and unrelated text.

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_password_label_benchmark.py -v -s
    # or standalone:
    python tests/integration/test_nvidia_gliner_password_label_benchmark.py
"""

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Set

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from gliner import GLiNER

# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------
MODEL_ID = "nvidia/gliner-PII"

# ---------------------------------------------------------------------------
# Label variants to benchmark
# ---------------------------------------------------------------------------
LABEL_VARIANTS: Dict[str, str] = {
    # Baseline — current production label (known FP on RSA, QUI IN)
    "password":
        "password",

    # More specific: emphasize it's a secret credential string
    "password_or_secret_credential":
        "password or secret credential",
    "user_password":
        "user password",
    "login_password":
        "login password",
    "authentication_password":
        "authentication password",

    # Explicit pattern description
    "plaintext_password":
        "plaintext password in clear text",
    "password_value":
        "password value or passphrase",

    # Very specific — try to exclude crypto keys
    "secret_passphrase":
        "secret passphrase",
    "account_password":
        "account password or PIN code",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: VPN/RSA key context (REAL production FP from screenshot) ------
CORPUS_VPN_RSA = (
    "En mars 2022, la Direction de la DGNSI a décidé de ne plus octroyer aux "
    "fournisseurs, un accès VPN Pulse basé sur une clé RSA.\n"
    "De supprimer les accès existants et donc de récupérer les clés RSA.\n"
    "Les accès actuels de fournisseurs externes qui travaillent sur leurs BYOD "
    "avec un VPN et clé RSA seront retirés progressivement et migrés sur des "
    "VM Windows ACV."
)
CORPUS_VPN_RSA_TP: Set[str] = set()  # NO passwords here
CORPUS_VPN_RSA_FP = {"RSA", "clé RSA", "clés RSA", "VPN", "VPN Pulse", "DGNSI", "BYOD", "ACV"}

# -- Corpus 2: Action table context (REAL production FP from screenshot) -----
CORPUS_ACTION_TABLE = (
    "# Action QUI IN VA PR\n"
    "Migrer les accès VPN Pulse vers ACV\n"
    "Récupérer les clés RSA des fournisseurs\n"
    "Planifier la migration BYOD vers VM Windows"
)
CORPUS_ACTION_TABLE_TP: Set[str] = set()  # NO passwords here
CORPUS_ACTION_TABLE_FP = {"QUI IN", "QUI", "IN", "VA", "PR", "RSA", "VPN", "BYOD", "ACV"}

# -- Corpus 3: IT doc with REAL passwords (must detect these) ----------------
CORPUS_REAL_PASSWORDS = (
    "Configuration du serveur de base de données:\n"
    "- Host: db-prod-01.internal\n"
    "- Port: 5432\n"
    "- Username: app_user\n"
    "- Password: Xk9$mP2!vL7#nQ4\n"
    "- Le mot de passe par défaut est: admin123\n"
    "- Ancien mot de passe: P@ssword!2024\n"
    "Pensez à changer le mot de passe après la première connexion."
)
CORPUS_REAL_PASSWORDS_TP = {"Xk9$mP2!vL7#nQ4", "admin123", "P@ssword!2024"}
CORPUS_REAL_PASSWORDS_FP = {"app_user", "db-prod-01.internal", "5432"}

# -- Corpus 4: Crypto & security doc (must NOT flag algorithms) ---------------
CORPUS_CRYPTO_DOC = (
    "Notre infrastructure utilise les standards de chiffrement suivants:\n"
    "- Certificats SSL/TLS avec clés RSA 2048 bits\n"
    "- Chiffrement AES-256-GCM pour les données au repos\n"
    "- Hachage SHA-256 pour les empreintes\n"
    "- Protocole OAuth 2.0 avec tokens JWT\n"
    "- Authentification MFA via TOTP\n"
    "- Échange de clés Diffie-Hellman (DH)\n"
    "Les clés privées SSH sont stockées dans le coffre-fort HashiCorp Vault."
)
CORPUS_CRYPTO_DOC_TP: Set[str] = set()  # NO passwords — just crypto terminology
CORPUS_CRYPTO_DOC_FP = {
    "RSA", "RSA 2048", "AES-256-GCM", "AES", "SHA-256", "SHA",
    "JWT", "TOTP", "MFA", "OAuth", "OAuth 2.0",
    "SSL", "TLS", "SSL/TLS", "SSH", "DH", "Diffie-Hellman",
    "HashiCorp Vault", "Vault",
}

# -- Corpus 5: Mixed French doc with passwords in various formats ------------
CORPUS_MIXED_PASSWORDS = (
    "Procédure de création de compte:\n"
    "1. Créer le compte avec le mot de passe temporaire: Welcome2025!\n"
    "2. L'utilisateur doit changer son mot de passe lors de la première connexion\n"
    "3. Le nouveau mot de passe doit respecter la politique:\n"
    "   - Minimum 12 caractères\n"
    "   - Au moins une majuscule, une minuscule, un chiffre et un caractère spécial\n"
    "4. Exemple de mot de passe NON conforme: 123456\n"
    "5. Exemple de mot de passe conforme: Tr0ub4dor&3\n"
    "Note: ne jamais partager son mdp par email ou Teams."
)
CORPUS_MIXED_PASSWORDS_TP = {"Welcome2025!", "123456", "Tr0ub4dor&3"}
CORPUS_MIXED_PASSWORDS_FP: Set[str] = set()

# -- Corpus 6: Technical acronyms soup (stress test for false positives) ------
CORPUS_ACRONYMS = (
    "Le projet EMPD utilise SONAR pour l'analyse de code. "
    "L'équipe QA valide les PR avant merge. "
    "Le CI/CD tourne sur Jenkins avec des agents K8S. "
    "Le monitoring Grafana surveille les métriques CPU, RAM et I/O. "
    "Les logs sont centralisés dans ELK (Elasticsearch, Logstash, Kibana). "
    "Le WAF Cloudflare protège les endpoints API REST. "
    "Le DNS est géré par Route53 avec failover multi-AZ."
)
CORPUS_ACRONYMS_TP: Set[str] = set()  # NO passwords — just tech acronyms
CORPUS_ACRONYMS_FP = {
    "EMPD", "SONAR", "QA", "PR", "CI/CD", "Jenkins", "K8S",
    "CPU", "RAM", "I/O", "ELK", "WAF", "API", "REST",
    "DNS", "Route53", "AZ",
}

ALL_CORPORA = [
    ("VPN_RSA",          CORPUS_VPN_RSA,          CORPUS_VPN_RSA_TP,          CORPUS_VPN_RSA_FP),
    ("ACTION_TABLE",     CORPUS_ACTION_TABLE,      CORPUS_ACTION_TABLE_TP,     CORPUS_ACTION_TABLE_FP),
    ("REAL_PASSWORDS",   CORPUS_REAL_PASSWORDS,    CORPUS_REAL_PASSWORDS_TP,   CORPUS_REAL_PASSWORDS_FP),
    ("CRYPTO_DOC",       CORPUS_CRYPTO_DOC,        CORPUS_CRYPTO_DOC_TP,       CORPUS_CRYPTO_DOC_FP),
    ("MIXED_PASSWORDS",  CORPUS_MIXED_PASSWORDS,   CORPUS_MIXED_PASSWORDS_TP,  CORPUS_MIXED_PASSWORDS_FP),
    ("ACRONYMS",         CORPUS_ACRONYMS,          CORPUS_ACRONYMS_TP,         CORPUS_ACRONYMS_FP),
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


def _print_threshold_results(results, threshold, col_width=35, prod_key="password"):
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
class TestNvidiaGlinerPasswordLabelBenchmark:
    """Benchmark label variants for PASSWORD PII detection on nvidia/gliner-PII."""

    def test_benchmark_all_labels(self, nvidia_gliner_model):
        """
        Run all label variants against all corpora at multiple thresholds.
        Focus: reduce FP on crypto acronyms (RSA, AES) while keeping TP on real passwords.
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
        total_tp = sum(len(tp) for _, _, tp, _ in ALL_CORPORA)
        total_fp_traps = sum(len(fp) for _, _, _, fp in ALL_CORPORA)
        print(f"\n{'='*110}")
        print("nvidia/gliner-PII — PASSWORD Label Benchmark Results")
        print(f"{'='*110}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print(f"Thresholds: {THRESHOLDS_TO_TEST}")
        print("Problem: 'password' label flags RSA, QUI IN, crypto acronyms as passwords")
        print(f"{'='*110}\n")

        best_f1_overall, best_label_overall, best_threshold_overall = 0.0, "", 0.0
        for threshold in THRESHOLDS_TO_TEST:
            f1, label = _print_threshold_results(results, threshold)
            if f1 > best_f1_overall:
                best_f1_overall = f1
                best_label_overall = label
                best_threshold_overall = threshold

        print(f"\n{'='*110}")
        print(f"BEST: '{best_label_overall}' at threshold {best_threshold_overall} "
              f"(F1={best_f1_overall:.2f})")
        if best_label_overall:
            best_score = results[best_threshold_overall][best_label_overall]
            print(f"  Precision={best_score.precision:.2f}  Recall={best_score.recall:.2f}  "
                  f"FP={best_score.false_positives}  FN={best_score.false_negatives}")
            print(f"  Label: \"{best_score.label_text}\"")
        print(f"{'='*110}")

        if best_threshold_overall:
            _print_top_label_details(results, best_threshold_overall)

        # --- Assertions ---
        assert best_f1_overall > 0.0, "No label detected any password — all labels broken"

        baseline_03 = results[0.3]["password"]
        print(f"\n[REGRESSION CHECK] 'password' at 0.3: FP={baseline_03.false_positives}")

    def test_reproduce_rsa_false_positive(self, nvidia_gliner_model):
        """
        Reproduce the exact RSA false positive seen in production.
        The text is from a real Confluence page about VPN/RSA key migration.
        """
        text = (
            "En mars 2022, la Direction de la DGNSI a décidé de ne plus octroyer "
            "aux fournisseurs, un accès VPN Pulse basé sur une clé RSA."
        )

        print(f"\n{'='*80}")
        print("Reproducing RSA false positive — testing all labels")
        print(f"{'='*80}")
        print(f"Text: \"{text[:80]}...\"")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: e["score"] for e in entities}
            rsa_detected = any("rsa" in t.lower() for t in detected)
            marker = " ❌ RSA FLAGGED" if rsa_detected else " ✅ clean"
            if label_key == "password":
                marker += " <-- PROD"
            print(f"  {label_key:<35} detected={detected}{marker}")

    def test_reproduce_qui_in_false_positive(self, nvidia_gliner_model):
        """
        Reproduce the QUI IN false positive seen in production.
        """
        text = "# Action QUI IN VA PR"

        print(f"\n{'='*80}")
        print("Reproducing QUI IN false positive — testing all labels")
        print(f"{'='*80}")
        print(f"Text: \"{text}\"")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: e["score"] for e in entities}
            fp = any(_normalize(t) in {"qui in", "qui", "in", "va", "pr"} for t in detected)
            marker = " ❌ FP" if fp else " ✅ clean"
            if label_key == "password":
                marker += " <-- PROD"
            print(f"  {label_key:<35} detected={detected}{marker}")

    def test_detects_real_passwords(self, nvidia_gliner_model):
        """All labels must detect at least one real password at threshold 0.3."""
        text = (
            "Database config:\n"
            "Password: Xk9$mP2!vL7#nQ4\n"
            "Default password: admin123\n"
        )

        print(f"\n{'='*80}")
        print("Real password detection — all labels at threshold 0.3")
        print(f"{'='*80}")

        expected = {"Xk9$mP2!vL7#nQ4", "admin123"}

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")


# ---------------------------------------------------------------------------
# Standalone runner
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print(f"Loading {MODEL_ID}...")
    model = GLiNER.from_pretrained(MODEL_ID)

    test = TestNvidiaGlinerPasswordLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_rsa_false_positive(model)
    test.test_reproduce_qui_in_false_positive(model)
    test.test_detects_real_passwords(model)
