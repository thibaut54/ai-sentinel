"""
Test d'integration bout-en-bout du pipeline AI Sentinel sur un document reel.

Contrairement a `test_gemma_false_positive_filter.py` qui utilise un texte
synthetique et fournit la liste d'entites a la main, ce script :
  1. Charge un document Markdown reel (par defaut le PDF metier converti via
     `my-files/convert_pdf.py`).
  2. Construit le pipeline de production : CompositePIIDetector
     (MultiPassGLiNER + RegexDetector + PresidioDetector).
  3. Lance la detection complete avec la meme config TOML que le service gRPC.
  4. Charge le LLMValidator (Gemma 4 E4B GGUF, telecharge depuis HuggingFace).
  5. Soumet les entites detectees a la validation Gemma 4 (meme prompt et meme
     parser que la prod).
  6. Affiche, pour chaque entite : verdict du LLM + extrait de contexte, afin
     de verifier visuellement si Gemma 4 supprime les bons faux positifs sans
     casser de vrais positifs.
  7. Ecrit un rapport JSON detaille pour audit ulterieur.

Usage :
    cd pii-detector-service
    python scripts/test_pipeline_real_pdf.py
    python scripts/test_pipeline_real_pdf.py --markdown ../my-files/markdown/foo.md
    python scripts/test_pipeline_real_pdf.py --no-llm    # dump des entites brutes uniquement
    python scripts/test_pipeline_real_pdf.py --max-chars 20000   # tronquer pour test rapide
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

# Permet d'importer pii_detector sans installation editable
SERVICE_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVICE_ROOT))
PROJECT_ROOT = SERVICE_ROOT.parent
DEFAULT_MD = SERVICE_ROOT / "scripts" / "confluence-pii-test-document-docanno-light.txt"
DEFAULT_REPORT = SERVICE_ROOT / "logs" / "pipeline_real_pdf_report.json"

# Active le TOML de fallback Postgres -> permet de faire tourner le pipeline
# COMPLET (categories DB, configs par type, flags global) sans la stack docker.
# Doit etre defini AVANT le premier import de database_config_adapter.
_FALLBACK_TOML = SERVICE_ROOT / "config" / "test-fallback-pii-config.toml"
if _FALLBACK_TOML.is_file():
    os.environ.setdefault("PII_DETECTOR_TEST_FALLBACK_TOML", str(_FALLBACK_TOML))

# Cache local des modeles LLM (GLiNER + Gemma 4 GGUF). Les fichiers sont
# telecharges UNE seule fois dans ce folder et reutilises a chaque run --
# evite de re-telecharger les ~5 GB de Gemma. Le reload en RAM (~90s pour
# GLiNER, ~4s pour Gemma) reste inevitable a chaque process Python : pour
# l'eviter, faut passer par le serveur gRPC qui les garde en memoire.
#
# Doit etre configure AVANT le premier import HF (les libs HF lisent
# HF_HUB_CACHE / HF_HOME une seule fois au chargement). On pre-parse donc
# sys.argv pour respecter une eventuelle option --cache-dir, puis on
# retombe sur la variable d'env existante, sinon sur le default projet.
def _resolve_cache_dir() -> Path:
    argv = sys.argv[1:]
    for i, arg in enumerate(argv):
        if arg == "--cache-dir" and i + 1 < len(argv):
            return Path(argv[i + 1]).expanduser().resolve()
        if arg.startswith("--cache-dir="):
            return Path(arg.split("=", 1)[1]).expanduser().resolve()
    if os.getenv("HF_HUB_CACHE"):
        return Path(os.environ["HF_HUB_CACHE"]).resolve().parent
    if os.getenv("HF_HOME"):
        return Path(os.environ["HF_HOME"]).resolve()
    return (PROJECT_ROOT / ".model-cache").resolve()


_CACHE_DIR = _resolve_cache_dir()
_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ["HF_HUB_CACHE"] = str(_CACHE_DIR / "hub")
os.environ["HF_HOME"] = str(_CACHE_DIR)
print(f"[cache] HF cache: {_CACHE_DIR}")


# --------------------------------------------------------------------------
# Adaptateur "duck-typed" : LLMValidator lit .pii_type / .text / .start / .end
# / .type_label, mais le pipeline retourne des dicts (pii_service les passe
# tels quels en gRPC). On cree donc un wrapper minimal pour les batch prompts.
# --------------------------------------------------------------------------
@dataclass
class _EntityView:
    pii_type: str
    text: str
    start: int
    end: int
    type_label: str
    score: float
    source: str

    @classmethod
    def from_pipeline(cls, raw: Any) -> "_EntityView":
        """Accepte aussi bien un dict (gRPC servicer) qu'un PIIEntity domaine."""
        if isinstance(raw, dict):
            pii_type = str(raw.get("type") or raw.get("pii_type") or "UNKNOWN")
            return cls(
                pii_type=pii_type.upper().replace(" ", "_").replace("-", "_"),
                text=str(raw.get("text", "")),
                start=int(raw.get("start", 0)),
                end=int(raw.get("end", 0)),
                type_label=str(raw.get("type_label") or pii_type),
                score=float(raw.get("score", 0.0)),
                source=str(raw.get("source") or "UNKNOWN"),
            )
        # Domain entity
        pii_type = getattr(raw, "pii_type", None) or getattr(raw, "type", "UNKNOWN")
        pii_type_str = pii_type.name if hasattr(pii_type, "name") else str(pii_type)
        return cls(
            pii_type=pii_type_str.upper().replace(" ", "_").replace("-", "_"),
            text=str(getattr(raw, "text", "")),
            start=int(getattr(raw, "start", 0)),
            end=int(getattr(raw, "end", 0)),
            type_label=str(getattr(raw, "type_label", pii_type_str)),
            score=float(getattr(raw, "score", 0.0) or 0.0),
            source=str(getattr(raw, "source", "UNKNOWN")),
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--markdown",
        type=Path,
        default=DEFAULT_MD,
        help="Chemin vers le markdown a analyser",
    )
    parser.add_argument(
        "--max-chars",
        type=int,
        default=0,
        help="Tronquer le texte aux N premiers caracteres (0 = tout le doc)",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.5,
        help="Seuil de confiance ML (defaut 0.5)",
    )
    parser.add_argument(
        "--no-llm",
        action="store_true",
        help="N'execute pas la validation Gemma 4 (utile pour debug detecteurs)",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=DEFAULT_REPORT,
        help="Fichier JSON de sortie (rapport audit)",
    )
    parser.add_argument(
        "--context-window",
        type=int,
        default=200,
        help="Caracteres de contexte autour de chaque entite pour Gemma",
    )
    parser.add_argument(
        "--max-batch-size",
        type=int,
        default=20,
        help="Taille de batch LLM (max 20 pour rester sous la fenetre de contexte)",
    )
    parser.add_argument(
        "--n-gpu-layers",
        type=int,
        default=-1,
        help="Couches GPU offloadees (-1 = max, 0 = CPU only)",
    )
    parser.add_argument(
        "--max-output-tokens",
        type=int,
        default=400,
        help="Tokens max en sortie LLM par batch (assez pour 20 verdicts)",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=None,
        help=(
            "Folder ou cacher les modeles HF (GLiNER + Gemma GGUF). "
            "Par defaut <project-root>/.model-cache. Pour reutiliser "
            "le cache HF utilisateur (ex: deja telecharge dans "
            "~/.cache/huggingface), passer ~/.cache/huggingface."
        ),
    )
    return parser.parse_args()


