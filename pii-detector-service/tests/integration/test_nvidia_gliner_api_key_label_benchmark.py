"""
Benchmark test: evaluate label variants for API_KEY detection on nvidia/gliner-PII.

Problem anticipated: the label "api key" is very short (2 words) and "key" is highly
generic. In technical Confluence documentation, "key" appears everywhere:
- Translation keys: i18n.label.title, messages.error.notfound
- Configuration keys: spring.datasource.url, server.port
- SSH public keys: ssh-rsa AAAAB3NzaC1yc2EAAA...
- Encryption keys: AES-256, RSA 2048-bit key
- Primary/foreign keys: PRIMARY KEY, FOREIGN KEY
- Map keys, cache keys, registry keys...

Real API keys: sk-proj-abc123def456, AKIAIOSFODNN7EXAMPLE, ghp_xxxxxxxxxxxx

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_api_key_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_api_key_label_benchmark.py
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
    "api_key":
        "api key",

    # Short variants (2-3 words)
    "api_secret":
        "API secret",
    "api_credential":
        "API credential",
    "api_token":
        "API token",

    # Medium variants (3-5 words) -- usually winners on nvidia
    "api_secret_key":
        "API secret key or token",
    "api_authentication_key":
        "API authentication credential",
    "service_api_key":
        "service API access key",
    "api_access_credential":
        "API access credential string",

    # Long descriptive variants (5+ words)
    "programmatic_api_credential":
        "programmatic API authentication credential",
    "rest_api_secret_key":
        "REST API secret access credential",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: Real API keys in configuration documentation -------------------
CORPUS_REAL_API_KEYS = (
    "Configuration des services externes:\n\n"
    "OpenAI API:\n"
    "  api_key: sk-proj-abc123def456ghi789jkl012mno345\n"
    "  model: gpt-4\n\n"
    "AWS S3:\n"
    "  access_key_id: AKIAIOSFODNN7EXAMPLE\n"
    "  secret_access_key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n\n"
    "GitHub:\n"
    "  token: ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefgh\n\n"
    "Stripe:\n"
    "  api_key: sk_test_FAKE4eC39HqLyjWDarjtT1zdp7dc\n"
    "  publishable_key: pk_test_FAKETYooMQauvdEDq54NiTphI7jx"
)
CORPUS_REAL_API_KEYS_TP = {
    "sk-proj-abc123def456ghi789jkl012mno345",
    "AKIAIOSFODNN7EXAMPLE",
    "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefgh",
    "sk_test_FAKE4eC39HqLyjWDarjtT1zdp7dc",
    "pk_test_FAKETYooMQauvdEDq54NiTphI7jx",
}
CORPUS_REAL_API_KEYS_FP = {
    "gpt-4", "OpenAI", "AWS S3", "GitHub", "Stripe",
}

# -- Corpus 2: Real API keys in environment variables ------------------------
CORPUS_ENV_VARS = (
    "# .env.production (DO NOT COMMIT)\n"
    "OPENAI_API_KEY=sk-svcacct-abc123def456ghi789\n"
    "SONAR_TOKEN=sqp_abcdef1234567890abcdef1234567890\n"
    "DOCKER_REGISTRY_KEY=dckr_pat_abcDEF123456-ghiJKL\n"
    "INFISICAL_API_KEY=st.abc123.def456.ghi789jkl012mno\n"
    "DATABASE_URL=postgresql://user:password@localhost:5432/db"
)
CORPUS_ENV_VARS_TP = {
    "sk-svcacct-abc123def456ghi789",
    "sqp_abcdef1234567890abcdef1234567890",
    "dckr_pat_abcDEF123456-ghiJKL",
    "st.abc123.def456.ghi789jkl012mno",
}
CORPUS_ENV_VARS_FP = {
    "postgresql://user:password@localhost:5432/db",
}

# -- Corpus 3: Technical doc with "key" everywhere (PURE FP TRAP) ------------
CORPUS_GENERIC_KEYS = (
    "Architecture de la base de donnees:\n"
    "- PRIMARY KEY: id (bigint, auto-increment)\n"
    "- FOREIGN KEY: user_id REFERENCES users(id)\n"
    "- UNIQUE KEY: email_idx ON users(email)\n"
    "- INDEX KEY: created_at_idx ON orders(created_at)\n\n"
    "Configuration Spring Boot:\n"
    "- spring.datasource.url=jdbc:postgresql://...\n"
    "- server.port=8080\n"
    "- spring.cache.type=redis\n\n"
    "Internationalisation:\n"
    "- Cle de traduction: i18n.label.title\n"
    "- Cle de message: messages.error.notfound\n"
    "- Cle de config: app.feature.toggle.enabled\n\n"
    "Chiffrement:\n"
    "- Algorithme: AES-256-GCM\n"
    "- Taille de cle: RSA 2048 bits\n"
    "- Key derivation: PBKDF2 avec 600000 iterations"
)
CORPUS_GENERIC_KEYS_TP: Set[str] = set()  # NO API keys
CORPUS_GENERIC_KEYS_FP = {
    "PRIMARY KEY", "FOREIGN KEY", "UNIQUE KEY", "INDEX KEY",
    "i18n.label.title", "messages.error.notfound",
    "app.feature.toggle.enabled",
    "spring.datasource.url", "server.port",
    "AES-256-GCM", "RSA 2048",
    "PBKDF2",
}

# -- Corpus 4: SSH and encryption keys documentation (PURE FP TRAP) ----------
CORPUS_SSH_CRYPTO = (
    "Gestion des cles SSH:\n"
    "- Cle publique RSA: ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ user@host\n"
    "- Cle privee: stockee dans ~/.ssh/id_rsa (ne jamais partager)\n"
    "- Cle de deploiement: configuree dans GitLab CI/CD\n\n"
    "Certificats TLS:\n"
    "- Cle privee du serveur: /etc/ssl/private/server.key\n"
    "- Certificat public: /etc/ssl/certs/server.crt\n"
    "- CA root key: stockee en HSM (Hardware Security Module)\n\n"
    "Key management:\n"
    "- Key rotation: tous les 90 jours\n"
    "- Key escrow: coffre-fort numerique Infisical\n"
    "- Symmetric key: AES-256 pour le chiffrement au repos"
)
CORPUS_SSH_CRYPTO_TP: Set[str] = set()  # NO API keys
CORPUS_SSH_CRYPTO_FP = {
    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ",
    "id_rsa", "server.key", "server.crt",
    "AES-256", "HSM",
    "RSA", "TLS",
}

# -- Corpus 5: Project management with abbreviations (PURE FP TRAP) ----------
CORPUS_PROJECT_MANAGEMENT = (
    "Projet RAPAC - Sprint Review:\n"
    "- Story KEY-1234: Implementation du cache Redis\n"
    "- Story KEY-1235: Migration base de donnees\n"
    "- Bug KEY-1236: Correction du timeout API\n"
    "- Epic KEY-100: Refonte architecture hexagonale\n\n"
    "Indicateurs cles (KPI):\n"
    "- Velocity: 42 points\n"
    "- Lead time: 3.5 jours\n"
    "- Defect rate: 2.1%\n\n"
    "Prochaines etapes:\n"
    "- Livraison cle: 15 avril 2025\n"
    "- Jalon cle: recette utilisateur"
)
CORPUS_PROJECT_MANAGEMENT_TP: Set[str] = set()  # NO API keys
CORPUS_PROJECT_MANAGEMENT_FP = {
    "KEY-1234", "KEY-1235", "KEY-1236", "KEY-100",
    "KPI", "RAPAC", "Redis",
    "42", "3.5", "2.1%",
}

# -- Corpus 6: Abbreviation stress test (PURE FP TRAP) -----------------------
CORPUS_ABBREVIATION_STRESS = (
    "DNS DHCP VPN MFA RSA PKI SIEM WAF ADCS PRTG KEY API REST "
    "JWT OAuth SAML SSO MFA RBAC ACL CORS CSRF XSS SQL SSH TLS "
    "AWS GCP AZ VM LB CDN ALB NLB ELB S3 EC2 RDS SQS SNS "
    "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI BIC SOC DGMR "
    "PEM CRT CER DER PKCS PFX JKS P12 GPG PGP AES DES 3DES"
)
CORPUS_ABBREVIATION_STRESS_TP: Set[str] = set()
CORPUS_ABBREVIATION_STRESS_FP = {
    "KEY", "API", "REST", "JWT", "SSH", "TLS", "RSA", "AWS",
    "PEM", "CRT", "PKCS", "GPG", "AES", "DES", "3DES",
}

ALL_CORPORA = [
    ("REAL_API_KEYS",        CORPUS_REAL_API_KEYS,        CORPUS_REAL_API_KEYS_TP,        CORPUS_REAL_API_KEYS_FP),
    ("ENV_VARS",             CORPUS_ENV_VARS,             CORPUS_ENV_VARS_TP,             CORPUS_ENV_VARS_FP),
    ("GENERIC_KEYS",         CORPUS_GENERIC_KEYS,         CORPUS_GENERIC_KEYS_TP,         CORPUS_GENERIC_KEYS_FP),
    ("SSH_CRYPTO",           CORPUS_SSH_CRYPTO,           CORPUS_SSH_CRYPTO_TP,           CORPUS_SSH_CRYPTO_FP),
    ("PROJECT_MANAGEMENT",   CORPUS_PROJECT_MANAGEMENT,   CORPUS_PROJECT_MANAGEMENT_TP,   CORPUS_PROJECT_MANAGEMENT_FP),
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


PROD_KEY = "api_key"


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
class TestNvidiaGlinerApiKeyLabelBenchmark:
    """Benchmark label variants for API_KEY PII detection on nvidia/gliner-PII."""

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
        print("nvidia/gliner-PII -- API_KEY Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'api key' is short and 'key' is generic -- may flag DB keys, SSH keys, JIRA keys, etc.")
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

        assert best_f1 > 0.0, "No label detected any API key"

        baseline_03 = results[0.3][PROD_KEY]
        print(f"\n[REGRESSION CHECK] '{PROD_KEY}' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_generic_key_false_positives(self, nvidia_gliner_model):
        """
        Reproduce likely FP on generic 'key' references.
        Text contains ONLY non-API keys -- expect ZERO detections.
        """
        text = (
            "CREATE TABLE users (\n"
            "  id BIGINT PRIMARY KEY,\n"
            "  email VARCHAR(255) UNIQUE KEY,\n"
            "  department_id BIGINT FOREIGN KEY\n"
            ");\n\n"
            "JIRA tickets:\n"
            "- KEY-1234: Implement cache\n"
            "- KEY-1235: Fix timeout\n"
            "- KEY-1236: Update docs\n\n"
            "Translation keys: i18n.label.submit, i18n.error.required\n"
            "Config key: spring.datasource.url = jdbc:postgresql://...\n"
            "SSH public key: ssh-rsa AAAAB3NzaC1yc2EAAA..."
        )

        print(f"\n{'='*80}")
        print("Reproducing generic 'key' FP -- testing all labels")
        print(f"{'='*80}")
        print("Text contains ONLY generic keys (DB, JIRA, SSH) -- expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_api_keys(self, nvidia_gliner_model):
        """All labels must detect at least some real API keys at threshold 0.3."""
        text = (
            "Configuration:\n"
            "OPENAI_API_KEY=sk-proj-abc123def456ghi789jkl012mno345\n"
            "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE\n"
            "STRIPE_API_KEY=sk_test_FAKE4eC39HqLyjWDarjtT1zdp7dc\n"
        )
        expected = {
            "sk-proj-abc123def456ghi789jkl012mno345",
            "AKIAIOSFODNN7EXAMPLE",
            "sk_test_FAKE4eC39HqLyjWDarjtT1zdp7dc",
        }

        print(f"\n{'='*80}")
        print("Real API key detection -- all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense abbreviation soup -- should detect ZERO API keys."""
        text = (
            "DNS DHCP VPN MFA RSA PKI SIEM WAF KEY API REST TOKEN SECRET "
            "JWT OAuth SAML SSO RBAC ACL CORS CSRF XSS SQL SSH TLS SSL "
            "PEM CRT PKCS PFX JKS P12 GPG PGP AES DES 3DES SHA MD5 HMAC "
            "AWS GCP AZ VM LB CDN S3 EC2 RDS SQS SNS ECS EKS IAM KMS"
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

    test = TestNvidiaGlinerApiKeyLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_generic_key_false_positives(model)
    test.test_detects_real_api_keys(model)
    test.test_abbreviation_stress_test(model)
