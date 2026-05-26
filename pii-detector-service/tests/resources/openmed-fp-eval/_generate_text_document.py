"""One-shot generator: turn the 12 JSON fixtures into a single plain-text
document for OpenMed + LLM judge evaluation, paired with a sidecar JSON
annotations file used by the test as ground-truth.

The two-file design is intentional: the ``.txt`` looks like an actual
Confluence export (sections, paragraphs, mixed PII and noise on the same
page) and contains zero test metadata; the test loads it the same way the
production pipeline reads a Confluence page. The ground-truth lives next
to it in ``.annotations.json``, keyed by line number, so any edit to the
text remains traceable.

Run:

    cd pii-detector-service
    python tests/resources/openmed-fp-eval/_generate_text_document.py

Outputs:

    tests/resources/openmed-pii-test-document.txt
    tests/resources/openmed-pii-test-document.annotations.json

Annotations schema (one entry per annotated line)::

    {
      "document": "tests/resources/openmed-pii-test-document.txt",
      "total_lines": 289,
      "total_tp": 132,
      "total_fp": 157,
      "lines": [
        {"line_number": 33, "pii_type": "PASSWORD", "verdict": "TP",
         "value": "Tr0ub4dor!2024"},
        {"line_number": 41, "pii_type": "PASSWORD", "verdict": "FP",
         "value": null},
        ...
      ]
    }
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional

FIXTURES_DIR = Path(__file__).resolve().parent
OUTPUT_PATH = FIXTURES_DIR.parent / "openmed-pii-test-document.txt"
ANNOTATIONS_PATH = (
    FIXTURES_DIR.parent / "openmed-pii-test-document.annotations.json"
)

# Order kept in sync with EVALUATED_TYPES in
# tests/integration/test_openmed_realistic_fp_evaluation.py
EVALUATED_TYPES: List[str] = [
    "PASSWORD",
    "CVV",
    "PIN",
    "IMEI",
    "BITCOIN_ADDRESS",
    "ETHEREUM_ADDRESS",
    "LITECOIN_ADDRESS",
    "VEHICLE_VIN",
    "VEHICLE_REGISTRATION",
    "ACCOUNT_NAME",
    "BANK_ACCOUNT",
    "CREDIT_CARD",
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
DOCUMENT DE TEST PII — AI Sentinel
================================================================================

Ce document concatene des extraits techniques representatifs d'une page
Confluence interne : procedures, politiques de securite, notes de mise en
production, rapports d'incident, conventions de nommage. Il melange
volontairement des donnees personnelles veritables (mots de passe,
numeros de carte, identifiants bancaires, etc.) et des elements de
contexte qui ressemblent a des PII sans en etre (codes de tickets, hash,
identifiants techniques, noms de projet, references documentaires).

Il est destine a etre uploade sur Confluence ou consomme directement par
le pipeline de detection PII (OpenMed + LLM judge) pour mesurer le taux
de vrais positifs detectes et le taux de faux positifs rejetes.

================================================================================

"""


def main() -> None:
    # Build the document line by line so the annotation's `line_number`
    # is the exact 1-based index in the final file. ``emit()`` returns the
    # line number that was just written so callers can attach an annotation
    # to it without any manual offset arithmetic.
    output_lines: List[str] = []

    def emit(text: str = "") -> int:
        """Append ``text`` as one line, return its 1-based line number."""
        # Allow callers to pass multi-line blocks; expand them so each
        # physical line becomes its own counted entry.
        last_lineno = 0
        for piece in text.split("\n"):
            output_lines.append(piece)
            last_lineno = len(output_lines)
        return last_lineno

    annotations: List[Dict] = []
    total_tp = 0
    total_fp = 0

    # Header block (no annotations).
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

    # Sanity check: every annotation's line_number resolves to a line that
    # contains the expected value (for TPs) — catches off-by-one in emit().
    file_lines = document_text.split("\n")
    misaligned: List[str] = []
    for ann in annotations:
        if ann["verdict"] != "TP":
            continue
        line = file_lines[ann["line_number"] - 1] if ann["line_number"] - 1 < len(file_lines) else ""
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