# --------------------------------------------------------------------------
# Construction du pipeline (mirror de _initialize_detector_instance)
# --------------------------------------------------------------------------
def build_pipeline(threshold: float):
    """Construit le CompositePIIDetector avec les memes regles que le servicer."""
    from pii_detector.application.config.detection_policy import (
        DetectionConfig,
        _load_llm_config,
        get_enabled_models,
    )
    from pii_detector.application.orchestration.composite_detector import (
        create_composite_detector,
    )
    from pii_detector.infrastructure.detector.gliner_detector import GLiNERDetector
    from pii_detector.infrastructure.detector.multi_pass_gliner_detector import (
        MultiPassGlinerDetector,
    )

    cfg_dict = _load_llm_config()
    detection_cfg = cfg_dict.get("detection", {})
    enabled_models = get_enabled_models(cfg_dict)
    print(f"[pipeline] llm_detection_enabled={detection_cfg.get('llm_detection_enabled')}, "
          f"models enables = {[m['name'] for m in enabled_models]}")

    ml_detector = None
    if enabled_models:
        det_cfg = DetectionConfig()  # threshold global override possible mais on garde la config TOML
        if "gliner" in det_cfg.model_id.lower():
            if detection_cfg.get("multipass_gliner_enabled", False):
                print(f"[pipeline] Using MultiPassGlinerDetector ({det_cfg.model_id})")
                ml_detector = MultiPassGlinerDetector(config=det_cfg)
            else:
                print(f"[pipeline] Using GLiNERDetector ({det_cfg.model_id})")
                ml_detector = GLiNERDetector(config=det_cfg)
        else:
            from pii_detector.infrastructure.detector.pii_detector import PIIDetector
            print(f"[pipeline] Using HuggingFace PIIDetector ({det_cfg.model_id})")
            ml_detector = PIIDetector()

    composite = create_composite_detector(ml_detector=ml_detector)
    print("[pipeline] Loading models (download if needed) ...")
    started = time.monotonic()
    composite.download_model()
    composite.load_model()
    print(f"[pipeline] Models loaded in {time.monotonic() - started:.1f}s")
    return composite


