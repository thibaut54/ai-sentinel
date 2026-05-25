"""Live integration test for the LLM Judge against LM Studio Qwen 3.6.

This test exercises the real :class:`LLMJudgeValidator` end-to-end against a
running LM Studio instance with the Qwen 3.6 35B A3B model loaded and
``Structured Output`` (JSON Schema strict) enabled.

There is intentionally **no httpx mock** in this module: it is the
Python-side symmetry of the Java IT (commit ``139b10b``) that asserts spec
section 5.1 (acceptance criteria) by hitting the same physical model.

The canonical fixture mirrors the 10 Swiss-context few-shots embedded in the
system prompt (spec section 2.4). Those nine entities are the exact examples
Qwen was tuned on, so if any assertion fails persistently the prompt or the
model is mis-tuned (re-run once to discount sampling variance before
investigating).

Spec references:
    - section 1.6 : empirical reference tests (the few-shots are the gold).
    - section 2.4 : system prompt with the 10 CH-specific few-shots.
    - section 2.5 : MVP decision rule (FALSE_POSITIVE -> discard, else keep,
      non-GLiNER -> intact).
    - section 4.6 : Python IT to port (this file).
    - section 5   : acceptance criteria.

Run with::

    LLM_JUDGE_BASE_URL=http://172.22.22.63:1234/v1 \\
        rtk pytest tests/integration/test_llm_judge_pipeline.py -v

If LM Studio is unreachable (the default in CI) the entire module is
skipped via ``pytestmark``; the tests never hard-fail when the model is
offline.

Cost note:
    Each verdict takes ~3-5 s on Qwen 3.6 35B A3B, so the suite runs in
    roughly 30-45 s for the main assertion. This test is not meant to run
    on every commit; trigger it manually before publishing the feature
    branch or before tagging a release.
"""

from __future__ import annotations

import logging
import os
from typing import List, Tuple

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.validation.llm_validator import (
    LLMJudgeValidator,
)

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# LM Studio reachability
# ---------------------------------------------------------------------------


DEFAULT_BASE_URL = "http://172.22.22.63:1234/v1"
BASE_URL = os.getenv("LLM_JUDGE_BASE_URL", DEFAULT_BASE_URL)

# Mirror the blacklist enforced by ``_resolve_model_id`` so the skip
# condition never accepts a fine-tune the validator itself would reject.
_FINETUNE_BLACKLIST: Tuple[str, ...] = (
    "uncensored",
    "heretic",
    "distilled",
    "aggressive",
    "finetune",
)


def _find_qwen_base_model(ids: List[str]) -> str | None:
    """Return the first Qwen 3.6 A3B base model id (no fine-tune)."""
    for model_id in ids:
        lower = model_id.lower()
        if "qwen3.6" not in lower or "a3b" not in lower:
            continue
        if any(token in lower for token in _FINETUNE_BLACKLIST):
            continue
        return model_id
    return None


def _lm_studio_reachable() -> bool:
    """Probe LM Studio for a working Qwen 3.6 A3B inference endpoint.

    Two-step check:

    1. ``GET /v1/models`` must answer HTTP 200 within 3 s and advertise at
       least one model id containing ``qwen3.6`` and ``a3b`` that is not a
       fine-tune (see :data:`_FINETUNE_BLACKLIST`).
    2. ``POST /v1/chat/completions`` with ``max_tokens=1`` must answer HTTP
       200 within 10 s. This guards against the failure mode where LM
       Studio lists the model but the runtime cannot generate (e.g. GPU
       OOM, model not actually loaded), which would otherwise cause every
       judge call to 500 and every test to fail with fail_open=True.

    Returns ``True`` only when both probes succeed.
    """
    try:
        response = httpx.get(f"{BASE_URL}/models", timeout=3.0)
        if response.status_code != 200:
            return False
        ids = [m["id"] for m in response.json().get("data", [])]
    except (httpx.HTTPError, ValueError, KeyError):
        return False

    model_id = _find_qwen_base_model(ids)
    if model_id is None:
        return False

    # Cheap inference probe: 1 token, no JSON schema. Catches the "model
    # listed but inference broken" failure mode we hit during validation.
    try:
        probe = httpx.post(
            f"{BASE_URL}/chat/completions",
            json={
                "model": model_id,
                "messages": [{"role": "user", "content": "ping"}],
                "max_tokens": 1,
                "temperature": 0.0,
                "stream": False,
            },
            timeout=10.0,
        )
    except httpx.HTTPError:
        return False
    return probe.status_code == 200


# Skip the whole module if LM Studio is offline / not loaded with Qwen 3.6.
pytestmark = pytest.mark.skipif(
    not _lm_studio_reachable(),
    reason=(
        f"LM Studio not reachable on {BASE_URL} — run LM Studio with "
        "Qwen 3.6 35B A3B + Structured Output to enable this suite."
    ),
)


