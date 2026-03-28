"""
Integration test: GLiNER false-positive benchmark per label.

Runs the real nvidia/gliner-pii model against professional texts that
contain NO actual PII. Every detection is a false positive.

Goal: identify labels that are unusable due to excessive false positives.

Usage:
    pytest tests/integration/test_gliner_false_positive_benchmark.py -s -m integration
    python tests/integration/test_gliner_false_positive_benchmark.py
"""

import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.infrastructure.detector.gliner_detector import GLiNERDetector

# ---------------------------------------------------------------------------
# Threshold used for all labels
# ---------------------------------------------------------------------------
THRESHOLD = 0.80

# ---------------------------------------------------------------------------
# GLiNER label registry (from data.sql)
# Maps internal PII type -> detector_label used by GLiNER
# ---------------------------------------------------------------------------
GLINER_LABELS: Dict[str, str] = {
    # IDENTITY
    "PERSON_NAME": "person name",
    "NATIONAL_ID": "national identity number",
    "SSN": "social security number",
    "PASSPORT_NUMBER": "passport number",
    "DRIVER_LICENSE_NUMBER": "driver license number",
    "DATE_OF_BIRTH": "date of birth",
    "GENDER": "gender",
    "NATIONALITY": "nationality",
    "AGE": "age",
    # CONTACT
    "EMAIL": "email address",
    "PHONE_NUMBER": "phone number",
    "ADDRESS": "address",
    "CITY": "city",
    "ZIP_CODE": "zip code",
    # DIGITAL
    "USERNAME": "system account name",
    "ACCOUNT_ID": "account id",
    "URL": "url",
    # FINANCIAL
    "CREDIT_CARD_NUMBER": "credit card number",
    "BANK_ACCOUNT_NUMBER": "bank account number",
    "IBAN": "iban",
    "BIC_SWIFT": "swift code",
    "TAX_ID": "tax identification number",
    "SALARY": "salary amount",
    # MEDICAL
    "AVS_NUMBER": "avs number",
    "PATIENT_ID": "patient id",
    "MEDICAL_RECORD_NUMBER": "medical record number",
    "HEALTH_INSURANCE_NUMBER": "health insurance number",
    "DIAGNOSIS": "medical diagnosis",
    "MEDICATION": "medication name",
    # IT_CREDENTIALS
    "IP_ADDRESS": "ip address",
    "MAC_ADDRESS": "mac address",
    "HOSTNAME": "hostname",
    "DEVICE_ID": "device id",
    "PASSWORD": "password",
    "API_KEY": "api key",
    "ACCESS_TOKEN": "access token",
    "SECRET_KEY": "secret key",
    "SESSION_ID": "session id",
    # LEGAL_ASSET
    "CASE_NUMBER": "case number",
    "LICENSE_NUMBER": "license number",
    "CRIMINAL_RECORD": "criminal record",
    "VEHICLE_REGISTRATION": "vehicle registration number",
    "LICENSE_PLATE": "license plate number",
    "VIN": "vehicle identification number",
    "INSURANCE_POLICY_NUMBER": "insurance policy number",
}

