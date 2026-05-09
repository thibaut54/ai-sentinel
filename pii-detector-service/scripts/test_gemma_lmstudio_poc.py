"""
TODO POC: REMOVE AFTER VALIDATION
=================================
Variante POC du script test_gemma_false_positive_filter.py qui appelle un serveur
OpenAI-compatible (LM Studio, Ollama, vLLM, ...) au lieu d'embarquer llama-cpp-python.

But : valider qu'un GPU host (Intel Arc, NVIDIA, Apple Silicon, ...) accessible via
LM Studio donne des perfs significativement meilleures que llama-cpp-python en
CPU-only (cas typique du container Docker python:3.11-slim).

Usage :
    cd pii-detector-service
    # 1. Lancer LM Studio, activer le serveur (Developer mode -> Start Server)
    # 2. Charger le modele Gemma souhaite dans LM Studio
    # 3. Lancer ce script :
    python scripts/test_gemma_lmstudio_poc.py --model google/gemma-4-e4b

Variables d'environnement :
    LMSTUDIO_BASE_URL : URL du serveur OpenAI-compatible (defaut: http://localhost:1234/v1)
    LMSTUDIO_MODEL    : id du modele tel qu'expose par /v1/models

Comparaison de reference : run le script original (llama-cpp CPU-only) puis celui-ci
sur le meme dataset, et compare la latence + l'accuracy.
"""
# TODO POC: REMOVE AFTER VALIDATION
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import List, Set, Tuple

# Permet l'import des modules du service sans installation
ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(SCRIPTS))

# Reutilise integralement le dataset et les helpers du script de reference,
# pour que la comparaison soit apple-to-apple. `scripts/` n'est pas un package
# Python (pas de __init__.py), on l'ajoute donc directement au sys.path et on
# importe le module de reference comme un module flat.
from test_gemma_false_positive_filter import (  # noqa: E402
    SOURCE_TEXT,
    TestEntity,
    build_dataset,
    evaluate,
    parse_verdicts,
    print_report,
)

DEFAULT_BASE_URL = os.getenv("LMSTUDIO_BASE_URL", "http://localhost:1234/v1")
DEFAULT_MODEL = os.getenv("LMSTUDIO_MODEL", "google/gemma-4-e4b")
# Gemma 4 has reasoning capability: budget reservoir must cover thinking tokens
# (often 500-1500) PLUS the actual answer. 300 was way too low (POC v1 returned
# empty content because the model burned the budget thinking).
DEFAULT_MAX_OUTPUT_TOKENS = 2000
DEFAULT_TEMPERATURE = 0.0
DEFAULT_TIMEOUT = 300.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="URL du serveur OpenAI-compatible")
    parser.add_argument("--model", default=DEFAULT_MODEL,
                        help="Identifiant du modele cote serveur")
    parser.add_argument("--context-window", type=int, default=200,
                        help="Caracteres de contexte autour de chaque entite")
    parser.add_argument("--temperature", type=float, default=DEFAULT_TEMPERATURE)
    parser.add_argument("--max-output-tokens", type=int, default=DEFAULT_MAX_OUTPUT_TOKENS)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                        help="Timeout HTTP en secondes")
    parser.add_argument("--reasoning-effort", default="minimal",
                        choices=["none", "minimal", "low", "medium", "high", "default"],
                        help=("Niveau de raisonnement Gemma 4. 'minimal' coupe quasi "
                              "tout le thinking, 'default' laisse LM Studio decider. "
                              "Pour un task de classification courte, 'minimal' suffit "
                              "et divise la latence par ~10."))
    return parser.parse_args()


def health_check(base_url: str) -> List[str]:
    """Verifie que le serveur repond et retourne la liste des modeles disponibles."""
    url = base_url.rstrip("/") + "/models"
    print(f"[1/4] Health-check sur {url} ...")
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
        print(f"      ERREUR : impossible de joindre le serveur ({exc}).")
        print(f"      Verifie que LM Studio tourne et que le serveur est demarre.")
        raise SystemExit(2) from exc

    models = [m.get("id", "?") for m in data.get("data", [])]
    print(f"      OK -> {len(models)} modele(s) disponible(s) : {models}")
    return models


