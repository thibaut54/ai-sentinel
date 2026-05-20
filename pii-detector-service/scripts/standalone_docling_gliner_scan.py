"""
Standalone Docling + GLiNER scan -- from-scratch baseline.

Objectif : repartir de zero, sans aucune piece de notre pipeline custom
(chunker custom, multi-pass, merger, regex, presidio, post-filter, post-validators)
et voir si le probleme de faux negatifs massif sur
``confluence-pii-test-document-docanno.txt`` persiste.

Reference de verite :
    my-files/confluence-pii-test-document-nvidia-gliner-result.json
    (250 findings produits par le playground hosted NVIDIA sur le meme texte
    avec les memes 13 labels GLiNER).

Modele : ``nvidia/gliner-PII`` (le meme que celui charge par notre service).
Labels : les 13 labels NVIDIA, identiques a
    ``CorpusBenchmarkIT.PARITY_LABEL_TO_PII_TYPE``.

GLiNER tronque silencieusement a max_len=384 subword tokens (DeBERTa). Sur un
fichier de ~13k subword tokens, predict_entities() en un seul appel ignore
~97% du document. Pour garantir 100% coverage tout en restant "from-scratch",
on chunke avec ``semchunk`` (lib externe deja dans pyproject.toml, API
publique ``semchunk.chunkerify(tokenizer, chunk_size)``). On NE ramene PAS
``GlinerSubwordChunker`` du projet pour ne pas melanger le baseline avec notre
code applicatif.

Modes :
    --mode docling           Itere TextItems docling, predict_entities direct.
                             Risque de troncation sur TextItems > 384 tokens.
    --mode docling-chunked   Idem mais sub-chunk via semchunk si > chunk_size.
                             100% coverage. RECOMMANDE.
    --mode raw-chunked       Bypass docling, chunke le .txt brut via semchunk.
                             100% coverage, baseline le plus neutre.
    --mode whole-text        Un seul predict_entities sur le doc complet.
                             Demontre la troncation a des fins pedagogiques.

Usage :
    cd pii-detector-service
    .venv\\Scripts\\activate
    python scripts/standalone_docling_gliner_scan.py
    python scripts/standalone_docling_gliner_scan.py --mode raw-chunked
    python scripts/standalone_docling_gliner_scan.py --mode whole-text
    python scripts/standalone_docling_gliner_scan.py --threshold 0.3 --chunk-size 384 --overlap 64
"""
from __future__ import annotations

import argparse
import json
import logging
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

# --------------------------------------------------------------------------- #
# Constantes alignees sur CorpusBenchmarkIT.PARITY_LABEL_TO_PII_TYPE          #
# --------------------------------------------------------------------------- #

NVIDIA_PARITY_LABELS: list[str] = [
    "customer_id",
    "api_key",
    "account_number",
    "swift_bic",
    "device_identifier",
    "certificate_license_number",
    "health_plan_beneficiary_number",
    "medical_record_number",
    "national_id",
    "password",
    "http_cookie",
    "ssn",
    "license_plate",
]

GLINER_MODEL = "nvidia/gliner-PII"
DEFAULT_THRESHOLD = 0.8
DEFAULT_CHUNK_SIZE = 384  # GLiNER max_len pour nvidia/gliner-PII (DeBERTa)
DEFAULT_OVERLAP = 120

MODES = ("docling", "docling-chunked", "raw-chunked", "whole-text")
# raw-chunked par defaut : 100% coverage sans dependance docling.
# Pour matcher fidelement le tutoriel docling-gliner.md, passer --mode docling-chunked
# (necessite `pip install docling`).
DEFAULT_MODE = "raw-chunked"

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = REPO_ROOT / "my-files" / "confluence-pii-test-document-docanno.txt"
DEFAULT_REFERENCE = (
    REPO_ROOT / "my-files" / "confluence-pii-test-document-nvidia-gliner-result.json"
)

_log = logging.getLogger("standalone-docling-gliner")


# --------------------------------------------------------------------------- #
# 1. Chargement du texte                                                      #
# --------------------------------------------------------------------------- #


def _iter_docling_text_items(path: Path) -> list[str]:
    """Parse via docling et retourne la liste des TextItems (incl. cellules de table).

    Exactement la meme boucle que dans docling-gliner.md : chaque TextItem du
    DoclingDocument devient un chunk independant.
    """
    try:
        from docling.document_converter import DocumentConverter
        from docling_core.types.doc import TableItem, TextItem
    except ImportError as exc:
        raise RuntimeError(
            "docling n'est pas installe. `pip install docling` ou utiliser "
            "--mode raw-chunked / --mode whole-text."
        ) from exc

    _log.info("Parsing %s via docling DocumentConverter...", path.name)
    converter = DocumentConverter()
    result = converter.convert(str(path))
    doc = result.document

    chunks: list[str] = []
    for element, _level in doc.iterate_items():
        if isinstance(element, TextItem):
            text = (element.text or "").strip()
            if text:
                chunks.append(text)
        elif isinstance(element, TableItem):
            for cell in element.data.table_cells:
                cell_text = (cell.text or "").strip()
                if cell_text:
                    chunks.append(cell_text)

    _log.info("docling -> %d TextItem(s) extraits", len(chunks))
    return chunks


