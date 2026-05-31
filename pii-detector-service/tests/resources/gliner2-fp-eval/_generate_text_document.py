"""One-shot generator: turn the 24 GLiNER2 JSON fixtures into a single
plain-text document for the GLiNER2 + LLM judge document-wide evaluation,
paired with a sidecar JSON annotations file used as ground-truth.

GLiNER2 counterpart of ``openmed-fp-eval/_generate_text_document.py``: same
two-file design (a Confluence-like ``.txt`` with zero test metadata + a
``.annotations.json`` keyed by line number), but assembled from the 24
GLiNER2 privacy-filter types instead of OpenMed's 12. This keeps the
document-wide test scoped to the exact agreed label taxonomy — no
out-of-list annotation buckets.

Run:

    cd pii-detector-service
    python tests/resources/gliner2-fp-eval/_generate_text_document.py

Outputs:

    tests/resources/gliner2-pii-test-document.txt
    tests/resources/gliner2-pii-test-document.annotations.json
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional

FIXTURES_DIR = Path(__file__).resolve().parent
OUTPUT_PATH = FIXTURES_DIR.parent / "gliner2-pii-test-document.txt"
ANNOTATIONS_PATH = (
    FIXTURES_DIR.parent / "gliner2-pii-test-document.annotations.json"
)

# The 24 evaluated GLiNER2 types — kept in sync with GLINER2_LABEL_SPECS in
# tests/integration/test_gliner2_realistic_fp_evaluation_with_judge.py
# (Government/tax · Banking/payment · Digital identity · Secrets/credentials).
EVALUATED_TYPES: List[str] = [
    "GOVERNMENT_ID",
    "NATIONAL_ID",
    "PASSPORT",
    "DRIVER_LICENSE",
    "LICENSE_NUMBER",
    "TAX_ID",
    "TAX_NUMBER",
    "BANK_ACCOUNT",
    "ACCOUNT_NUMBER",
    "ROUTING_NUMBER",
    "IBAN",
    "PAYMENT_CARD",
    "CARD_NUMBER",
    "CARD_EXPIRY",
    "CARD_CVV",
    "USERNAME",
    "IP_ADDRESS",
    "ACCOUNT_ID",
    "SENSITIVE_ACCOUNT_ID",
    "PASSWORD",
    "SECRET",
    "API_KEY",
    "ACCESS_TOKEN",
    "RECOVERY_CODE",
]


def _flatten(text: str) -> str:
    """Collapse newlines so each case fits on one line of the document."""
    return text.replace("\r\n", "\n").replace("\n", " ").strip()


def _section_header(idx: int, pii_type: str) -> str:
    bar = "=" * 80
    pretty = pii_type.replace("_", " ").title()
    return f"{bar}\nSECTION {idx} — {pretty}\n{bar}\n"


def _expected_value(case: Dict) -> Optional[str]:
    """Return the first expected-span value when the case is a TP, else None."""
    expected = case.get("expected_spans") or []
    if not expected:
        return None
    return expected[0]["value"]


def _load_cases(pii_type: str) -> List[Dict]:
    path = FIXTURES_DIR / f"{pii_type}.json"
    with path.open("r", encoding="utf-8") as fp:
        payload = json.load(fp)
    return payload["cases"]


HEADER = """\
================================================================================
DOCUMENT DE TEST PII (GLiNER2) — AI Sentinel
================================================================================

Ce document concatene des extraits techniques representatifs d'une page
Confluence interne : procedures, politiques de securite, notes de mise en
production, rapports d'incident, conventions de nommage. Il melange
volontairement des donnees personnelles veritables (identifiants etatiques,
numeros de compte/carte, identifiants techniques, secrets) et des elements de
contexte qui ressemblent a des PII sans en etre (codes de tickets, hash,
identifiants techniques, noms de projet, references documentaires).

Il est destine a etre uploade sur Confluence ou consomme directement par
le pipeline de detection PII (GLiNER2 + LLM judge) pour mesurer le taux
de vrais positifs detectes et le taux de faux positifs rejetes, sur les 24
types GLiNER2 evalues.

================================================================================

"""


def main() -> None:
    # Build the document line by line so the annotation's `line_number`
    # is the exact 1-based index in the final file.
    output_lines: List[str] = []

    def emit(text: str = "") -> int:
        """Append ``text`` as one line, return its 1-based line number."""
        last_lineno = 0
        for piece in text.split("\n"):
            output_lines.append(piece)
            last_lineno = len(output_lines)
        return last_lineno

    annotations: List[Dict] = []
    total_tp = 0
    total_fp = 0

    emit(HEADER.rstrip("\n"))

    for idx, pii_type in enumerate(EVALUATED_TYPES, start=1):
        cases = _load_cases(pii_type)
        emit(_section_header(idx, pii_type).rstrip("\n"))

        for case in cases:
            value = _expected_value(case)
            verdict = "TP" if value is not None else "FP"
            line_text = _flatten(case["text"])
            ln = emit(line_text)
            annotations.append(
                {
                    "line_number": ln,
                    "pii_type": pii_type,
                    "verdict": verdict,
                    "value": value,
                    "axis": case.get("axis"),
                    "language": case.get("language"),
                    "case_id": case.get("id"),
                }
            )
            if verdict == "TP":
                total_tp += 1
            else:
                total_fp += 1

        emit()  # blank separator between sections

    document_text = "\n".join(output_lines)
    OUTPUT_PATH.write_text(document_text, encoding="utf-8")

    annotations_payload = {
        "document": str(OUTPUT_PATH.relative_to(FIXTURES_DIR.parent.parent)),
        "total_lines": len(output_lines),
        "total_tp": total_tp,
        "total_fp": total_fp,
        "lines": annotations,
    }
    ANNOTATIONS_PATH.write_text(
        json.dumps(annotations_payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )

    # Sanity check: every TP annotation's line contains the expected value.
    file_lines = document_text.split("\n")
    misaligned: List[str] = []
    for ann in annotations:
        if ann["verdict"] != "TP":
            continue
        line = (
            file_lines[ann["line_number"] - 1]
            if ann["line_number"] - 1 < len(file_lines)
            else ""
        )
        if ann["value"] not in line:
            misaligned.append(
                f"line={ann['line_number']} value={ann['value']!r} "
                f"actual_line={line[:80]!r}"
            )
    if misaligned:
        raise SystemExit(
            "Annotation/document alignment broken:\n"
            + "\n".join(f"  - {m}" for m in misaligned)
        )

    print(
        f"Wrote {OUTPUT_PATH.name} ({OUTPUT_PATH.stat().st_size} bytes, "
        f"{len(output_lines)} lines), "
        f"{ANNOTATIONS_PATH.name} ({ANNOTATIONS_PATH.stat().st_size} bytes); "
        f"TP={total_tp} FP={total_fp}, alignment OK."
    )


if __name__ == "__main__":
    main()
