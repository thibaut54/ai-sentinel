"""
Benchmark test: evaluate label variants for SESSION_ID detection on nvidia/gliner-PII.

Problem anticipated: the label "session id" is short (2 words) and "id" is the most
generic identifier suffix. Combined with "session", it risks flagging:
- Session descriptions: "session de formation", "session d'information"
- Meeting sessions: "session du conseil", "session parlementaire"
- Training sessions: "3 sessions de coaching"
- Technical sessions: "HTTP session", "database session"
- Session-related config: session.timeout=30, session.cookie.name

Real session IDs: JSESSIONID=ABC123, session_id=f47ac10b-58cc, sid=a1b2c3d4e5f6

Usage:
    python -m pytest tests/integration/test_nvidia_gliner_session_id_label_benchmark.py -v -s
    python tests/integration/test_nvidia_gliner_session_id_label_benchmark.py
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
    "session_id":
        "session id",

    # Short variants (2-3 words)
    "session_identifier":
        "session identifier",
    "web_session":
        "web session",
    "session_token":
        "session token",

    # Medium variants (3-5 words) -- usually winners on nvidia
    "web_session_identifier":
        "web session identifier",
    "http_session_identifier":
        "HTTP session tracking identifier",
    "browser_session_token":
        "browser session tracking token",
    "authenticated_session_identifier":
        "authenticated session identifier",

    # Long descriptive variants (5+ words)
    "web_application_session":
        "web application session tracking identifier",
    "server_session_credential":
        "server-side session authentication credential",
}

# ---------------------------------------------------------------------------
# Ground truth corpora
# ---------------------------------------------------------------------------

# -- Corpus 1: Real session IDs in technical logs ----------------------------
CORPUS_REAL_SESSION_IDS = (
    "Application logs - AI Sentinel:\n\n"
    "2025-03-15 10:23:45 [INFO] User login successful\n"
    "  JSESSIONID=ABC123DEF456GHI789JKL012MNO345PQR\n"
    "  session_id=f47ac10b-58cc-4372-a567-0e02b2c3d479\n"
    "  user=jean.dupont@example.com\n\n"
    "2025-03-15 10:24:12 [INFO] Session created\n"
    "  sid=a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6\n"
    "  cookie: PHPSESSID=qwertyuiopasdfghjklzxcvbnm\n\n"
    "2025-03-15 10:25:00 [WARN] Session expiring\n"
    "  connect.sid=s%3Axyz789abc123def456.signature"
)
CORPUS_REAL_SESSION_IDS_TP = {
    "ABC123DEF456GHI789JKL012MNO345PQR",
    "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
    "qwertyuiopasdfghjklzxcvbnm",
}
CORPUS_REAL_SESSION_IDS_FP = {
    "jean.dupont@example.com",
    "AI Sentinel",
}

# -- Corpus 2: Session IDs in security audit --------------------------------
CORPUS_SECURITY_AUDIT = (
    "Audit de securite - Sessions actives:\n\n"
    "Session 1:\n"
    "  ID: sess_2a4b6c8d0e1f3g5h7i9j\n"
    "  Utilisateur: admin\n"
    "  IP: 192.168.1.100\n"
    "  Duree: 45 min\n\n"
    "Session 2:\n"
    "  ID: sess_9k8l7m6n5o4p3q2r1s0t\n"
    "  Utilisateur: reader\n"
    "  IP: 10.0.0.50\n"
    "  Duree: 12 min\n\n"
    "Recommandation: forcer la rotation des session IDs toutes les 30 minutes."
)
CORPUS_SECURITY_AUDIT_TP = {
    "sess_2a4b6c8d0e1f3g5h7i9j",
    "sess_9k8l7m6n5o4p3q2r1s0t",
}
CORPUS_SECURITY_AUDIT_FP = {
    "192.168.1.100", "10.0.0.50",
    "admin", "reader",
}

# -- Corpus 3: Meeting/training sessions (PURE FP TRAP) ---------------------
CORPUS_MEETING_SESSIONS = (
    "Calendrier des sessions de formation 2025:\n\n"
    "Session 1: Introduction a la securite informatique\n"
    "  Date: 15 mars 2025\n"
    "  Duree: 3 heures\n"
    "  Participants: 25 personnes\n\n"
    "Session 2: Protection des donnees personnelles (RGPD)\n"
    "  Date: 22 mars 2025\n"
    "  Duree: 4 heures\n"
    "  Participants: 30 personnes\n\n"
    "Session 3: Sensibilisation au phishing\n"
    "  Date: 29 mars 2025\n\n"
    "La prochaine session du conseil d'administration aura lieu le 5 avril.\n"
    "La session parlementaire de printemps commence le 1er mars.\n"
    "Trois sessions de coaching individuel sont prevues ce trimestre."
)
CORPUS_MEETING_SESSIONS_TP: Set[str] = set()  # NO session IDs
CORPUS_MEETING_SESSIONS_FP = {
    "Session 1", "Session 2", "Session 3",
    "session du conseil", "session parlementaire",
    "sessions de coaching", "sessions de formation",
}

# -- Corpus 4: Technical session config (PURE FP TRAP) ----------------------
CORPUS_SESSION_CONFIG = (
    "Configuration Spring Security - Gestion des sessions:\n\n"
    "server.servlet.session.timeout=30m\n"
    "server.servlet.session.cookie.name=JSESSIONID\n"
    "server.servlet.session.cookie.secure=true\n"
    "server.servlet.session.cookie.http-only=true\n"
    "spring.session.store-type=redis\n"
    "spring.session.redis.namespace=ai-sentinel:session\n\n"
    "Session management strategy:\n"
    "- Maximum sessions per user: 1\n"
    "- Session fixation protection: migrateSession\n"
    "- Invalid session URL: /login?expired\n"
    "- Session creation policy: IF_REQUIRED\n\n"
    "Database session table: SPRING_SESSION\n"
    "Session attribute: SPRING_SESSION_ATTRIBUTES"
)
CORPUS_SESSION_CONFIG_TP: Set[str] = set()  # NO real session IDs
CORPUS_SESSION_CONFIG_FP = {
    "JSESSIONID", "session.timeout", "session.cookie.name",
    "spring.session.store-type", "ai-sentinel:session",
    "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES",
    "IF_REQUIRED", "migrateSession",
}

# -- Corpus 5: Project management table (PURE FP TRAP) ----------------------
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
    "ID UID GID PID TID SID CID SESSION SESS TOKEN COOKIE "
    "DNS DHCP VPN MFA RSA PKI SIEM WAF JWT SSO RBAC "
    "HTTP HTTPS TLS SSL SSH FTP SFTP SMTP LDAP REDIS "
    "SEJ TEP CEI EUCM SES FC SOCLE RH INST DSI SOC"
)
CORPUS_ABBREVIATION_STRESS_TP: Set[str] = set()
CORPUS_ABBREVIATION_STRESS_FP = {
    "ID", "SID", "SESSION", "SESS", "TOKEN", "COOKIE",
    "JWT", "SSO", "HTTP", "REDIS",
}

ALL_CORPORA = [
    ("REAL_SESSION_IDS",     CORPUS_REAL_SESSION_IDS,     CORPUS_REAL_SESSION_IDS_TP,     CORPUS_REAL_SESSION_IDS_FP),
    ("SECURITY_AUDIT",       CORPUS_SECURITY_AUDIT,       CORPUS_SECURITY_AUDIT_TP,       CORPUS_SECURITY_AUDIT_FP),
    ("MEETING_SESSIONS",     CORPUS_MEETING_SESSIONS,     CORPUS_MEETING_SESSIONS_TP,     CORPUS_MEETING_SESSIONS_FP),
    ("SESSION_CONFIG",       CORPUS_SESSION_CONFIG,       CORPUS_SESSION_CONFIG_TP,       CORPUS_SESSION_CONFIG_FP),
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


PROD_KEY = "session_id"


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
class TestNvidiaGlinerSessionIdLabelBenchmark:
    """Benchmark label variants for SESSION_ID PII detection on nvidia/gliner-PII."""

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
        print("nvidia/gliner-PII -- SESSION_ID Label Benchmark Results")
        print(f"{'='*115}")
        print(f"Model: {MODEL_ID}")
        print(f"Corpora: {len(ALL_CORPORA)} | Total expected TP: {total_tp} | "
              f"Total FP traps: {total_fp_traps}")
        print(f"Label variants: {len(LABEL_VARIANTS)}")
        print("Problem: 'session id' is short, 'id' generic -- may flag training sessions, config, meetings")
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

        assert best_f1 > 0.0, "No label detected any session ID"

        baseline_03 = results[0.3][PROD_KEY]
        print(f"\n[REGRESSION CHECK] '{PROD_KEY}' at 0.3: FP={baseline_03.false_positives} "
              f"FP%={baseline_03.fp_rate:.1%}")

    def test_reproduce_meeting_session_false_positives(self, nvidia_gliner_model):
        """
        Reproduce likely FP on meeting/training session descriptions.
        Text contains ONLY session descriptions -- zero session IDs.
        """
        text = (
            "Session de formation: Introduction RGPD\n"
            "Session du conseil d'administration du 5 avril\n"
            "Session parlementaire de printemps\n"
            "3 sessions de coaching individuel\n"
            "Session d'information pour les nouveaux collaborateurs\n"
            "La session de travail a dure 2 heures"
        )

        print(f"\n{'='*80}")
        print("Reproducing meeting/training session FP -- testing all labels")
        print(f"{'='*80}")
        print("Text contains ONLY session descriptions -- expect ZERO detections\n")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"]: round(e["score"], 3) for e in entities}
            status = "FP DETECTED" if detected else "clean"
            if label_key == PROD_KEY:
                status += " <-- PROD"
            print(f"  {label_key:<35} {status}")
            if detected:
                print(f"  {'':35} detected={detected}")

    def test_detects_real_session_ids(self, nvidia_gliner_model):
        """All labels must detect at least some real session IDs at threshold 0.3."""
        text = (
            "Session tracking:\n"
            "JSESSIONID=ABC123DEF456GHI789JKL012MNO345PQR\n"
            "session_id=f47ac10b-58cc-4372-a567-0e02b2c3d479\n"
            "sid=a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6\n"
        )
        expected = {
            "ABC123DEF456GHI789JKL012MNO345PQR",
            "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
        }

        print(f"\n{'='*80}")
        print("Real session ID detection -- all labels at threshold 0.3")
        print(f"{'='*80}")

        for label_key, label_text in LABEL_VARIANTS.items():
            entities = nvidia_gliner_model.predict_entities(text, [label_text], threshold=0.3)
            detected = {e["text"] for e in entities}
            found = {tp for tp in expected if _match_span_in_set(tp, detected)}
            print(f"  {label_key:<35} found={len(found)}/{len(expected)} "
                  f"detected={detected}  label=\"{label_text}\"")

    def test_abbreviation_stress_test(self, nvidia_gliner_model):
        """Dense abbreviation soup -- should detect ZERO session IDs."""
        text = (
            "ID UID GID PID TID SID CID SESSION SESS TOKEN COOKIE "
            "JSESSIONID PHPSESSID ASPSESSIONID CONNECT SID "
            "DNS DHCP VPN MFA RSA PKI SIEM WAF JWT SSO RBAC "
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

    test = TestNvidiaGlinerSessionIdLabelBenchmark()
    test.test_benchmark_all_labels(model)
    test.test_reproduce_meeting_session_false_positives(model)
    test.test_detects_real_session_ids(model)
    test.test_abbreviation_stress_test(model)