def _load_raw_text(path: Path) -> str:
    raw = path.read_text(encoding="utf-8")
    _log.info("Texte brut : %d chars", len(raw))
    return raw


# --------------------------------------------------------------------------- #
# 2. Chunking subword-aware via semchunk (lib externe, publique)              #
# --------------------------------------------------------------------------- #


def _build_semchunk_chunker(tokenizer: Any, chunk_size: int) -> Any:
    """Wraps semchunk.chunkerify() to keep dependency boundaries clean."""
    try:
        import semchunk
    except ImportError as exc:
        raise RuntimeError(
            "semchunk n'est pas installe. `pip install 'semchunk>=2.2.0'`."
        ) from exc
    return semchunk.chunkerify(tokenizer, chunk_size)


def _chunk_with_semchunk(
    text: str,
    chunker: Any,
    overlap: int,
) -> list[tuple[str, int, int]]:
    """Retourne (chunk_text, char_start, char_end) pour chaque chunk."""
    if not text:
        return []
    try:
        chunks, offsets = chunker(
            text,
            offsets=True,
            overlap=overlap if overlap > 0 else None,
        )
    except TypeError:
        # semchunk < 2.2 : pas de kwarg overlap
        chunks, offsets = chunker(text, offsets=True)
        _log.warning("semchunk installee sans support overlap : run sans chevauchement")
    return [(c, s, e) for c, (s, e) in zip(chunks, offsets)]


def _split_long_chunks(
    chunks: Iterable[str],
    tokenizer: Any,
    chunker: Any,
    overlap: int,
    max_len: int,
) -> list[str]:
    """Pour le mode docling-chunked : sub-chunke seulement les TextItems trop longs."""
    out: list[str] = []
    for idx, chunk in enumerate(chunks):
        n_tok = _count_subword_tokens(chunk, tokenizer)
        if n_tok <= max_len:
            out.append(chunk)
            continue
        sub = _chunk_with_semchunk(chunk, chunker, overlap)
        _log.info(
            "TextItem #%d trop long (%d tokens > %d) -> sub-chunke en %d morceaux",
            idx, n_tok, max_len, len(sub),
        )
        out.extend(s for s, _, _ in sub)
    return out


def _count_subword_tokens(text: str, tokenizer: Any) -> int:
    """Compte les subword tokens (sans special tokens, pour matcher semchunk)."""
    if not text:
        return 0
    try:
        ids = tokenizer.encode(text, add_special_tokens=False)
        return len(ids)
    except Exception:  # noqa: BLE001
        # Fallback grossier en cas de tokenizer recalcitrant
        return len(text) // 4


def _warn_truncation_risk(
    chunks: Iterable[str],
    tokenizer: Any,
    max_len: int,
) -> int:
    """Logue un warning par chunk depassant max_len. Retourne le nombre de risques."""
    risks = 0
    for idx, chunk in enumerate(chunks):
        n_tok = _count_subword_tokens(chunk, tokenizer)
        if n_tok > max_len:
            risks += 1
            _log.warning(
                "[TRUNCATION_RISK] chunk #%d a %d subword tokens > %d (GLiNER tronquera silencieusement)",
                idx, n_tok, max_len,
            )
    if risks:
        _log.warning("[TRUNCATION_RISK] %d chunk(s) seront tronques par GLiNER", risks)
    return risks


# --------------------------------------------------------------------------- #
# 3. Modele GLiNER + prediction                                               #
# --------------------------------------------------------------------------- #


def _build_gliner_model() -> Any:
    try:
        from gliner import GLiNER
    except ImportError as exc:
        raise RuntimeError(
            "gliner n'est pas installe. Run: pip install gliner torch "
            "--extra-index-url https://download.pytorch.org/whl/cpu"
        ) from exc
    _log.info("Chargement modele GLiNER : %s", GLINER_MODEL)
    return GLiNER.from_pretrained(GLINER_MODEL)


def _get_gliner_tokenizer(model: Any) -> Any:
    """Recupere le tokenizer HF embarque dans GLiNER (zero discrepancy d'unite)."""
    try:
        return model.data_processor.transformer_tokenizer
    except AttributeError:
        from transformers import AutoTokenizer
        _log.info("Fallback AutoTokenizer.from_pretrained(%s)", GLINER_MODEL)
        return AutoTokenizer.from_pretrained(GLINER_MODEL)