# ---------------------------------------------------------------------------
# Realistic texts — content typical of Confluence/corporate docs that
# contains ambiguous tokens (product names, version numbers, ports, dates,
# locations, technical IDs, acronyms, code snippets) but NO actual PII.
# Every detection on these texts is a FALSE POSITIVE.
# ---------------------------------------------------------------------------
CLEAN_TEXTS: Dict[str, str] = {
    "deployment_procedure": (
        "Deployment procedure for DataBridge v3.2.1 on PREPROD2-VD environment. "
        "Connect to the bastion host via SSH on port 22. Run deploy.sh --env preprod2 "
        "to start the rollout. The service binds to 0.0.0.0:8443 and connects to "
        "the PostgreSQL cluster at db-internal:5432/databridge. Health check endpoint "
        "is available at /actuator/health. Rollback using tag 3.2.0-SNAPSHOT if needed. "
        "Container image: registry.gitlab.com/myorg/databridge:3.2.1-rc1. "
        "Expected memory footprint: 512Mi request, 1Gi limit. "
        "Kubernetes namespace: ns-databridge-preprod. Helm chart version 0.9.4. "
        "DNS entry: databridge.preprod.internal.vd.ch resolves to the ingress controller."
    ),
    "sprint_meeting_notes": (
        "Sprint 42 retrospective — 2025-03-15. Participants: DevOps team, QA chapter, "
        "Product Owner. Build time increased from 8 to 15 minutes after adding Sonar "
        "analysis. JIRA-4521: investigate Gradle build cache. JIRA-4522: split test "
        "suite into unit/integration modules. Target: reduce pipeline to under 10 min "
        "by Sprint 44. The Stockholm office reported flaky tests on Windows runners. "
        "Berlin team volunteered to investigate. Action items assigned in Confluence. "
        "Next planning session: Monday 2025-03-24 at 09:00 CET. Budget for new CI "
        "runners approved: CHF 12,500 per quarter."
    ),
    "infrastructure_wiki": (
        "Network topology — Production LAN. VLAN 100: application servers (10.0.1.0/24). "
        "VLAN 200: database tier (10.0.2.0/24). VLAN 300: management (10.0.3.0/24). "
        "Firewall rules: allow TCP 443 from VLAN 100 to VLAN 200. "
        "DNS servers: ns1.internal.vd.ch (10.0.3.10), ns2.internal.vd.ch (10.0.3.11). "
        "NTP source: ntp.ubuntu.com. DHCP range: 10.0.1.100–10.0.1.200. "
        "Switch model: Cisco Catalyst 9300 (serial C9300-24T). "
        "Rack location: Building B, Floor 3, Cabinet R12. "
        "UPS capacity: 20 kVA, runtime 45 minutes at full load. "
        "Monitoring via SNMP v3 on port 161. Community string rotated quarterly."
    ),
    "api_documentation": (
        "POST /api/v2/documents/scan HTTP/1.1. Content-Type: application/json. "
        "Authorization: Bearer <token>. Request body: {\"sourceType\": \"CONFLUENCE\", "
        "\"spaceKey\": \"IT-OPS\", \"pageId\": \"123456789\", \"threshold\": 0.80}. "
        "Response 200: {\"scanId\": \"a1b2c3d4-e5f6-7890-abcd-ef1234567890\", "
        "\"status\": \"RUNNING\", \"createdAt\": \"2025-03-15T10:30:00Z\"}. "
        "Error 401: invalid or expired bearer token. "
        "Error 429: rate limit exceeded (max 100 requests/minute). "
        "Pagination: use cursor=eyJwYWdlIjoyLCJzaXplIjoxMH0= for next page. "
        "Webhook callback URL: https://hooks.internal.vd.ch/pii-results. "
        "Timeout: 30000ms. Retry policy: exponential backoff, max 3 attempts."
    ),
    "incident_report": (
        "Incident INC-2025-0342 — Severity P2 — 2025-03-10 08:15 UTC. "
        "The PDF Extractor service returned HTTP 503 for 23 minutes. Root cause: "
        "JVM heap exhaustion on pod pdf-extractor-7f8b9c6d4-xk2lm (node worker-03). "
        "Memory limit was 2Gi but processing a 450-page document required 2.8Gi. "
        "Mitigation: increased memory limit to 4Gi, added circuit breaker with "
        "max-document-size=200 pages. Permanent fix: stream-based processing (JIRA-4601). "
        "Impact: 142 scan requests queued, 0 data loss. MTTR: 23 minutes. "
        "Timeline: 08:15 alert fired, 08:22 on-call acknowledged, 08:38 fix deployed. "
        "Post-mortem review scheduled for 2025-03-12 14:00 CET."
    ),
    "confluence_onboarding": (
        "Welcome to the IT Operations team! Here is your onboarding checklist. "
        "Step 1: Request VPN access through the ServiceNow portal (catalog item REQ-VPN-001). "
        "Step 2: Install IntelliJ IDEA 2025.1 and configure the Maven settings.xml with "
        "the Nexus mirror at https://nexus.internal.vd.ch/repository/maven-public/. "
        "Step 3: Clone the repositories from GitLab (group: /myorg/platform). "
        "Step 4: Set up Docker Desktop and authenticate to the container registry. "
        "Step 5: Join Slack channels #dev-backend, #ops-alerts, and #team-lunch. "
        "Step 6: Complete the security awareness training module SEC-101 by end of week 2. "
        "Office location: Lausanne, Avenue de Cour 135, Building C, 3rd floor. "
        "WiFi network: CORP-SECURE (WPA3 Enterprise, certificate-based auth)."
    ),
    "architecture_decision": (
        "ADR-017: Switch from MongoDB 6.0 to PostgreSQL 16 for the reporting service. "
        "Status: Accepted. Date: 2025-02-28. Context: ACID transactions required for "
        "financial audit trails. MongoDB's eventual consistency model caused reconciliation "
        "errors in Q4 2024 (see INC-2024-0891). Decision: migrate to PostgreSQL 16 with "
        "JSONB columns for semi-structured data. Consequences: estimated migration effort "
        "3 weeks (Sprint 45–46). Connection pool: HikariCP, max-pool-size=20. "
        "Read replicas for reporting queries. Flyway for schema versioning. "
        "License: PostgreSQL License (permissive, similar to MIT). "
        "Alternative considered: CockroachDB — rejected due to operational complexity "
        "and licensing costs ($45,000/year for enterprise tier)."
    ),
    "security_hardening_guide": (
        "Security hardening checklist for Spring Boot applications. "
        "TLS: enforce HTTPS with TLS 1.3 minimum. Disable TLS 1.0 and 1.1. "
        "Certificate: issued by Let's Encrypt or internal CA (validity 90 days). "
        "Headers: set X-Content-Type-Options: nosniff, X-Frame-Options: DENY, "
        "Strict-Transport-Security: max-age=31536000. "
        "CORS: restrict to https://*.internal.vd.ch. "
        "Authentication: OAuth 2.0 with Keycloak. Token lifetime: access=5min, refresh=30min. "
        "Password policy: minimum 12 characters, must include uppercase, lowercase, digit, "
        "and special character. Account lockout after 5 failed attempts for 15 minutes. "
        "Secrets management: use HashiCorp Vault or Infisical. Never store credentials "
        "in application.yml, environment variables, or Confluence pages. "
        "Logging: never log tokens, passwords, or PII. Use MDC for correlation IDs."
    ),
    "release_notes": (
        "Release notes — AI Sentinel v2.4.0 — 2025-03-20. "
        "New features: SharePoint connector (Beta), Confluence Data Center support, "
        "bulk export of scan results in CSV format. "
        "Improvements: GLiNER detection throughput improved by 35% via parallel chunk "
        "processing with ThreadPoolExecutor (max_workers=10). Presidio analyzer now "
        "supports French and German languages. Dashboard refresh rate reduced from "
        "5s to 2s using Server-Sent Events. "
        "Bug fixes: JIRA-4480 — false positive on version numbers like 2.3.1 detected "
        "as IP addresses. JIRA-4495 — scan status stuck at RUNNING when Confluence "
        "returns 429. JIRA-4501 — PDF attachment download fails for files > 50MB. "
        "Known issues: JIRA-4510 — username detection threshold may need tuning for "
        "short identifiers (3-4 characters). "
        "Upgrade path: run Flyway migration V2.4.0__add_source_type_sharepoint.sql."
    ),
    "project_budget_report": (
        "Q1 2025 Budget Report — AI Sentinel Platform. "
        "Infrastructure costs: Azure AKS cluster CHF 8,200/month (3 nodes, D4s v5). "
        "Database: Azure Database for PostgreSQL CHF 1,450/month (GP_Gen5_4 tier). "
        "Storage: 2 TB blob storage CHF 120/month. "
        "Licenses: GitLab Ultimate CHF 1,200/year (15 seats), SonarQube Developer "
        "CHF 3,500/year, Infisical Team CHF 600/year. "
        "Personnel: 4.5 FTE allocated (2 backend, 1.5 frontend, 1 DevOps). "
        "Training budget: CHF 5,000 for Spring Boot 3 certification and Angular 19 workshop. "
        "Total Q1 spend: CHF 42,350 (92% of budget). Variance: -CHF 3,650 under budget. "
        "Forecast Q2: CHF 48,000 (+13%) due to SharePoint integration and GPU node "
        "for GLiNER model serving (NC6s v3, CHF 2,800/month)."
    ),
}


