"""One-shot generator: turn the 12 JSON fixtures into a single human-readable
``.txt`` document for OpenMed + LLM judge evaluation.

Format conventions
------------------

Each entry sits on a single line and starts with one of:

* ``[TP value=X]`` — OpenMed MUST detect ``X`` somewhere on the line.
  The LLM judge MUST keep that detection (TP preserved).
* ``[FP]``         — no PII detection is expected on the line. If OpenMed
  flags anything, the LLM judge MUST drop it (FP rejected).

Multi-line ``long_context`` cases are collapsed to a single line by replacing
internal newlines with a small ``\\n`` literal placeholder so the txt file
remains line-oriented and easy to parse / grep.

Run:

    cd pii-detector-service
    python tests/resources/openmed-fp-eval/_generate_text_document.py

Output:

    tests/resources/openmed-pii-test-document.txt
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

FIXTURES_DIR = Path(__file__).resolve().parent
OUTPUT_PATH = FIXTURES_DIR.parent / "openmed-pii-test-document.txt"

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
    """Collapse newlines so each case fits on one line of the txt document."""
    return text.replace("\r\n", "\n").replace("\n", " \\n ").strip()


def _case_marker(case: Dict) -> str:
    """Build the ``[TP value=X]`` / ``[FP]`` prefix for a case."""
    expected = case.get("expected_spans") or []
    if not expected:
        return "[FP]"
    # When several expected spans exist on the same line we only annotate the
    # first one — the test still extracts the others by IoU matching against
    # what OpenMed reports.
    value = expected[0]["value"]
    # Escape a literal "]" inside the value so the prefix stays parseable.
    safe = value.replace("]", "\\]")
    return f"[TP value={safe}]"


def _section_header(idx: int, pii_type: str) -> str:
    bar = "=" * 80
    return f"{bar}\nSECTION {idx} — {pii_type}\n{bar}\n"


def _load_cases(pii_type: str) -> List[Dict]:
    path = FIXTURES_DIR / f"{pii_type}.json"
    with path.open("r", encoding="utf-8") as fp:
        payload = json.load(fp)
    return payload["cases"]


HEADER = """\
================================================================================
DOCUMENT DE TEST OpenMed + LLM Judge — Pipeline FP/FN
================================================================================
Ce document concatene les fixtures JSON utilisees par
``test_openmed_realistic_fp_evaluation*.py`` sous un format texte plat. Il
sert au test ``test_openmed_pii_text_document_with_judge.py`` qui scanne le
fichier entier avec OpenMed + LLM judge et reconstruit le verdict
TP/FP/FN par ligne en lisant les marqueurs ci-dessous.

Conventions de marquage (debut de ligne) :

  [TP value=X] : OpenMed DOIT detecter la valeur X quelque part dans la
                 ligne. Le LLM judge DOIT conserver cette detection (recall
                 preserve).
  [FP]         : Aucune detection PII n'est attendue sur la ligne. Si
                 OpenMed flagge quelque chose, le LLM judge DOIT le rejeter
                 (FP elimine).

Source des annotations : tests/resources/openmed-fp-eval/*.json (12 types
PII, 6 axes d'evaluation : canonical_with_clue, canonical_no_clue,
look_alikes, explicit_negatives, adversarial_formatting, long_context, en
fr/en/de/it).

Les sauts de ligne internes des cas ``long_context`` ont ete remplaces par
le placeholder litteral \\n pour conserver une ligne = un cas.
================================================================================

"""


def main() -> None:
    lines: List[str] = [HEADER]
    total_tp = 0
    total_fp = 0
    for idx, pii_type in enumerate(EVALUATED_TYPES, start=1):
        cases = _load_cases(pii_type)
        lines.append(_section_header(idx, pii_type))
        tp_count = 0
        fp_count = 0
        for case in cases:
            marker = _case_marker(case)
            if marker == "[FP]":
                fp_count += 1
            else:
                tp_count += 1
            lines.append(f"{marker} {_flatten(case['text'])}")
        lines.append("")  # empty line between sections
        lines.append(
            f"# Section {pii_type} totals: TP={tp_count} FP={fp_count} "
            f"(total cases={len(cases)})"
        )
        lines.append("")
        total_tp += tp_count
        total_fp += fp_count

    lines.append("")
    lines.append(
        f"# Document totals across {len(EVALUATED_TYPES)} sections: "
        f"TP={total_tp} FP={total_fp} (total cases={total_tp + total_fp})"
    )
    lines.append("")

    OUTPUT_PATH.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH} ({OUTPUT_PATH.stat().st_size} bytes, "
          f"TP={total_tp} FP={total_fp})")


if __name__ == "__main__":
    main()