def run_detection(detector, text: str, threshold: float) -> List[Dict[str, Any]]:
    """Lance la detection et normalise la sortie en list[dict] (comme la prod)."""
    print(f"[detect] Running detect_pii on {len(text)} chars, threshold={threshold} ...")
    started = time.monotonic()
    raw = detector.detect_pii(
        text,
        threshold=threshold,
        # Active toutes les briques disponibles, comme le ferait un scan reel
        # avec llm_validation_enabled=true / regex_enabled=true / etc. en DB.
        enable_ml=True,
        enable_regex=True,
        enable_presidio=True,
    )
    elapsed = time.monotonic() - started
    print(f"[detect] {len(raw)} entities in {elapsed:.2f}s")

    # Normalisation: detect_pii peut renvoyer dict ou objets selon le detecteur
    normalized: List[Dict[str, Any]] = []
    for e in raw:
        if isinstance(e, dict):
            normalized.append(dict(e))
        else:
            normalized.append({
                "text": getattr(e, "text", ""),
                "type": getattr(e, "pii_type", None) or getattr(e, "type", "UNKNOWN"),
                "type_label": getattr(e, "type_label", "PII"),
                "start": getattr(e, "start", 0),
                "end": getattr(e, "end", 0),
                "score": float(getattr(e, "score", 0.0) or 0.0),
                "source": str(getattr(e, "source", "UNKNOWN")),
            })
    return normalized