@dataclass
class LabelResult:
    """Result of testing a single label against all clean texts."""
    label: str
    pii_type: str
    total_false_positives: int = 0
    false_positives_by_text: Dict[str, int] = field(default_factory=dict)
    detections: List[Dict] = field(default_factory=list)
    elapsed_seconds: float = 0.0


def _run_single_label(
    model,
    pii_type: str,
    label: str,
    threshold: float,
) -> LabelResult:
    """Run a single GLiNER label against all clean texts and count false positives."""
    result = LabelResult(label=label, pii_type=pii_type)
    start = time.time()

    for text_name, text in CLEAN_TEXTS.items():
        raw = model.predict_entities(text, [label], threshold=threshold)
        count = len(raw)
        result.false_positives_by_text[text_name] = count
        result.total_false_positives += count
        for entity in raw:
            result.detections.append({
                "text_source": text_name,
                "detected_text": text[entity["start"]:entity["end"]],
                "score": entity.get("score", 0.0),
                "start": entity["start"],
                "end": entity["end"],
            })

    result.elapsed_seconds = time.time() - start
    return result


def _print_false_positive_details(ranked: List[LabelResult]) -> None:
    """Print detailed false-positive log for problematic labels."""
    problematic = [r for r in ranked if r.total_false_positives > 0]
    if not problematic:
        return
    print("\n" + "=" * 100)
    print("DETAILED FALSE POSITIVES")
    print("=" * 100)
    for r in problematic:
        print(f"\n[{r.pii_type}] label='{r.label}' — {r.total_false_positives} FP")
        for d in r.detections:
            print(
                f"   Source: {d['text_source']:<35} "
                f"Text: '{d['detected_text']:<40}' "
                f"Score: {d['score']:.3f}"
            )