# ---------------------------------------------------------------------------
# Canonical fixture (mirrors the 10 few-shots in spec §2.4)
# ---------------------------------------------------------------------------


# Single source text embedding 5 TRUE_POSITIVE and 4 FALSE_POSITIVE
# candidates. The exact substrings are also referenced verbatim by the
# system prompt examples (spec §2.4), so Qwen MUST classify them
# correctly. If a single assertion fails intermittently, re-run; if the
# failure persists the prompt or model is mis-tuned.
TEXT_WITH_MIXED_DETECTIONS = (
    "Voici le compte rapprovisionnement vide en montant 92366499.59 - "
    "reference sur le ticket PCV-1189 ouvert par l'employe ayant pour AVS "
    "756.3407.8913.03, son telephone 021 316 01 57, et son IBAN "
    "CH6930000011100005458. Le mot de passe initial pour la console etait "
    "\"DGAIC\" mais a depuis ete rotated. Le serveur cible est 10.217.4.11 "
    "(RFC1918). La carte de paiement test est 374111111111111 (AmEx). Le "
    "code projet est PCV-1189 et le mobile pro 41780007878."
)


# Each tuple: (value, pii_type, source, expected_verdict_after_judge).
# - "kept"      : the judge must keep this entity (TRUE_POSITIVE / UNSURE).
# - "discarded" : the judge must mark this entity FALSE_POSITIVE.
# Source: spec §2.4 few-shots (canonical training signal for Qwen).
EXPECTED_ENTITIES: List[Tuple[str, str, DetectorSource, str]] = [
    # TRUE_POSITIVES (must be kept) -------------------------------------
    ("CH6930000011100005458", "IBAN",          DetectorSource.GLINER, "kept"),
    ("756.3407.8913.03",      "AVS_NUMBER",    DetectorSource.GLINER, "kept"),
    ("021 316 01 57",         "PHONE_NUMBER",  DetectorSource.GLINER, "kept"),
    ("10.217.4.11",           "IP_ADDRESS",    DetectorSource.GLINER, "kept"),
    ("374111111111111",       "CREDIT_CARD",   DetectorSource.GLINER, "kept"),
    # FALSE_POSITIVES (must be discarded) -------------------------------
    ("PCV-1189",              "NATIONAL_ID",         DetectorSource.GLINER, "discarded"),
    ("92366499.59",           "BANK_ACCOUNT_NUMBER", DetectorSource.GLINER, "discarded"),
    ("DGAIC",                 "PASSWORD",            DetectorSource.GLINER, "discarded"),
    ("41780007878",           "SOCIALNUM",           DetectorSource.GLINER, "discarded"),
]


def _build_pii_entity(
    text: str,
    value: str,
    pii_type: str,
    source: DetectorSource,
) -> PIIEntity:
    """Locate ``value`` inside ``text`` and build a ``PIIEntity`` at that span.

    Args:
        text: Source document the entity is anchored to.
        value: Exact substring to look up in ``text`` (must occur at least once).
        pii_type: GLiNER-style PII type label (``IBAN``, ``NATIONAL_ID``, etc.).
        source: Detector source — only ``DetectorSource.GLINER`` is audited by
            the judge; the other values exercise the §2.5 pass-through rule.

    Raises:
        ValueError: If ``value`` is not present in ``text`` (mis-typed fixture).
    """
    start = text.index(value)
    end = start + len(value)
    return PIIEntity(
        text=value,
        pii_type=pii_type,
        type_label=pii_type,
        start=start,
        end=end,
        score=0.85,
        source=source,
    )


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def validator() -> LLMJudgeValidator:
    """Real :class:`LLMJudgeValidator` pointing at LM Studio. No mock.

    Module-scoped so the underlying ``httpx.Client`` and
    ``ThreadPoolExecutor`` are reused across the suite; the teardown closes
    them deterministically (the constructor also wires an ``atexit`` hook
    as a belt-and-braces).
    """
    instance = LLMJudgeValidator(base_url=BASE_URL)
    try:
        yield instance
    finally:
        instance.shutdown()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_lm_studio_models_endpoint_is_reachable() -> None:
    """Independent sanity check guarding against a silent false-skip.

    Re-asserts the same predicate as :func:`_lm_studio_reachable` but
    surfaces the actual error message and model list when LM Studio is
    misconfigured (status code != 200, no Qwen 3.6 A3B, etc.). This
    mirrors the Java IT smoke test (commit ``139b10b``) so a Python and a
    JVM probe both confirm the same physical endpoint.
    """
    response = httpx.get(f"{BASE_URL}/models", timeout=5.0)
    assert response.status_code == 200, (
        f"LM Studio /v1/models returned {response.status_code} on {BASE_URL}"
    )
    ids = [m["id"] for m in response.json().get("data", [])]
    assert _find_qwen_base_model(ids) is not None, (
        f"No Qwen 3.6 A3B base model exposed on {BASE_URL} — got: {ids}"
    )