# --------------------------------------------------------------------------
# Validation LLM "manuelle" : on duplique la boucle de _validate_batch pour
# capturer les verdicts (TP/FP) et pas seulement la liste filtree, afin
# d'expliquer chaque decision dans le rapport.
# --------------------------------------------------------------------------
def run_llm_validation(
    entities: List[Dict[str, Any]],
    source_text: str,
    args: argparse.Namespace,
) -> Dict[str, Any]:
    from pii_detector.infrastructure.validation.llm_validator import (
        LLMValidator,
        _VERDICT_PATTERN,
    )
    from pii_detector.infrastructure.validation.prompt_templates import (
        build_batch_prompt,
    )

    print("[llm] Loading Gemma 4 E4B GGUF (download if needed, this can be ~2 GB) ...")
    validator = LLMValidator(
        context_window=args.context_window,
        max_batch_size=args.max_batch_size,
        max_output_tokens=args.max_output_tokens,
        n_gpu_layers=args.n_gpu_layers,
    )
    if not validator.load_model():
        raise RuntimeError(
            "LLMValidator.load_model() returned False - verifier HF_TOKEN ou "
            "la connectivite vers HuggingFace."
        )
    print("[llm] Model loaded")

    views = [_EntityView.from_pipeline(e) for e in entities]
    verdicts: List[Optional[str]] = [None] * len(views)
    raw_responses: List[Dict[str, Any]] = []
    # Marge de securite identique a la prod (cf. LLMValidator._validate_batch).
    max_prompt_tokens = validator.n_ctx - args.max_output_tokens - 50
    inference_total = [0.0]  # liste pour mutation depuis closure

    def _count_tokens(text: str) -> int:
        """Compte exact des tokens via le tokenizer du modele (llama-cpp).

        L'heuristique `len(prompt) // 3` de la prod sous-estime fortement les
        prompts en francais (ratio reel observe : ~2 chars/token), ce qui fait
        deborder le n_ctx. On utilise donc le vrai tokenizer pour eviter toute
        ambiguite. Cout : ~1 ms par appel, negligeable face a l'inference.
        """
        return len(validator._model.tokenize(text.encode("utf-8"), add_bos=True, special=True))  # noqa: SLF001

    def _run_with_split(batch: List[_EntityView], offset: int) -> None:
        prompt = build_batch_prompt(batch, source_text, args.context_window)
        token_count = _count_tokens(prompt)
        if token_count > max_prompt_tokens and len(batch) > 1:
            mid = len(batch) // 2
            print(
                f"[llm] split batch offset={offset} taille={len(batch)} "
                f"({token_count} tokens > limite {max_prompt_tokens}) "
                f"-> {mid} + {len(batch) - mid}"
            )
            _run_with_split(batch[:mid], offset)
            _run_with_split(batch[mid:], offset + mid)
            return

        if token_count > max_prompt_tokens:
            # Plus de subdivision possible, le prompt est encore trop long
            # pour le batch de taille 1 - on reduit tactiquement le context
            # window pour cette entite plutot que de planter tout le run.
            shrunk_window = max(40, args.context_window // 2)
            print(
                f"[llm] entity {offset} prompt={token_count} tokens encore "
                f"> {max_prompt_tokens} avec batch=1, retry avec "
                f"context_window={shrunk_window} (au lieu de {args.context_window})"
            )
            prompt = build_batch_prompt(batch, source_text, shrunk_window)
            token_count = _count_tokens(prompt)

        started = time.monotonic()
        response = validator._model.create_chat_completion(  # noqa: SLF001
            messages=[{"role": "user", "content": prompt}],
            max_tokens=args.max_output_tokens,
            temperature=0.0,
        )
        elapsed = time.monotonic() - started
        inference_total[0] += elapsed
        text_response = response["choices"][0]["message"]["content"] or ""
        print(
            f"[llm] batch {offset}-{offset + len(batch) - 1} "
            f"({len(batch)} entites, {token_count} tokens) en {elapsed:.2f}s"
        )
        raw_responses.append({
            "batch_offset": offset,
            "batch_size": len(batch),
            "prompt_tokens": token_count,
            "elapsed_s": elapsed,
            "response": text_response.strip(),
        })
        for match in _VERDICT_PATTERN.finditer(text_response):
            local_idx = int(match.group(1))
            if 0 <= local_idx < len(batch):
                verdicts[offset + local_idx] = match.group(2).upper()

    for batch_start in range(0, len(views), args.max_batch_size):
        batch = views[batch_start : batch_start + args.max_batch_size]
        _run_with_split(batch, batch_start)

    return {
        "verdicts": verdicts,
        "total_inference_s": inference_total[0],
        "raw_responses": raw_responses,
    }


def context_snippet(text: str, start: int, end: int, window: int = 80) -> str:
    """Petit extrait centre sur l'entite, avec [[...]] autour de la valeur."""
    a = max(0, start - window)
    b = min(len(text), end + window)
    return (
        text[a:start].replace("\n", " ")
        + "[["
        + text[start:end].replace("\n", " ")
        + "]]"
        + text[end:b].replace("\n", " ")
    )


def write_report(
    report_path: Path,
    payload: Dict[str, Any],
) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2, default=str)
    print(f"[report] JSON ecrit: {report_path}")