def _print_summary(
    results: List[LabelResult],
    unusable: List[LabelResult],
    warning: List[LabelResult],
    clean_labels: List[LabelResult],
) -> None:
    """Print summary section of the benchmark report."""
    print("\n" + "=" * 100)
    print("SUMMARY")
    print("=" * 100)
    total_fp = sum(r.total_false_positives for r in results)
    print(f"   Total false positives:  {total_fp}")
    print(f"   UNUSABLE labels (>=5):  {len(unusable)}")
    print(f"   WARNING labels (2-4):   {len(warning)}")
    print(f"   CLEAN labels (0-1):     {len(clean_labels)}")
    print()

    if unusable:
        print("   UNUSABLE labels to consider disabling:")
        for r in unusable:
            print(f"      - {r.pii_type:<30} ({r.total_false_positives} FP) label='{r.label}'")

    if warning:
        print("\n   WARNING labels to monitor:")
        for r in warning:
            print(f"      - {r.pii_type:<30} ({r.total_false_positives} FP) label='{r.label}'")

    print("\n" + "=" * 100)


def _print_report(results: List[LabelResult]) -> None:
    """Print a human-readable benchmark report."""
    print("\n" + "=" * 100)
    print("GLINER FALSE-POSITIVE BENCHMARK REPORT")
    print(f"Threshold: {THRESHOLD} | Clean texts: {len(CLEAN_TEXTS)} | Labels tested: {len(results)}")
    print("=" * 100)

    ranked = sorted(results, key=lambda r: r.total_false_positives, reverse=True)

    print(f"\n{'PII Type':<30} {'Label':<50} {'FP':>4} {'Verdict':>12}")
    print("-" * 100)

    unusable = []
    warning = []
    clean_labels = []

    for r in ranked:
        if r.total_false_positives >= 5:
            verdict = "UNUSABLE"
            unusable.append(r)
        elif r.total_false_positives >= 2:
            verdict = "WARNING"
            warning.append(r)
        else:
            verdict = "OK" if r.total_false_positives == 0 else "MINOR"
            clean_labels.append(r)

        print(f"{r.pii_type:<30} {r.label:<50} {r.total_false_positives:>4} {verdict:>12}")

    _print_false_positive_details(ranked)
    _print_summary(results, unusable, warning, clean_labels)


@pytest.fixture(scope="module")
def gliner_model():
    """Load GLiNER model once for all tests in this module."""
    print("\n[SETUP] Loading GLiNER model...")
    detector = GLiNERDetector()
    detector.load_model()
    print("[SETUP] Model loaded successfully")
    return detector.model


@pytest.mark.integration
@pytest.mark.slow
def test_gliner_false_positive_benchmark(gliner_model):
    """
    Benchmark each GLiNER label for false positives on clean texts.

    For each of the 40 GLiNER labels:
    1. Run prediction with ONLY that label against 10 clean professional texts
    2. Count every detection as a false positive
    3. Produce a ranked report identifying unusable labels

    Labels with >= 5 false positives are flagged UNUSABLE.
    Labels with 2-4 false positives are flagged WARNING.
    """
    print("\n" + "=" * 100)
    print("STARTING GLINER FALSE-POSITIVE BENCHMARK")
    print(f"Labels: {len(GLINER_LABELS)} | Texts: {len(CLEAN_TEXTS)} | Threshold: {THRESHOLD}")
    print("=" * 100)

    results: List[LabelResult] = []
    total_start = time.time()

    for idx, (pii_type, label) in enumerate(GLINER_LABELS.items(), 1):
        print(f"   [{idx:2}/{len(GLINER_LABELS)}] Testing '{pii_type}' (label='{label}')...", end="", flush=True)
        result = _run_single_label(gliner_model, pii_type, label, THRESHOLD)
        results.append(result)
        status = "OK" if result.total_false_positives == 0 else f"{result.total_false_positives} FP"
        print(f" {status} ({result.elapsed_seconds:.2f}s)")

    total_elapsed = time.time() - total_start
    print(f"\nBenchmark completed in {total_elapsed:.1f}s")

    _print_report(results)

    # Soft assertion: log but don't fail — the purpose is reporting
    total_fp = sum(r.total_false_positives for r in results)
    unusable_count = sum(1 for r in results if r.total_false_positives >= 5)

    print(f"\n[ASSERT] Total FP: {total_fp}, Unusable labels: {unusable_count}")
    # We don't hard-fail; the report IS the deliverable


if __name__ == "__main__":
    print("Loading GLiNER model...")
    detector = GLiNERDetector()
    detector.load_model()
    print("Model loaded.\n")
    test_gliner_false_positive_benchmark(detector.model)
