"""
POC: experimentation du modele OpenMed/privacy-filter-multilingual.

Telecharge le modele depuis HuggingFace Hub, charge le pipeline
`token-classification`, analyse un fichier de test et affiche les entites PII
detectees (avec scores, offsets, statistiques par categorie).

PREREQUIS IMPORTANT - transformers >= 5.0
==========================================
Le modele utilise :
    - tokenizer_class : `TokenizersBackend`
    - model_type       : `openai_privacy_filter`
    - architecture     : `OpenAIPrivacyFilterForTokenClassification`

Ces classes sont disponibles uniquement a partir de **transformers 5.x**
(introduit via plusieurs PR : #44294, #45813, #46091...). Avec transformers 4.x
on obtient l'erreur :
    ValueError: Tokenizer class TokenizersBackend does not exist or is not currently imported

Le venv principal `.venv` du projet utilise transformers 4.57 (compatible avec
GLiNER et l'inferences existante). Pour ce POC, un venv dedie a ete cree :
    ../.venv-openmed-poc  (transformers 5.9.0 + torch 2.12.0)

Lancement :
    cd pii-detector-service
    ../.venv-openmed-poc/Scripts/python.exe scripts/experiment_openmed_privacy_filter.py
    ../.venv-openmed-poc/Scripts/python.exe scripts/experiment_openmed_privacy_filter.py --file tests/resources/test-input.txt
    ../.venv-openmed-poc/Scripts/python.exe scripts/experiment_openmed_privacy_filter.py --device cuda --threshold 0.5

Installation initiale du venv dedie (si pas encore fait) :
    python -m venv ../.venv-openmed-poc
    ../.venv-openmed-poc/Scripts/python.exe -m pip install "transformers>=5.0" torch
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TEST_FILE = ROOT / "tests" / "resources" / "text-for-gliner.txt"
MODEL_ID = "OpenMed/privacy-filter-multilingual"
MAX_TOKENS_PER_CHUNK = 480  # marge sous la fenetre du tokenizer (128k mais on borne pour aggregation)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--file",
        type=Path,
        default=DEFAULT_TEST_FILE,
        help=f"Fichier texte a analyser (defaut: {DEFAULT_TEST_FILE.relative_to(ROOT)})",
    )
    parser.add_argument(
        "--model",
        type=str,
        default=MODEL_ID,
        help=f"Identifiant HuggingFace du modele (defaut: {MODEL_ID})",
    )
    parser.add_argument(
        "--device",
        type=str,
        default="auto",
        choices=["auto", "cpu", "cuda", "mps"],
        help="Device d'inference (defaut: auto)",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.3,
        help="Score minimal pour conserver une entite (defaut: 0.3)",
    )
    parser.add_argument(
        "--aggregation",
        type=str,
        default="simple",
        choices=["none", "simple", "first", "average", "max"],
        help="Strategie d'aggregation BIOES -> entites (defaut: simple)",
    )
    parser.add_argument(
        "--max-display",
        type=int,
        default=200,
        help="Nombre max d'entites affichees dans le detail (defaut: 200)",
    )
    parser.add_argument(
        "--no-chunk",
        action="store_true",
        help="Envoie le texte entier au modele (le tokenizer est large, en general OK)",
    )
    return parser.parse_args()


def resolve_device(requested: str):
    import torch

    if requested == "cpu":
        return -1
    if requested == "cuda":
        return 0 if torch.cuda.is_available() else -1
    if requested == "mps":
        return "mps" if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available() else -1
    if torch.cuda.is_available():
        return 0
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return "mps"
    return -1


def chunk_text(text: str, tokenizer, max_tokens: int) -> list[tuple[int, str]]:
    """Decoupe le texte en chunks respectant la limite de tokens, en gardant
    les offsets origine pour pouvoir recoller les positions dans le texte source."""
    lines = text.splitlines(keepends=True)
    chunks: list[tuple[int, str]] = []
    current = ""
    current_offset = 0
    cursor = 0

    for line in lines:
        candidate = current + line
        token_count = len(tokenizer.encode(candidate, add_special_tokens=False))
        if token_count > max_tokens and current:
            chunks.append((current_offset, current))
            current_offset = cursor
            current = line
        else:
            if not current:
                current_offset = cursor
            current = candidate
        cursor += len(line)

    if current.strip():
        chunks.append((current_offset, current))
    return chunks


def format_score_bar(score: float, width: int = 10) -> str:
    filled = int(round(max(0.0, min(1.0, score)) * width))
    return "[" + "#" * filled + "." * (width - filled) + "]"


def truncate(text: str, max_len: int = 50) -> str:
    cleaned = text.replace("\n", " ").replace("\r", " ").strip()
    return cleaned if len(cleaned) <= max_len else cleaned[: max_len - 3] + "..."


def main() -> int:
    args = parse_args()

    if not args.file.exists():
        print(f"[ERREUR] Fichier introuvable : {args.file}", file=sys.stderr)
        return 1

    print("=" * 80)
    print("  POC OpenMed Privacy Filter Multilingual")
    print("=" * 80)
    print(f"  Modele      : {args.model}")
    print(f"  Fichier     : {args.file}")
    print(f"  Threshold   : {args.threshold}")
    print(f"  Aggregation : {args.aggregation}")
    print(f"  HF_HOME     : {os.environ.get('HF_HOME', '<defaut: ~/.cache/huggingface>')}")
    print()

    try:
        import transformers
        from transformers import AutoModelForTokenClassification, AutoTokenizer, pipeline
    except ImportError as exc:
        print(f"[ERREUR] transformers/torch non installes : {exc}", file=sys.stderr)
        print('[HINT] pip install "transformers>=5.0" torch', file=sys.stderr)
        return 2

    print(f"[INFO] transformers version : {transformers.__version__}")
    if int(transformers.__version__.split(".", 1)[0]) < 5:
        print(
            "[ERREUR] Ce modele requiert transformers >= 5.0 (TokenizersBackend / "
            "openai_privacy_filter). Version actuelle : "
            f"{transformers.__version__}",
            file=sys.stderr,
        )
        print(
            "[HINT] Utilise un venv dedie : "
            "python -m venv ../.venv-openmed-poc && "
            '../.venv-openmed-poc/Scripts/python.exe -m pip install "transformers>=5.0" torch',
            file=sys.stderr,
        )
        return 3

    device = resolve_device(args.device)
    print(f"[INFO] Device resolu : {device!r}")

    text = args.file.read_text(encoding="utf-8")
    print(f"[INFO] Texte charge : {len(text)} caracteres, {text.count(chr(10)) + 1} lignes")

    print("[INFO] Telechargement / chargement du modele (1er run = telechargement, ~2.8 Go)...")
    t0 = time.perf_counter()
    tokenizer = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForTokenClassification.from_pretrained(args.model)
    load_elapsed = time.perf_counter() - t0
    print(f"[INFO] Modele charge en {load_elapsed:.1f}s")
    print(f"[INFO] Tokenizer  : {type(tokenizer).__name__}")
    print(f"[INFO] Model      : {type(model).__name__}")
    num_labels = getattr(model.config, "num_labels", None) or len(model.config.id2label)
    print(f"[INFO] Num labels : {num_labels}")

    aggregation = None if args.aggregation == "none" else args.aggregation
    ner = pipeline(
        task="token-classification",
        model=model,
        tokenizer=tokenizer,
        aggregation_strategy=aggregation,
        device=device,
    )

    if args.no_chunk:
        chunks = [(0, text)]
    else:
        chunks = chunk_text(text, tokenizer, MAX_TOKENS_PER_CHUNK)
    print(f"[INFO] Texte decoupe en {len(chunks)} chunk(s)")
    print()

    all_entities: list[dict] = []
    t0 = time.perf_counter()
    for idx, (offset, chunk) in enumerate(chunks, 1):
        chunk_t0 = time.perf_counter()
        result = ner(chunk)
        chunk_elapsed = time.perf_counter() - chunk_t0
        for ent in result:
            ent["start"] = int(ent.get("start", 0)) + offset
            ent["end"] = int(ent.get("end", 0)) + offset
            all_entities.append(ent)
        print(f"[CHUNK {idx}/{len(chunks)}] {len(result)} entites brutes en {chunk_elapsed*1000:.0f} ms")
    inference_elapsed = time.perf_counter() - t0
    print()
    print(f"[INFO] Inference totale : {inference_elapsed:.2f}s ({len(all_entities)} entites brutes)")

    filtered = [
        e for e in all_entities
        if float(e.get("score", 0.0)) >= args.threshold
        and (e.get("entity_group") or e.get("entity", "")) not in ("O", "")
    ]
    filtered.sort(key=lambda e: e["start"])
    print(f"[INFO] Entites apres filtre (score >= {args.threshold}, hors 'O') : {len(filtered)}")
    print()

    label_counter: Counter[str] = Counter()
    for ent in filtered:
        label = ent.get("entity_group") or ent.get("entity", "?")
        label_counter[label] += 1

    print("=" * 80)
    print("  STATISTIQUES PAR TYPE D'ENTITE")
    print("=" * 80)
    if not label_counter:
        print("  (aucune entite detectee)")
    else:
        for label, count in label_counter.most_common():
            print(f"  {label:30s} {count:>4d}")
    print()

    print("=" * 80)
    print(f"  ENTITES DETECTEES (max {args.max_display} affichees, total={len(filtered)})")
    print("=" * 80)
    print(f"  {'#':>3s}  {'TYPE':<22s}  {'SCORE':>6s}  {'BAR':<12s}  {'POS':>11s}  TEXTE")
    print("  " + "-" * 90)
    for i, ent in enumerate(filtered[: args.max_display], 1):
        label = ent.get("entity_group") or ent.get("entity", "?")
        score = float(ent.get("score", 0.0))
        start = int(ent.get("start", 0))
        end = int(ent.get("end", 0))
        word = truncate(ent.get("word", ""), 50)
        bar = format_score_bar(score)
        print(f"  {i:>3d}  {label:<22s}  {score:>6.2f}  {bar:<12s}  {start:>4d}-{end:<5d}  {word!r}")

    if len(filtered) > args.max_display:
        print(f"  ... ({len(filtered) - args.max_display} entites supplementaires non affichees)")

    print()
    print("=" * 80)
    print(f"  Termine. {len(filtered)} entites detectees au-dessus du threshold {args.threshold}.")
    print("=" * 80)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