def _predict_chunks(
    model: Any,
    chunks: list[str],
    labels: list[str],
    threshold: float,
) -> list[dict[str, Any]]:
    """Boucle naive : un appel predict_entities par chunk. Pas de merger, pas de dedup."""
    all_findings: list[dict[str, Any]] = []
    for idx, chunk in enumerate(chunks):
        if not chunk:
            continue
        try:
            entities = model.predict_entities(chunk, labels, threshold=threshold)
        except Exception as exc:  # noqa: BLE001
            _log.error("predict_entities echoue sur chunk %d (len=%d): %s", idx, len(chunk), exc)
            continue
        for ent in entities:
            ent["_chunk_idx"] = idx
        all_findings.extend(entities)
        if idx and idx % 20 == 0:
            _log.info("  chunk %d/%d, +%d entites (cumul=%d)",
                      idx, len(chunks), len(entities), len(all_findings))
    return all_findings


# --------------------------------------------------------------------------- #
# 4. Lecture des comptes attendus depuis le JSON NVIDIA                       #
# --------------------------------------------------------------------------- #


def _extract_expected_counts(reference_json: Path) -> Counter:
    if not reference_json.exists():
        _log.warning("Reference JSON introuvable : %s", reference_json)
        return Counter()

    raw = json.loads(reference_json.read_text(encoding="utf-8"))

    if isinstance(raw, list):
        return _count_findings(raw)

    if isinstance(raw, dict):
        choices = raw.get("choices") or []
        if choices and isinstance(choices, list):
            content = choices[0].get("message", {}).get("content")
            findings = _coerce_to_findings_list(content)
            if findings:
                return _count_findings(findings)
        for key in ("entities", "findings", "results"):
            findings = _coerce_to_findings_list(raw.get(key))
            if findings:
                return _count_findings(findings)

    _log.error("Shape JSON reference inattendue ; aucun finding extrait")
    return Counter()


def _coerce_to_findings_list(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, list):
        return [v for v in value if isinstance(v, dict)]
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return []
        if isinstance(parsed, list):
            return [v for v in parsed if isinstance(v, dict)]
        if isinstance(parsed, dict):
            for key in ("entities", "findings", "results"):
                if key in parsed and isinstance(parsed[key], list):
                    return [v for v in parsed[key] if isinstance(v, dict)]
    return []


def _count_findings(findings: list[dict[str, Any]]) -> Counter:
    counts: Counter = Counter()
    for f in findings:
        label = f.get("label") or f.get("type") or f.get("entity_type")
        if label:
            counts[str(label)] += 1
    return counts


# --------------------------------------------------------------------------- #
# 5. Reporting                                                                #
# --------------------------------------------------------------------------- #


def _print_detected(detected: Counter, all_labels: list[str]) -> None:
    print("\n=== Detection counts (from-scratch GLiNER, sans pipeline custom) ===")
    print(f"{'label':<40} {'count':>10}")
    print("-" * 52)
    total = 0
    for lab in all_labels:
        c = detected.get(lab, 0)
        total += c
        print(f"{lab:<40} {c:>10}")
    extras = sorted(set(detected) - set(all_labels))
    for lab in extras:
        c = detected[lab]
        total += c
        print(f"{lab:<40} {c:>10}  (hors NVIDIA_PARITY_LABELS)")
    print("-" * 52)
    print(f"{'TOTAL':<40} {total:>10}")


def _print_comparison(detected: Counter, expected: Counter) -> None:
    print("\n=== Parite from-scratch vs NVIDIA hosted playground ===")
    print(f"{'label':<40} {'detected':>10} {'expected':>10} {'recall%':>10}")
    print("-" * 75)
    all_labels = sorted(set(detected) | set(expected))
    total_d = total_e = 0
    for lab in all_labels:
        d = detected.get(lab, 0)
        e = expected.get(lab, 0)
        total_d += d
        total_e += e
        recall_str = f"{(100.0 * d / e):>9.1f}%" if e else "       n/a"
        print(f"{lab:<40} {d:>10} {e:>10} {recall_str}")
    print("-" * 75)
    overall_str = f"{(100.0 * total_d / total_e):>9.1f}%" if total_e else "       n/a"
    print(f"{'TOTAL':<40} {total_d:>10} {total_e:>10} {overall_str}")