def test_judge_keeps_true_positives_and_rejects_false_positives(
    validator: LLMJudgeValidator,
) -> None:
    """End-to-end pipeline test: 5 TP must stay, 4 FP must be discarded.

    Spec §2.4 — these nine entities are the canonical few-shots embedded
    verbatim in :data:`SYSTEM_PROMPT`. The judge MUST classify them
    correctly; any persistent failure indicates a regression in the prompt
    or the model weights.
    """
    entities = [
        _build_pii_entity(TEXT_WITH_MIXED_DETECTIONS, value, pii_type, source)
        for value, pii_type, source, _ in EXPECTED_ENTITIES
    ]

    kept = validator.filter(TEXT_WITH_MIXED_DETECTIONS, entities)
    kept_values = {entity.text for entity in kept}
    log.info("[LLM-JUDGE IT] kept_values=%s", sorted(kept_values))

    expected_kept = {
        value
        for value, _, _, expected in EXPECTED_ENTITIES
        if expected == "kept"
    }
    expected_discarded = {
        value
        for value, _, _, expected in EXPECTED_ENTITIES
        if expected == "discarded"
    }

    missing_kept = expected_kept - kept_values
    assert not missing_kept, (
        f"Spec §2.4 — these TRUE_POSITIVE few-shots were wrongly rejected "
        f"by Qwen: {sorted(missing_kept)}. Re-run once to discount sampling "
        f"variance; if persistent, the prompt or the model is mis-tuned."
    )

    wrongly_kept = kept_values & expected_discarded
    assert not wrongly_kept, (
        f"Spec §2.4 — these FALSE_POSITIVE few-shots were NOT discarded by "
        f"Qwen: {sorted(wrongly_kept)}. Re-run once to discount sampling "
        f"variance; if persistent, the prompt or the model is mis-tuned."
    )


def test_judge_preserves_non_gliner_entities(
    validator: LLMJudgeValidator,
) -> None:
    """Spec §2.5 — entities from Regex/Presidio MUST never be touched by the judge.

    The validator's GLiNER-only scope is a hard contract: deterministic
    detectors are treated as ground truth so the judge never erodes
    Presidio/Regex recall. This test feeds two of the canonical FP values
    (``PCV-1189``, ``DGAIC``) but tags them as Regex / Presidio so the
    judge MUST pass them through unchanged.
    """
    regex_entity = _build_pii_entity(
        TEXT_WITH_MIXED_DETECTIONS,
        "PCV-1189",
        "NATIONAL_ID",
        DetectorSource.REGEX,
    )
    presidio_entity = _build_pii_entity(
        TEXT_WITH_MIXED_DETECTIONS,
        "DGAIC",
        "PASSWORD",
        DetectorSource.PRESIDIO,
    )

    kept = validator.filter(
        TEXT_WITH_MIXED_DETECTIONS, [regex_entity, presidio_entity]
    )
    kept_values = {entity.text for entity in kept}

    assert "PCV-1189" in kept_values, (
        "Spec §2.5 — REGEX entity was filtered by the judge; the post-filter "
        "must never touch non-GLiNER entities."
    )
    assert "DGAIC" in kept_values, (
        "Spec §2.5 — PRESIDIO entity was filtered by the judge; the post-filter "
        "must never touch non-GLiNER entities."
    )


def test_judge_handles_empty_entity_list(
    validator: LLMJudgeValidator,
) -> None:
    """Edge case: empty input returns empty output without any HTTP call.

    Documented short-circuit in :meth:`LLMJudgeValidator.filter` — guards
    against accidental network traffic when the upstream detectors return
    no GLiNER findings.
    """
    result = validator.filter(TEXT_WITH_MIXED_DETECTIONS, [])
    assert result == []


@pytest.mark.skip(
    reason=(
        "fail-open live coverage requires temporarily blocking LM Studio "
        "mid-flight; out of scope for the live IT (covered by unit tests "
        "patching ``httpx``)."
    )
)
def test_judge_fail_open_keeps_entity_on_timeout(
    validator: LLMJudgeValidator,
) -> None:
    """Spec §2.6 — fail_open=True keeps the entity when LM Studio is unreachable.

    Live coverage of this path would require blocking LM Studio between
    two calls, which conflicts with the rest of the suite running against
    the same module-scoped validator. The fail-open behaviour is covered
    extensively by the unit tests in
    ``tests/unit/infrastructure/validation/test_llm_validator.py`` where
    ``httpx`` is patched to raise.
    """


if __name__ == "__main__":  # pragma: no cover - manual entry point
    pytest.main([__file__, "-s", "-v"])
