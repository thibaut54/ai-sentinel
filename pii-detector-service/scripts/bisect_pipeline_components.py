"""
Bisection forward des composants downstream du pipeline pii-detector.

Objectif : on a deja prouve que GLiNER + chunker subword neutre atteint la
parite (141 findings @ threshold=0.5, 109% recall vs NVIDIA hosted, cf
``standalone_docling_gliner_scan.py``). En prod on n'a que 78 findings.
Le delta vient de composants downstream entre l'inference brute et le retour
gRPC.

Strategie : partir du baseline standalone et **ajouter un composant a la fois**.
Le step ou le recall chute brutalement identifie le coupable.

Steps implementes en Python pur :
    S0  baseline standalone (raw + semchunk + predict_entities @ threshold)
    S1  + HtmlContentParser-like clean (whitespace normalize)
    S2  swap chunker semchunk -> GlinerSubwordChunker du projet
    S3  swap single-pass -> MultiPassGlinerDetector.detect_pii() (multi-pass
        + conflict resolver inclus dans le detecteur)
    S4  + DetectionMerger.merge() par-dessus S3

Steps non implementes ici (necessitent stack DB/LLM, mieux teste via gRPC) :
    S5  + post-filter par type (pii_service._filter_entities_by_type_config)
    S6  + threshold DB par type (charge depuis pii_type_config table)
    S7  + validateur LLM (Gemma)

Pour S5-S7 : utiliser ``scripts/probe_docanno_grpc.py`` avec differentes
configurations DB (kill switches via SQL UPDATE sur pii_type_config).

Usage :
    cd pii-detector-service
    .venv\\Scripts\\activate
    python scripts/bisect_pipeline_components.py
    python scripts/bisect_pipeline_components.py --threshold 0.8 --steps 0,1,2
    python scripts/bisect_pipeline_components.py --only 3   # un seul step
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

# Ajouter pii-detector-service au sys.path pour les imports projet
REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = REPO_ROOT / "pii-detector-service"
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

# 13 labels NVIDIA + mapping vers nos pii_types (aligne sur CorpusBenchmarkIT)
PARITY_LABEL_TO_PII_TYPE: dict[str, str] = {
    "customer_id": "ACCOUNT_ID",
    "api_key": "API_KEY",
    "account_number": "BANK_ACCOUNT_NUMBER",
    "swift_bic": "BIC_SWIFT",
    "device_identifier": "DEVICE_ID",
    "certificate_license_number": "DRIVER_LICENSE_NUMBER",
    "health_plan_beneficiary_number": "HEALTH_INSURANCE_NUMBER",
    "medical_record_number": "MEDICAL_RECORD_NUMBER",
    "national_id": "NATIONAL_ID",
    "password": "PASSWORD",
    "http_cookie": "SESSION_ID",
    "ssn": "SSN",
    "license_plate": "LICENSE_PLATE",
}
PII_TYPE_TO_LABEL = {v: k for k, v in PARITY_LABEL_TO_PII_TYPE.items()}
NVIDIA_LABELS = list(PARITY_LABEL_TO_PII_TYPE.keys())

GLINER_MODEL = "nvidia/gliner-PII"
DEFAULT_THRESHOLD = 0.8
DEFAULT_CHUNK_SIZE = 384
DEFAULT_OVERLAP = 120

DEFAULT_INPUT = REPO_ROOT / "my-files" / "confluence-pii-test-document-docanno.txt"
DEFAULT_REFERENCE = REPO_ROOT / "my-files" / "confluence-pii-test-document-nvidia-gliner-result.json"

_log = logging.getLogger("bisect-pipeline")


# --------------------------------------------------------------------------- #
# 0. Utils                                                                    #
# --------------------------------------------------------------------------- #


def _load_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _build_gliner_model() -> Any:
    from gliner import GLiNER
    _log.info("Chargement GLiNER : %s", GLINER_MODEL)
    return GLiNER.from_pretrained(GLINER_MODEL)


def _get_tokenizer(model: Any) -> Any:
    try:
        return model.data_processor.transformer_tokenizer
    except AttributeError:
        from transformers import AutoTokenizer
        return AutoTokenizer.from_pretrained(GLINER_MODEL)


# --------------------------------------------------------------------------- #
# 1. HtmlContentParser-like clean (clone Python pour le whitespace)           #
# --------------------------------------------------------------------------- #
# Approximation des trois regex finales de HtmlContentParser.cleanText() :
# le doc test n'ayant aucun tag HTML (verifie : 0 occurrence de "<"), seul
# l'impact whitespace normalize est pertinent ici. Si le doc avait du HTML,
# il faudrait invoquer Jsoup -- non disponible en Python.
_SPACES_AROUND_NEWLINES = re.compile(r"[ \t]*\n[ \t]*")
_MULTIPLE_NEWLINES = re.compile(r"\n{2,}")
_MULTIPLE_SPACES = re.compile(r"[ \t]{2,}")


def html_parser_clean(text: str) -> str:
    """Reproduction Python des 3 etapes finales de HtmlContentParser.cleanText.

    Ne fait PAS le parse Jsoup (pas d'equivalent stdlib leger). Acceptable
    uniquement quand le texte d'entree n'a pas de tags HTML -- a verifier en
    amont. Suffit pour le doc de test docanno.
    """
    if not text:
        return text
    out = _SPACES_AROUND_NEWLINES.sub("\n", text)
    out = _MULTIPLE_NEWLINES.sub("\n\n", out)
    out = _MULTIPLE_SPACES.sub(" ", out)
    return out.strip()


# --------------------------------------------------------------------------- #
# 2. Chunkers : semchunk neutre vs GlinerSubwordChunker projet                #
# --------------------------------------------------------------------------- #


def chunk_semchunk(text: str, tokenizer: Any, chunk_size: int, overlap: int) -> list[str]:
    """Chunker neutre via semchunk (lib externe)."""
    import semchunk
    chunker = semchunk.chunkerify(tokenizer, chunk_size)
    try:
        chunks, _offsets = chunker(text, offsets=True, overlap=overlap if overlap > 0 else None)
    except TypeError:
        chunks, _offsets = chunker(text, offsets=True)
    return list(chunks)


def chunk_projet(text: str, tokenizer: Any, chunk_size: int, overlap: int) -> list[str]:
    """Chunker du projet : GlinerSubwordChunker."""
    from pii_detector.infrastructure.text_processing.semantic_chunker import GlinerSubwordChunker
    chunker = GlinerSubwordChunker(tokenizer=tokenizer, chunk_size=chunk_size, overlap=overlap)
    results = chunker.chunk_text(text)
    return [r.text for r in results]


# --------------------------------------------------------------------------- #
# 3. Predictions                                                              #
# --------------------------------------------------------------------------- #


def predict_per_chunk(model: Any, chunks: list[str], labels: list[str], threshold: float) -> list[dict]:
    """Boucle naive predict_entities par chunk, pas de merger, pas de dedup."""
    findings: list[dict] = []
    for idx, c in enumerate(chunks):
        if not c:
            continue
        ents = model.predict_entities(c, labels, threshold=threshold)
        for e in ents:
            e["_chunk_idx"] = idx
        findings.extend(ents)
    return findings


def predict_via_multipass(text: str, threshold: float) -> tuple[list[dict], Any]:
    """Step S3 : utilise MultiPassGlinerDetector du projet (multi-pass + conflict resolver).

    Retourne (findings_as_dicts, raw_pii_entities) pour permettre le step S4
    qui composte par-dessus.
    """
    from pii_detector.infrastructure.detector.multi_pass_gliner_detector import (
        MultiPassGlinerDetector,
    )

    detector = MultiPassGlinerDetector()
    detector._gliner_detector.load_model()

    # Mock pii_type_configs minimal pour les 13 NVIDIA labels (en ALL detector pour ne pas
    # filtrer par detector type, et enabled=True).
    pii_type_configs = {
        pii_type: {
            "enabled": True,
            "detector": "GLINER",
            "detector_label": label,
            "threshold": threshold,
        }
        for label, pii_type in PARITY_LABEL_TO_PII_TYPE.items()
    }

    entities = detector.detect_pii(
        text,
        threshold=threshold,
        pii_type_configs=pii_type_configs,
        chunk_size=len(NVIDIA_LABELS) + 1,  # tous les labels dans une seule passe pour fair-compare
    )

    # Convertit PIIEntity -> dict avec label NVIDIA pour matcher le tableau de bord
    findings: list[dict] = []
    for ent in entities:
        pt = getattr(ent, "pii_type", None) or ent["pii_type"]
        findings.append({
            "label": PII_TYPE_TO_LABEL.get(pt, pt),
            "text": ent["text"],
            "start": ent["start"],
            "end": ent["end"],
            "score": float(ent["score"]),
        })
    return findings, entities


def apply_detection_merger(entities_from_detector: Any) -> list[dict]:
    """Step S4 : applique DetectionMerger par-dessus le resultat de S3."""
    from pii_detector.domain.service.detection_merger import DetectionMerger

    # DetectionMerger.merge() veut une liste de (detector, entities) tuples.
    # On simule un detector unique. Une protocol stub suffit (juste model_id attribute).
    class _StubDetector:
        model_id = "gliner-multipass"
    merger = DetectionMerger()
    merged = merger.merge([(_StubDetector(), entities_from_detector)])
    return [{
        "label": PII_TYPE_TO_LABEL.get(getattr(e, "pii_type", None) or e["pii_type"], "?"),
        "text": e["text"],
        "start": e["start"],
        "end": e["end"],
        "score": float(e["score"]),
    } for e in merged]


# --------------------------------------------------------------------------- #
# 4. Reference NVIDIA + reporting                                             #
# --------------------------------------------------------------------------- #


def load_expected(reference: Path) -> Counter:
    if not reference.exists():
        return Counter()
    raw = json.loads(reference.read_text(encoding="utf-8"))
    findings = []
    if isinstance(raw, dict) and raw.get("choices"):
        content = raw["choices"][0].get("message", {}).get("content")
        if isinstance(content, str):
            try:
                findings = json.loads(content)
            except json.JSONDecodeError:
                findings = []
        elif isinstance(content, list):
            findings = content
    counts: Counter = Counter()
    for f in findings:
        if isinstance(f, dict):
            label = f.get("label") or f.get("type")
            if label:
                counts[str(label)] += 1
    return counts


def print_bisection(rows: list[dict]) -> None:
    """Affiche le tableau de bisection."""
    print("\n" + "=" * 100)
    print("BISECTION FORWARD - composants downstream pii-detector")
    print("=" * 100)
    header = f"{'step':<5} {'setup':<55} {'findings':>10} {'delta':>8} {'recall%':>10}"
    print(header)
    print("-" * 100)
    r0 = rows[0]["findings"] if rows else 0
    for i, row in enumerate(rows):
        delta = row["findings"] - rows[i - 1]["findings"] if i > 0 else 0
        recall = (100.0 * row["findings"] / r0) if r0 else 0.0
        delta_str = f"{delta:+d}" if i > 0 else "  --"
        print(f"S{row['step']:<4} {row['setup']:<55} {row['findings']:>10} {delta_str:>8} {recall:>9.1f}%")
    print("-" * 100)
    print("Lecture : `delta` = findings(step_N) - findings(step_N-1). Une chute brutale identifie le coupable.")


def per_label_counts(findings: list[dict]) -> Counter:
    return Counter(str(f.get("label", "?")) for f in findings)


# --------------------------------------------------------------------------- #
# 5. Orchestration                                                            #
# --------------------------------------------------------------------------- #


def run_step(
    step: int,
    text_raw: str,
    model: Any,
    tokenizer: Any,
    threshold: float,
    chunk_size: int,
    overlap: int,
) -> dict:
    """Execute un step et retourne {'step', 'setup', 'findings', 'by_label'}."""
    if step == 0:
        chunks = chunk_semchunk(text_raw, tokenizer, chunk_size, overlap)
        findings = predict_per_chunk(model, chunks, NVIDIA_LABELS, threshold)
        return {"step": 0, "setup": "baseline (raw + semchunk + predict)",
                "findings": len(findings), "by_label": per_label_counts(findings)}

    if step == 1:
        cleaned = html_parser_clean(text_raw)
        chunks = chunk_semchunk(cleaned, tokenizer, chunk_size, overlap)
        findings = predict_per_chunk(model, chunks, NVIDIA_LABELS, threshold)
        return {"step": 1, "setup": "+ HtmlContentParser clean (whitespace norm)",
                "findings": len(findings), "by_label": per_label_counts(findings)}

    if step == 2:
        cleaned = html_parser_clean(text_raw)
        chunks = chunk_projet(cleaned, tokenizer, chunk_size, overlap)
        findings = predict_per_chunk(model, chunks, NVIDIA_LABELS, threshold)
        return {"step": 2, "setup": "swap chunker -> GlinerSubwordChunker projet",
                "findings": len(findings), "by_label": per_label_counts(findings)}

    if step == 3:
        cleaned = html_parser_clean(text_raw)
        findings, _raw = predict_via_multipass(cleaned, threshold)
        return {"step": 3, "setup": "swap -> MultiPassGlinerDetector (+ conflict resolver)",
                "findings": len(findings), "by_label": per_label_counts(findings)}

    if step == 4:
        cleaned = html_parser_clean(text_raw)
        _findings, raw_entities = predict_via_multipass(cleaned, threshold)
        merged = apply_detection_merger(raw_entities)
        return {"step": 4, "setup": "+ DetectionMerger.merge() (IoU overlap resolution)",
                "findings": len(merged), "by_label": per_label_counts(merged)}

    raise ValueError(f"step {step} non implemente en Python (cf doc pour S5-S7 via gRPC)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    p.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    p.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    p.add_argument("--chunk-size", type=int, default=DEFAULT_CHUNK_SIZE)
    p.add_argument("--overlap", type=int, default=DEFAULT_OVERLAP)
    p.add_argument("--steps", type=str, default="0,1,2,3,4",
                   help="Liste de steps separes par virgules, e.g. 0,1,2")
    p.add_argument("--only", type=int, default=None, help="Lance un seul step (debug).")
    return p.parse_args()


def main() -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    args = parse_args()

    if not args.input.exists():
        _log.error("Input introuvable : %s", args.input)
        return 2

    text_raw = _load_text(args.input)
    _log.info("Input : %d chars", len(text_raw))

    model = _build_gliner_model()
    tokenizer = _get_tokenizer(model)

    if args.only is not None:
        steps = [args.only]
    else:
        steps = [int(s.strip()) for s in args.steps.split(",") if s.strip()]
    _log.info("Steps a executer : %s", steps)

    rows: list[dict] = []
    for step in steps:
        _log.info("=== Step S%d ===", step)
        try:
            result = run_step(step, text_raw, model, tokenizer,
                              args.threshold, args.chunk_size, args.overlap)
            rows.append(result)
            _log.info("S%d : %d findings", step, result["findings"])
        except Exception as exc:  # noqa: BLE001
            _log.exception("S%d a echoue : %s", step, exc)
            rows.append({"step": step, "setup": f"FAILED: {exc}", "findings": 0, "by_label": Counter()})

    print_bisection(rows)

    # Detail par label sur le step final
    if rows:
        last = rows[-1]
        print(f"\n=== Detail par label - step S{last['step']} ===")
        print(f"{'label':<40} {'count':>10}")
        print("-" * 52)
        for lab in NVIDIA_LABELS:
            print(f"{lab:<40} {last['by_label'].get(lab, 0):>10}")

    # NVIDIA reference (informatif, pas une cible directe car threshold different)
    expected = load_expected(args.reference)
    if expected and rows:
        print("\n=== Reference NVIDIA hosted (informatif, threshold inconnu) ===")
        total_e = sum(expected.values())
        print(f"  Total NVIDIA hosted findings: {total_e}")

    print("\nProchaines etapes (steps S5-S7) : modifier la config DB via SQL puis lancer "
          "scripts/probe_docanno_grpc.py pour comparer. Voir docstring du script.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
