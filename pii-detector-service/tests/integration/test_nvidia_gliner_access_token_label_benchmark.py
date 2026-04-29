"""
Benchmark test: evaluate label variants for ACCESS_TOKEN detection on nvidia/gliner-PII.

Problem anticipated: the label "access token" is short (2 words) and "token" is
extremely generic in technical documentation:
- CSRF tokens, XSRF tokens (not PII)
- JWT tokens mentioned in architecture docs
- Token-based authentication descriptions
- OAuth flow descriptions mentioning "token endpoint", "token refresh"
- "Token" in natural language: "token of appreciation", "token effort"

Real access tokens: eyJhbGciOiJIUzI1NiIs... (JWT), gho_xxxxxxxxxxxx (GitHub),
ya29.a0AfH6SMA... (Google OAuth)

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_access_token_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_access_token_label_benchmark.py
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
    "access_token":
        "access token",

    # Short variants (2-3 words)
    "bearer_token":
        "bearer token",
    "auth_token":
        "authentication token",
    "oauth_token":
        "OAuth token",

    # Medium variants (3-5 words) -- usually winners on nvidia
    "bearer_auth_token":
        "bearer authentication access token",
    "oauth_access_credential":
        "OAuth access credential token",
    "session_bearer_token":
        "session bearer authentication token",
    "api_bearer_credential":
        "API bearer credential string",

    # Long descriptive variants (5+ words)
    "programmatic_access_credential":
        "programmatic access authentication credential",
    "oauth_bearer_access_credential":
        "OAuth bearer access authentication credential",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: Real access tokens in configuration ---------------------------
CORPUS_REAL_TOKENS = (
    "Configuration des tokens d'acces:\n\n"
    "GitHub Personal Access Token:\n"
    "  GITHUB_TOKEN=gho_ABCDEFghijklmnop1234567890abcdef\n\n"
    "Google OAuth2 Access Token:\n"
    "  access_token: ya29.a0AfH6SMABcDeFgHiJkLmNoPqRsTuVwXyZ123456789\n\n"
    "Azure AD Bearer Token:\n"
    "  Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature\n\n"
    "Slack Bot Token:\n"
    "  SLACK_TOKEN=xoxb-fake-1234567890-benchmarktoken"
)
CORPUS_REAL_TOKENS_TP = {
    "gho_ABCDEFghijklmnop1234567890abcdef",
    "ya29.a0AfH6SMABcDeFgHiJkLmNoPqRsTuVwXyZ123456789",
    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature",
    "xoxb-fake-1234567890-benchmarktoken",
}
CORPUS_REAL_TOKENS_FP = {
    "GitHub", "Google", "Azure AD", "Slack",
}

# -- Corpus 2: Real tokens in HTTP headers -----------------------------------
CORPUS_HTTP_HEADERS = (
    "Exemple de requete API authentifiee:\n\n"
    "GET /api/v2/users HTTP/1.1\n"
    "Host: api.example.com\n"
    "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoiam9obiJ9.abc123def\n"
    "X-Request-ID: req_abc123\n"
    "Content-Type: application/json\n\n"
    "Response:\n"
    "HTTP/1.1 200 OK\n"
    "X-RateLimit-Remaining: 99"
)
CORPUS_HTTP_HEADERS_TP = {
    "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoiam9obiJ9.abc123def",
}
CORPUS_HTTP_HEADERS_FP = {
    "api.example.com", "req_abc123",
    "application/json",
}

# -- Corpus 3: OAuth flow documentation (PURE FP TRAP) -----------------------
CORPUS_OAUTH_DOC = (
    "Flux d'authentification OAuth 2.0:\n\n"
    "1. L'application redirige l'utilisateur vers le serveur d'autorisation\n"
    "2. L'utilisateur s'authentifie et autorise l'application\n"
    "3. Le serveur renvoie un authorization code\n"
    "4. L'application echange le code contre un access token\n"
    "5. Le token a une duree de vie de 3600 secondes\n"
    "6. Un refresh token permet de renouveler l'access token\n"
    "7. Le token endpoint est: /oauth/token\n"
    "8. Le token type est: Bearer\n\n"
    "Securite:\n"
    "- Token storage: cote serveur uniquement\n"
    "- Token rotation: a chaque refresh\n"
    "- Token revocation: endpoint /oauth/revoke\n"
    "- CSRF token: inclus dans chaque formulaire"
)
CORPUS_OAUTH_DOC_TP: Set[str] = set()  # NO actual tokens -- just documentation
CORPUS_OAUTH_DOC_FP = {
    "access token", "refresh token", "authorization code",
    "token endpoint", "token type", "Bearer",
    "CSRF token", "Token storage", "Token rotation", "Token revocation",
    "/oauth/token", "/oauth/revoke",
}

# -- Corpus 4: Architecture doc with token mentions (PURE FP TRAP) -----------
CORPUS_ARCHITECTURE_DOC = (
    "Architecture de securite - AI Sentinel:\n\n"
    "Authentification:\n"
    "- Methode: JWT token-based authentication\n"
    "- Token issuer: Keycloak 22.0\n"
    "- Token validation: signature RSA-256\n"
    "- Token lifetime: 15 minutes (access), 7 jours (refresh)\n\n"
    "Anti-CSRF:\n"
    "- Double submit cookie pattern\n"
    "- XSRF-TOKEN header synchronise avec cookie\n"
    "- Token genere par Spring Security CsrfFilter\n\n"
    "gRPC:\n"
    "- Metadata token: transmis via header 'authorization'\n"
    "- Token format: 'Bearer <jwt>'\n"
    "- Token propagation: intercepteur cote client"
)
CORPUS_ARCHITECTURE_DOC_TP: Set[str] = set()  # NO actual tokens
CORPUS_ARCHITECTURE_DOC_FP = {
    "JWT", "Keycloak", "RSA-256",
    "XSRF-TOKEN", "CsrfFilter",
    "token-based authentication",
    "Token issuer", "Token validation", "Token lifetime",
}

# -- Corpus 5: Natural language with "token" (PURE FP TRAP) ------------------
CORPUS_NATURAL_LANGUAGE = (
    "Compte-rendu de la reunion du 15 mars 2025:\n\n"
    "Le chef de projet a remis un token de remerciement aux equipes.\n"
    "La participation etait symbolique, un simple token d'appreciation.\n"
    "Le budget alloue ne represente qu'un token effort par rapport au projet global.\n\n"
    "Points discutes:\n"
    "- Integration avec le service de tokenization des cartes bancaires\n"
    "- Le systeme de token ring reseau sera remplace\n"
    "- Presentation du proof-of-concept token economy blockchain\n"
    "- Revue du token bucket algorithm pour le rate limiting"
)
CORPUS_NATURAL_LANGUAGE_TP: Set[str] = set()
CORPUS_NATURAL_LANGUAGE_FP = {
    "token de remerciement", "token d'appreciation",
    "token effort", "tokenization",
    "token ring", "token economy",
    "token bucket",
}

# -- Corpus 6: Abbreviation stress test (PURE FP TRAP) -----------------------
CORPUS_ABBREVIATION_STRESS = (
    "DNS DHCP VPN MFA RSA PKI SIEM WAF TOKEN BEARER AUTH OAUTH "
    "JWT SAML SSO RBAC ACL CORS CSRF XSS SQL SSH TLS SSL XSRF "
    "AWS GCP AZ VM LB CDN S3 EC2 RDS SQS SNS ECS EKS IAM KMS "
    "API REST SOAP GRPC HTTP HTTPS WSS AMQP MQTT STOMP LDAP"
)
CORPUS_ABBREVIATION_STRESS_TP: Set[str] = set()
CORPUS_ABBREVIATION_STRESS_FP = {
    "TOKEN", "BEARER", "AUTH", "OAUTH", "JWT", "CSRF", "XSRF",
    "API", "REST", "GRPC", "AWS", "IAM", "KMS",
}

ALL_CORPORA = [
    ("REAL_TOKENS",          CORPUS_REAL_TOKENS,          CORPUS_REAL_TOKENS_TP,          CORPUS_REAL_TOKENS_FP),
    ("HTTP_HEADERS",         CORPUS_HTTP_HEADERS,         CORPUS_HTTP_HEADERS_TP,         CORPUS_HTTP_HEADERS_FP),
    ("OAUTH_DOC",            CORPUS_OAUTH_DOC,            CORPUS_OAUTH_DOC_TP,            CORPUS_OAUTH_DOC_FP),
    ("ARCHITECTURE_DOC",     CORPUS_ARCHITECTURE_DOC,     CORPUS_ARCHITECTURE_DOC_TP,     CORPUS_ARCHITECTURE_DOC_FP),
    ("NATURAL_LANGUAGE",     CORPUS_NATURAL_LANGUAGE,     CORPUS_NATURAL_LANGUAGE_TP,     CORPUS_NATURAL_LANGUAGE_FP),
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


PROD_KEY = "access_token"


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
class TestNvidiaGlinerAccessTokenLabelBenchmark:
    """Benchmark label variants for ACCESS_TOKEN PII detection on nvidia/gliner-PII."""

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
        print("nvidia/gliner-PII -- ACCESS_TOKEN Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'access token' is short, 'token' is generic -- may flag OAuth doc, CSRF, natural language")
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

        assert best_f1 > 0.0, "No label detected any access token"

        baseline_03 = results[0.3][PROD_KEY]
        print(f"\n[REGRESSION CHECK] '{PROD_KEY}' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_oauth_doc_false_positives(self, nvidia_gliner_model):
        """
        Reproduce likely FP on OAuth documentation.
        Text describes OAuth flow -- mentions 'token' many times but NO real tokens.
        """
        text = (
            "OAuth 2.0 Flow:\n"
            "1. Request authorization code\n"
            "2. Exchange code for access token\n"
            "3. Use access token in Authorization header\n"
            "4. Refresh access token when expired\n"
            "5. Token endpoint: POST /oauth/token\n"
            "6. Token type: Bearer\n"
            "7. Token lifetime: 3600 seconds"
        )

        print(f"\n{'='*80}")
        print("Reproducing OAuth doc FP -- testing all labels")
        print(f"{'='*80}")
        print("Text describes OAuth flow (no real tokens) -- expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_access_tokens(self, nvidia_gliner_model):
        """All labels must detect at least some real tokens at threshold 0.3."""
        text = (
            "Tokens actifs:\n"
            "GitHub: gho_ABCDEFghijklmnop1234567890abcdef\n"
            "Google: ya29.a0AfH6SMABcDeFgHiJkLmNoPqRsTuVwXyZ123456789\n"
            "JWT: eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoiam9obiJ9.abc123def\n"
        )
        expected = {
            "gho_ABCDEFghijklmnop1234567890abcdef",
            "ya29.a0AfH6SMABcDeFgHiJkLmNoPqRsTuVwXyZ123456789",
            "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoiam9obiJ9.abc123def",
        }

        print(f"\n{'='*80}")
        print("Real access token detection -- all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense abbreviation soup -- should detect ZERO access tokens."""
        text = (
            "DNS DHCP VPN MFA RSA PKI SIEM WAF TOKEN BEARER AUTH OAUTH "
            "JWT SAML SSO RBAC ACL CORS CSRF XSS SQL SSH TLS SSL XSRF "
            "API REST SOAP GRPC HTTP HTTPS WSS AMQP MQTT STOMP LDAP "
            "AWS GCP AZ IAM KMS STS SES SNS SQS EC2 S3 RDS ECS EKS"
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

    test = TestNvidiaGlinerAccessTokenLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_oauth_doc_false_positives(model)
    test.test_detects_real_access_tokens(model)
    test.test_abbreviation_stress_test(model)