def _dump_sample(findings: list[dict[str, Any]], n: int = 25) -> None:
    print(f"\n=== Echantillon : {min(n, len(findings))} premieres entites ===")
    for ent in findings[:n]:
        text = ent.get("text") or ""
        snippet = text[:80].replace("\n", " ")
        score = ent.get("score", 0.0)
        chunk = ent.get("_chunk_idx", "-")
        print(f"  [{ent.get('label', '?'):<35}] score={score:.3f} chunk={chunk} text={snippet!r}")


# --------------------------------------------------------------------------- #
# 6. Orchestration des modes                                                  #
# --------------------------------------------------------------------------- #


def _build_chunks_for_mode(
    mode: str,
    input_path: Path,
    tokenizer: Any,
    chunk_size: int,
    overlap: int,
) -> list[str]:
    if mode == "whole-text":
        return [_load_raw_text(input_path)]

    if mode == "raw-chunked":
        text = _load_raw_text(input_path)
        chunker = _build_semchunk_chunker(tokenizer, chunk_size)
        chunks_with_offsets = _chunk_with_semchunk(text, chunker, overlap)
        _log.info("raw-chunked : %d chunks (chunk_size=%d, overlap=%d)",
                  len(chunks_with_offsets), chunk_size, overlap)
        return [c for c, _, _ in chunks_with_offsets]

    # docling / docling-chunked : fallback gracieux sur raw-chunked si docling absent
    try:
        text_items = _iter_docling_text_items(input_path)
    except RuntimeError as exc:
        _log.warning("%s -- fallback automatique sur --mode raw-chunked.", exc)
        text = _load_raw_text(input_path)
        chunker = _build_semchunk_chunker(tokenizer, chunk_size)
        chunks_with_offsets = _chunk_with_semchunk(text, chunker, overlap)
        return [c for c, _, _ in chunks_with_offsets]

    if mode == "docling":
        return text_items

    # docling-chunked : sub-chunke uniquement les TextItems > chunk_size tokens
    chunker = _build_semchunk_chunker(tokenizer, chunk_size)
    return _split_long_chunks(text_items, tokenizer, chunker, overlap, chunk_size)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    parser.add_argument("--mode", choices=MODES, default=DEFAULT_MODE,
                        help=f"Strategie de decoupage (defaut: {DEFAULT_MODE}).")
    parser.add_argument("--chunk-size", type=int, default=DEFAULT_CHUNK_SIZE,
                        help="Taille max d'un chunk en subword tokens (defaut 384).")
    parser.add_argument("--overlap", type=int, default=DEFAULT_OVERLAP,
                        help="Overlap entre chunks en subword tokens (defaut 64).")
    parser.add_argument("--max-chunks", type=int, default=None,
                        help="Limite le nombre de chunks traites (debug).")
    return parser.parse_args()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    args = _parse_args()

    if not args.input.exists():
        _log.error("Fichier d'entree introuvable : %s", args.input)
        return 2

    _log.info("Mode : %s", args.mode)

    # 1. Charger GLiNER en premier pour reutiliser SON tokenizer (zero discrepancy d'unite)
    model = _build_gliner_model()
    tokenizer = _get_gliner_tokenizer(model)

    # 2. Construire les chunks selon le mode
    chunks = _build_chunks_for_mode(
        args.mode, args.input, tokenizer, args.chunk_size, args.overlap,
    )
    if args.max_chunks is not None:
        chunks = chunks[: args.max_chunks]
        _log.info("Tronque a %d chunks (--max-chunks)", len(chunks))
    if not chunks:
        _log.error("Aucun chunk a traiter.")
        return 3

    total_chars = sum(len(c) for c in chunks)
    _log.info("Total : %d chunk(s), %d chars", len(chunks), total_chars)

    # 3. Verifier le risque de troncation pour ce mode
    risks = _warn_truncation_risk(chunks, tokenizer, args.chunk_size)
    if risks and args.mode in {"docling-chunked", "raw-chunked"}:
        _log.error(
            "Mode %s devrait garantir 0 troncation mais %d chunk(s) en risque "
            "-- bug dans le decoupage ?", args.mode, risks,
        )

    # 4. Predictions
    _log.info(
        "Run predict_entities(threshold=%.2f, %d labels) sur %d chunk(s)...",
        args.threshold, len(NVIDIA_PARITY_LABELS), len(chunks),
    )
    findings = _predict_chunks(model, chunks, NVIDIA_PARITY_LABELS, args.threshold)
    _log.info("Total findings : %d", len(findings))

    # 5. Reporting
    detected = Counter(str(f.get("label", "?")) for f in findings)
    _print_detected(detected, NVIDIA_PARITY_LABELS)

    expected = _extract_expected_counts(args.reference)
    if expected:
        _print_comparison(detected, expected)
    else:
        _log.warning("Pas de comptes attendus -> comparaison sautee.")

    _dump_sample(findings, n=25)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