def print_summary(
    entities: List[Dict[str, Any]],
    verdicts: Optional[List[Optional[str]]],
    text: str,
) -> Dict[str, Any]:
    by_source: Dict[str, int] = {}
    by_type: Dict[str, int] = {}
    for e in entities:
        src = str(e.get("source", "UNKNOWN"))
        by_source[src] = by_source.get(src, 0) + 1
        ptype = str(e.get("type", "UNKNOWN"))
        by_type[ptype] = by_type.get(ptype, 0) + 1

    rejected = kept = missing = 0
    if verdicts is not None:
        for v in verdicts:
            if v == "FALSE_POSITIVE":
                rejected += 1
            elif v == "TRUE_POSITIVE":
                kept += 1
            else:
                missing += 1

    print()
    print("=" * 78)
    print("RAPPORT DETECTION + VALIDATION GEMMA 4")
    print("=" * 78)
    print(f"Entites detectees       : {len(entities)}")
    print(f"  par source            : {dict(sorted(by_source.items()))}")
    print(f"  top 10 types          : {dict(sorted(by_type.items(), key=lambda x: -x[1])[:10])}")
    if verdicts is not None:
        print(f"Verdicts Gemma 4        : kept={kept} rejected={rejected} missing={missing}")
        if entities:
            print(f"Taux de rejet (FP)      : {rejected / len(entities):.1%}")
    print()

    if verdicts is not None:
        print("ENTITES REJETEES PAR GEMMA 4 (candidats faux positifs supprimes) :")
        print("-" * 78)
        rejected_rows = [(i, e) for i, e in enumerate(entities) if verdicts[i] == "FALSE_POSITIVE"]
        if not rejected_rows:
            print("  (aucune)")
        for i, e in rejected_rows[:50]:
            print(
                f"  #{i:>3} type={e.get('type'):<22} src={str(e.get('source')):<12} "
                f"score={float(e.get('score', 0)):.2f}  text={e.get('text')!r}"
            )
            print(f"        ctx: ...{context_snippet(text, e['start'], e['end'])[:200]}...")
        if len(rejected_rows) > 50:
            print(f"  ... ({len(rejected_rows) - 50} autres rejets dans le rapport JSON)")

        print()
        print("ENTITES VALIDEES (gardees par Gemma 4) - TOP 30 :")
        print("-" * 78)
        kept_rows = [(i, e) for i, e in enumerate(entities) if verdicts[i] == "TRUE_POSITIVE"]
        for i, e in kept_rows[:30]:
            print(
                f"  #{i:>3} type={e.get('type'):<22} src={str(e.get('source')):<12} "
                f"score={float(e.get('score', 0)):.2f}  text={e.get('text')!r}"
            )

        if missing:
            print()
            print(f"VERDICTS MANQUANTS (Gemma 4 n'a pas repondu) - {missing} entites :")
            print("-" * 78)
            for i, e in [(i, e) for i, e in enumerate(entities) if verdicts[i] is None][:20]:
                print(f"  #{i:>3} type={e.get('type')} text={e.get('text')!r}")

    print("=" * 78)
    return {
        "total_entities": len(entities),
        "by_source": by_source,
        "by_type": by_type,
        "kept": kept,
        "rejected": rejected,
        "missing": missing,
    }


def main() -> int:
    args = parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    md_path = args.markdown
    if not md_path.is_file():
        print(f"ERREUR: markdown introuvable: {md_path}", file=sys.stderr)
        print(
            "Astuce: lance d'abord `python my-files/convert_pdf.py` pour generer le MD.",
            file=sys.stderr,
        )
        return 2

    text = md_path.read_text(encoding="utf-8")
    if args.max_chars > 0:
        text = text[: args.max_chars]
    print(f"[input] {md_path}  ({len(text)} chars)")

    detector = build_pipeline(args.threshold)
    entities = run_detection(detector, text, args.threshold)

    verdicts: Optional[List[Optional[str]]] = None
    llm_payload: Optional[Dict[str, Any]] = None
    llm_skip_reason: Optional[str] = None
    if args.no_llm:
        llm_skip_reason = "Disabled via --no-llm"
    elif not entities:
        llm_skip_reason = "No entities detected"
    else:
        try:
            import llama_cpp  # noqa: F401
        except ImportError:
            llm_skip_reason = (
                "llama-cpp-python n'est pas installe dans ce venv. "
                "Installer avec: pip install llama-cpp-python "
                "(ou indiquer un venv qui le contient deja)."
            )
        else:
            try:
                llm_payload = run_llm_validation(entities, text, args)
                verdicts = llm_payload["verdicts"]
            except Exception as exc:  # noqa: BLE001
                llm_skip_reason = f"LLM validation a echoue: {exc}"
                logging.exception("LLM validation failed")
    if llm_skip_reason:
        print(f"[llm] SKIP: {llm_skip_reason}")

    metrics = print_summary(entities, verdicts, text)

    report = {
        "input": {
            "markdown_path": str(md_path),
            "char_count": len(text),
            "max_chars_truncation": args.max_chars or None,
            "threshold": args.threshold,
        },
        "metrics": metrics,
        "entities": [
            {
                "index": i,
                "text": e.get("text"),
                "type": str(e.get("type")),
                "type_label": e.get("type_label"),
                "source": str(e.get("source")),
                "score": float(e.get("score", 0.0)),
                "start": e.get("start"),
                "end": e.get("end"),
                "verdict": verdicts[i] if verdicts is not None else None,
                "context": context_snippet(text, e["start"], e["end"]),
            }
            for i, e in enumerate(entities)
        ],
        "llm_raw_responses": (llm_payload or {}).get("raw_responses"),
        "llm_total_inference_s": (llm_payload or {}).get("total_inference_s"),
        "llm_skip_reason": llm_skip_reason,
    }
    write_report(args.report, report)

    # Exit code = 0 si pipeline a tourne sans planter.
    # On NE bloque PAS sur des metriques de qualite : pas de ground-truth
    # labellisee, l'audit est visuel via le rapport JSON.
    return 0


if __name__ == "__main__":
    sys.exit(main())
