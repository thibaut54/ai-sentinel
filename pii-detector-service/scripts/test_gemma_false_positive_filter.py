"""
Test d'integration autonome : telecharge reellement Gemma 4 (GGUF) depuis
HuggingFace et evalue sa capacite a filtrer les faux positifs PII.

Usage :
    cd pii-detector-service
    python scripts/test_gemma_false_positive_filter.py
    python scripts/test_gemma_false_positive_filter.py --repo unsloth/gemma-4-E4B-it-GGUF \
        --filename gemma-4-E4B-it-Q4_K_M.gguf --n-gpu-layers -1

Variables d'environnement :
    HF_TOKEN ou HUGGING_FACE_HUB_TOKEN : token HuggingFace si modele gated
    LLM_TEST_REPO                       : override repo HF
    LLM_TEST_FILENAME                   : override nom fichier GGUF

Le script :
    1. Telecharge le modele GGUF (cache HuggingFace standard)
    2. Charge llama-cpp-python
    3. Soumet un dataset fixe d'entites (vrais positifs + faux positifs connus)
    4. Reutilise le meme prompt batch que la production (prompt_templates)
    5. Reutilise le meme parser de verdicts que la production (llm_validator)
    6. Affiche un rapport precision / recall / F1 + matrice de confusion
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Set, Tuple

# Permet l'import des modules du service sans installation
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

DEFAULT_REPO = os.getenv("LLM_TEST_REPO", "unsloth/gemma-4-E4B-it-GGUF")
DEFAULT_FILENAME = os.getenv("LLM_TEST_FILENAME", "gemma-4-E4B-it-Q4_K_M.gguf")
DEFAULT_N_CTX = 4096
DEFAULT_MAX_OUTPUT_TOKENS = 300
DEFAULT_TEMPERATURE = 0.0


@dataclass
class TestEntity:
    """Entite PII compatible avec build_batch_prompt (duck-typed)."""

    pii_type: str
    text: str
    start: int
    end: int
    type_label: str
    is_true_positive: bool

    @property
    def expected_verdict(self) -> str:
        return "TRUE_POSITIVE" if self.is_true_positive else "FALSE_POSITIVE"


SOURCE_TEXT = (
    "Rapport technique - Projet RSA Migration v3.2\n"
    "\n"
    "Le certificat RSA du serveur principal a ete renouvele le 15 mars 2026.\n"
    "Le protocole MAC (Message Authentication Code) est active sur tous les endpoints.\n"
    "L'API Gateway utilise le standard OAuth 2.0 pour l'authentification.\n"
    "Le module SWIFT de l'application gere les transferts inter-bancaires.\n"
    "Le projet ATLAS a ete livre en phase 2 avec le module DIANA.\n"
    "L'identifiant du build est BUILD-2026-03-1542.\n"
    "La version du framework Spring Boot utilisee est 3.2.4.\n"
    "\n"
    "Contact du responsable : Jean-Pierre Duval, joignable au +41 79 345 67 89.\n"
    "Son adresse email est jp.duval@softcom.ch et il reside au 15 Rue du Lac, "
    "1003 Lausanne.\n"
    "Son numero AVS est 756.1234.5678.90.\n"
    "Le serveur DNS principal est configure sur l'adresse 10.0.0.1 du reseau interne.\n"
)


def _locate(text: str, needle: str) -> Tuple[int, int]:
    idx = text.find(needle)
    if idx < 0:
        raise ValueError(f"Aiguille introuvable dans le texte : {needle!r}")
    return idx, idx + len(needle)


def build_dataset() -> List[TestEntity]:
    """Construit un dataset mixant faux positifs typiques et vrais positifs."""
    cases = [
        # FAUX POSITIFS (acronymes techniques, projets, frameworks)
        ("RSA", "PERSON_NAME", "Nom de personne", False),
        ("MAC", "PERSON_NAME", "Nom de personne", False),
        ("SWIFT", "PERSON_NAME", "Nom de personne", False),
        ("OAuth", "PERSON_NAME", "Nom de personne", False),
        ("ATLAS", "PERSON_NAME", "Nom de personne", False),
        ("DIANA", "PERSON_NAME", "Nom de personne", False),
        ("Spring Boot", "PERSON_NAME", "Nom de personne", False),
        ("BUILD-2026-03-1542", "ID_NUMBER", "Numero d'identification", False),
        # VRAIS POSITIFS (PII reelles)
        ("Jean-Pierre Duval", "PERSON_NAME", "Nom de personne", True),
        ("jp.duval@softcom.ch", "EMAIL", "Adresse email", True),
        ("+41 79 345 67 89", "PHONE_NUMBER", "Numero de telephone", True),
        ("15 Rue du Lac", "ADDRESS", "Adresse postale", True),
        ("Lausanne", "ADDRESS", "Adresse postale", True),
        ("756.1234.5678.90", "AVS_NUMBER", "Numero AVS", True),
    ]
    entities: List[TestEntity] = []
    for needle, pii_type, label, is_tp in cases:
        start, end = _locate(SOURCE_TEXT, needle)
        entities.append(
            TestEntity(
                pii_type=pii_type,
                text=needle,
                start=start,
                end=end,
                type_label=label,
                is_true_positive=is_tp,
            )
        )
    return entities


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", default=DEFAULT_REPO, help="Repo HF (GGUF)")
    parser.add_argument("--filename", default=DEFAULT_FILENAME, help="Nom fichier GGUF")
    parser.add_argument("--n-ctx", type=int, default=DEFAULT_N_CTX, help="Taille contexte")
    parser.add_argument(
        "--n-gpu-layers",
        type=int,
        default=-1,
        help="Couches GPU (-1 = max possible, 0 = CPU)",
    )
    parser.add_argument(
        "--context-window",
        type=int,
        default=200,
        help="Caracteres de contexte autour de chaque entite",
    )
    parser.add_argument(
        "--temperature", type=float, default=DEFAULT_TEMPERATURE, help="Temperature LLM"
    )
    parser.add_argument(
        "--max-output-tokens",
        type=int,
        default=DEFAULT_MAX_OUTPUT_TOKENS,
        help="Tokens max en sortie",
    )
    parser.add_argument(
        "--model-path",
        default=None,
        help="Chemin local vers un .gguf (court-circuite le download)",
    )
    return parser.parse_args()


def download_model(repo: str, filename: str) -> str:
    print(f"[1/4] Telechargement de {repo}/{filename} depuis HuggingFace ...")
    from huggingface_hub import hf_hub_download

    token = os.getenv("HF_TOKEN") or os.getenv("HUGGING_FACE_HUB_TOKEN")
    started = time.monotonic()
    path = hf_hub_download(repo_id=repo, filename=filename, token=token)
    elapsed = time.monotonic() - started
    size_mb = Path(path).stat().st_size / (1024 * 1024)
    print(f"      OK -> {path}  ({size_mb:.1f} MB en {elapsed:.1f}s)")
    return path


def load_model(model_path: str, n_ctx: int, n_gpu_layers: int):
    print(f"[2/4] Chargement de Llama (n_ctx={n_ctx}, n_gpu_layers={n_gpu_layers}) ...")
    from llama_cpp import Llama

    started = time.monotonic()
    model = Llama(
        model_path=model_path,
        n_ctx=n_ctx,
        n_gpu_layers=n_gpu_layers,
        verbose=False,
    )
    print(f"      OK ({time.monotonic() - started:.1f}s)")
    return model


def run_inference(
    model,
    entities: List[TestEntity],
    context_window: int,
    max_output_tokens: int,
    temperature: float,
) -> Tuple[str, str, float]:
    from pii_detector.infrastructure.validation.prompt_templates import (
        build_batch_prompt,
    )

    print(f"[3/4] Inference Gemma 4 sur {len(entities)} entites ...")
    prompt = build_batch_prompt(entities, SOURCE_TEXT, context_window)
    started = time.monotonic()
    response = model.create_chat_completion(
        messages=[{"role": "user", "content": prompt}],
        max_tokens=max_output_tokens,
        temperature=temperature,
    )
    elapsed = time.monotonic() - started
    response_text = response["choices"][0]["message"]["content"] or ""
    print(f"      Reponse en {elapsed:.2f}s")
    return prompt, response_text, elapsed


def parse_verdicts(response: str, entity_count: int) -> Tuple[Set[int], Set[int]]:
    """Reutilise le meme regex que la prod pour extraire TP/FP."""
    from pii_detector.infrastructure.validation.llm_validator import _VERDICT_PATTERN

    rejected: Set[int] = set()
    confirmed: Set[int] = set()
    for match in _VERDICT_PATTERN.finditer(response):
        idx = int(match.group(1))
        verdict = match.group(2).upper()
        if 0 <= idx < entity_count:
            (rejected if verdict == "FALSE_POSITIVE" else confirmed).add(idx)
    return confirmed, rejected


def evaluate(
    entities: List[TestEntity], confirmed: Set[int], rejected: Set[int]
) -> dict:
    """Calcule precision / recall / F1 sur la classe FALSE_POSITIVE."""
    tp_total = sum(1 for e in entities if e.is_true_positive)
    fp_total = len(entities) - tp_total

    # On evalue la qualite du filtrage des faux positifs
    correctly_rejected = sum(1 for i in rejected if not entities[i].is_true_positive)
    wrongly_rejected = sum(1 for i in rejected if entities[i].is_true_positive)
    correctly_kept = sum(1 for i in confirmed if entities[i].is_true_positive)
    wrongly_kept = sum(1 for i in confirmed if not entities[i].is_true_positive)
    missing = [
        i for i in range(len(entities)) if i not in confirmed and i not in rejected
    ]

    precision_fp = (
        correctly_rejected / (correctly_rejected + wrongly_rejected)
        if (correctly_rejected + wrongly_rejected) > 0
        else 0.0
    )
    recall_fp = correctly_rejected / fp_total if fp_total > 0 else 0.0
    f1_fp = (
        2 * precision_fp * recall_fp / (precision_fp + recall_fp)
        if (precision_fp + recall_fp) > 0
        else 0.0
    )
    accuracy = (correctly_kept + correctly_rejected) / len(entities)

    return {
        "tp_total": tp_total,
        "fp_total": fp_total,
        "correctly_rejected": correctly_rejected,
        "wrongly_rejected": wrongly_rejected,
        "correctly_kept": correctly_kept,
        "wrongly_kept": wrongly_kept,
        "missing_indices": missing,
        "precision_fp": precision_fp,
        "recall_fp": recall_fp,
        "f1_fp": f1_fp,
        "accuracy": accuracy,
    }


def print_report(
    entities: List[TestEntity],
    confirmed: Set[int],
    rejected: Set[int],
    response: str,
    metrics: dict,
    elapsed: float,
) -> None:
    print()
    print("=" * 78)
    print("[4/4] RAPPORT D'EVALUATION")
    print("=" * 78)
    print()
    print("Reponse brute du modele :")
    print("-" * 78)
    print(response.strip())
    print("-" * 78)
    print()
    print(f"{'IDX':>3}  {'ATTENDU':<15}  {'OBTENU':<15}  {'OK':<3}  TEXTE")
    for i, ent in enumerate(entities):
        if i in confirmed:
            got = "TRUE_POSITIVE"
        elif i in rejected:
            got = "FALSE_POSITIVE"
        else:
            got = "(manquant)"
        ok = "OK" if got == ent.expected_verdict else "KO"
        print(f"{i:>3}  {ent.expected_verdict:<15}  {got:<15}  {ok:<3}  {ent.text!r}")
    print()
    print("Synthese :")
    print(f"  Total entites           : {len(entities)}")
    print(f"  Vrais positifs attendus : {metrics['tp_total']}")
    print(f"  Faux positifs attendus  : {metrics['fp_total']}")
    print(f"  FP correctement filtres : {metrics['correctly_rejected']}")
    print(f"  TP correctement gardes  : {metrics['correctly_kept']}")
    print(f"  TP rejetes a tort       : {metrics['wrongly_rejected']}")
    print(f"  FP gardes a tort        : {metrics['wrongly_kept']}")
    print(f"  Verdicts manquants      : {len(metrics['missing_indices'])}")
    print()
    print("Metriques (classe FALSE_POSITIVE) :")
    print(f"  Precision : {metrics['precision_fp']:.2%}")
    print(f"  Recall    : {metrics['recall_fp']:.2%}")
    print(f"  F1        : {metrics['f1_fp']:.2%}")
    print(f"  Accuracy  : {metrics['accuracy']:.2%}")
    print(f"  Latence   : {elapsed:.2f}s ({elapsed / len(entities):.3f}s / entite)")
    print("=" * 78)


def main() -> int:
    args = parse_args()

    try:
        import llama_cpp  # noqa: F401
    except ImportError:
        print("ERREUR : llama-cpp-python n'est pas installe.")
        print("        Installe-le avec : pip install llama-cpp-python")
        return 2

    model_path = args.model_path or download_model(args.repo, args.filename)
    if not Path(model_path).is_file():
        print(f"ERREUR : fichier introuvable : {model_path}")
        return 3

    model = load_model(model_path, args.n_ctx, args.n_gpu_layers)
    entities = build_dataset()
    _prompt, response, elapsed = run_inference(
        model,
        entities,
        args.context_window,
        args.max_output_tokens,
        args.temperature,
    )
    confirmed, rejected = parse_verdicts(response, len(entities))
    metrics = evaluate(entities, confirmed, rejected)
    print_report(entities, confirmed, rejected, response, metrics, elapsed)

    # Exit code = 0 si recall FP >= 50% et aucun vrai positif perdu
    return 0 if metrics["recall_fp"] >= 0.5 and metrics["wrongly_rejected"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