def chat_completion(
    base_url: str,
    model: str,
    prompt: str,
    max_tokens: int,
    temperature: float,
    timeout: float,
    reasoning_effort: str = "minimal",
) -> Tuple[str, float]:
    """Appelle POST /v1/chat/completions et renvoie (content, elapsed_seconds)."""
    url = base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": temperature,
        "stream": False,
    }
    # Gemma 4 reasoning toggle. LM Studio honors several conventions, on les passe
    # toutes pour maximiser les chances de couper le thinking quel que soit le
    # parser interne :
    #   - "reasoning_effort" (OpenAI standard, depuis o1/gpt-5)
    #   - "reasoning.effort" (Anthropic-style nested)
    #   - "thinking" (Anthropic Claude convention)
    if reasoning_effort != "default":
        payload["reasoning_effort"] = reasoning_effort
        payload["reasoning"] = {"effort": reasoning_effort}
        if reasoning_effort in ("none", "minimal"):
            payload["thinking"] = {"type": "disabled"}
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer lm-studio"},
    )
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
    elapsed = time.monotonic() - started
    data = json.loads(raw)
    message = data["choices"][0]["message"]
    finish_reason = data["choices"][0].get("finish_reason", "?")
    usage = data.get("usage", {})

    content = message.get("content") or ""
    # Gemma 4 reasoning models can emit thinking in a separate field
    reasoning = message.get("reasoning_content") or message.get("reasoning") or ""

    print(f"      finish_reason = {finish_reason}")
    print(f"      usage         = {usage}")
    if reasoning:
        preview = reasoning[:200].replace("\n", " ")
        print(f"      reasoning     = {len(reasoning)} chars -> {preview!r}...")
    if not content and reasoning:
        # Fallback: model emitted answer inside the reasoning field, or only thought
        # without producing a final answer. Try to extract verdicts from reasoning.
        print(f"      [WARN] content vide, fallback sur reasoning_content")
        content = reasoning

    return content, elapsed


def run_inference(
    base_url: str,
    model: str,
    entities: List[TestEntity],
    context_window: int,
    max_output_tokens: int,
    temperature: float,
    timeout: float,
    reasoning_effort: str = "minimal",
) -> Tuple[str, str, float]:
    from pii_detector.infrastructure.validation.prompt_templates import (
        build_batch_prompt,
    )

    print(f"[3/4] Inference via {base_url} (model={model}, reasoning={reasoning_effort}) "
          f"sur {len(entities)} entites ...")
    prompt = build_batch_prompt(entities, SOURCE_TEXT, context_window)
    response_text, elapsed = chat_completion(
        base_url=base_url,
        model=model,
        prompt=prompt,
        max_tokens=max_output_tokens,
        temperature=temperature,
        timeout=timeout,
        reasoning_effort=reasoning_effort,
    )
    print(f"      Reponse en {elapsed:.2f}s ({len(response_text)} chars)")
    return prompt, response_text, elapsed


def main() -> int:
    args = parse_args()

    available = health_check(args.base_url)
    if available and args.model not in available:
        print(f"      AVERTISSEMENT : '{args.model}' absent de la liste exposee.")
        print(f"      LM Studio peut neanmoins le charger a la volee (JIT loading).")

    print(f"[2/4] Construction du dataset ...")
    entities = build_dataset()
    print(f"      OK -> {len(entities)} entites ({sum(e.is_true_positive for e in entities)} TP, "
          f"{sum(not e.is_true_positive for e in entities)} FP)")

    _prompt, response, elapsed = run_inference(
        args.base_url,
        args.model,
        entities,
        args.context_window,
        args.max_output_tokens,
        args.temperature,
        args.timeout,
        args.reasoning_effort,
    )
    confirmed, rejected = parse_verdicts(response, len(entities))
    metrics = evaluate(entities, confirmed, rejected)
    print_report(entities, confirmed, rejected, response, metrics, elapsed)

    return 0 if metrics["recall_fp"] >= 0.5 and metrics["wrongly_rejected"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
