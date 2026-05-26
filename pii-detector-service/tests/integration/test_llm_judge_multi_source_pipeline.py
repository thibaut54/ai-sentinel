"""Live IT proving the LLM judge can audit multiple detector sources.

This test is the multi-source counterpart of :mod:`test_llm_judge_pipeline`.
It demonstrates that :class:`LLMJudgeValidator` can be configured (via the
``audit_sources`` parameter) to audit *any* detector, not just GLiNER as
originally scoped by spec section 2.5.

The fixture replays **real OpenMed false positives** captured in a previous
benchmark run (``target/openmed-fp-eval/findings.jsonl``). Each FP record
provides:

- ``value``: the substring the OpenMed model wrongly classified as PII
- ``pii_type``: the bogus type label produced by OpenMed
- ``axis``: the failure mode bucket (``long_context``, ``look_alikes``,
  ``explicit_negatives``, ...) — kept around for debugging

We rebuild a minimal synthetic context around each value so the judge has
enough surrounding text to disambiguate (300-char window per
:data:`prompt_templates.DEFAULT_CONTEXT_WINDOW`), then tag every entity as
``DetectorSource.OPENMED`` and submit them to a validator whose
``audit_sources`` includes ``OPENMED``.

Two scenarios cover the contract:

1. ``audit_sources={OPENMED}`` -> the judge **audits** the FPs and rejects
   the majority of them (rejection rate >= 50%, recall-preserving floor).
2. ``audit_sources={GLINER}`` (MVP default) -> the judge **passthroughs**
   OpenMed entities; rejection rate must be 0%.

Run with::

    LLM_JUDGE_BASE_URL=http://172.22.22.63:1234/v1 \\
        rtk proxy pytest \\
        tests/integration/test_llm_judge_multi_source_pipeline.py \\
        -v -s --log-cli-level=INFO --no-cov

Skipped automatically when LM Studio is unreachable (same pattern as
:mod:`test_llm_judge_pipeline`).

Spec references:
    - section 1.4 (was backlog: ``llm_judge_sources``)
    - section 2.5 (rule extended via ``audit_sources``)
    - section 7 (risk: extended scope -> proportional latency)
"""

from __future__ import annotations

import json
import logging
import os
import time
import uuid
from pathlib import Path
from typing import Iterable, List, Tuple

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.observability.throughput_logger import (
    ThroughputLogger,
)
from pii_detector.infrastructure.validation.llm_validator import (
    LLMJudgeValidator,
)

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# LM Studio reachability (mirrors test_llm_judge_pipeline._lm_studio_reachable)
# ---------------------------------------------------------------------------


DEFAULT_BASE_URL = "http://172.22.22.63:1234/v1"
BASE_URL = os.getenv("LLM_JUDGE_BASE_URL", DEFAULT_BASE_URL)

_FINETUNE_BLACKLIST: Tuple[str, ...] = (
    "uncensored",
    "heretic",
    "distilled",
    "aggressive",
    "finetune",
)


def _find_qwen_base_model(ids: Iterable[str]) -> str | None:
    for model_id in ids:
        lower = model_id.lower()
        if "qwen3.6" not in lower or "a3b" not in lower:
            continue
        if any(token in lower for token in _FINETUNE_BLACKLIST):
            continue
        return model_id
    return None


def _lm_studio_reachable() -> bool:
    """Two-step probe matching the main IT: /v1/models + cheap inference.

    The inference probe uses a 45 s timeout because a cold-started LM Studio
    instance can take 15-25 s to page the 35B Qwen 3.6 weights into RAM /
    VRAM on the first request after sleep. A previous 10 s budget caused
    spurious skips on developer machines where the model was listed but not
    yet "warm". Each failure mode is logged at WARNING so a skip never goes
    silent — the operator can tell whether to reload the model, enable
    Structured Output, or check connectivity.
    """
    try:
        response = httpx.get(f"{BASE_URL}/models", timeout=15.0)
        response.raise_for_status()
        ids = [m["id"] for m in response.json().get("data", [])]
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        log.warning(
            "[LM-STUDIO-PROBE] /v1/models failed on %s (%s: %s); suite skipped. "
            "Check that LM Studio's HTTP server is running and reachable.",
            BASE_URL,
            exc.__class__.__name__,
            exc,
        )
        return False

    model_id = _find_qwen_base_model(ids)
    if model_id is None:
        log.warning(
            "[LM-STUDIO-PROBE] no Qwen 3.6 A3B base model on %s "
            "(available=%s); load qwen/qwen3.6-35b-a3b in LM Studio. "
            "Suite skipped.",
            BASE_URL,
            ids,
        )
        return False

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
            timeout=45.0,
        )
    except httpx.HTTPError as exc:
        log.warning(
            "[LM-STUDIO-PROBE] inference probe failed on %s "
            "(%s: %s); the model is listed but not warm/usable. "
            "Click 'Load' on qwen/qwen3.6-35b-a3b in LM Studio. Suite skipped.",
            BASE_URL,
            exc.__class__.__name__,
            exc,
        )
        return False
    if probe.status_code != 200:
        log.warning(
            "[LM-STUDIO-PROBE] inference probe returned HTTP %d on %s "
            "(body=%s); suite skipped.",
            probe.status_code,
            BASE_URL,
            probe.text[:200],
        )
        return False
    return True


pytestmark = pytest.mark.skipif(
    not _lm_studio_reachable(),
    reason=(
        f"LM Studio not reachable on {BASE_URL} — run LM Studio with "
        "Qwen 3.6 35B A3B + Structured Output to enable this suite."
    ),
)


# ---------------------------------------------------------------------------
# OpenMed FP fixture loader
# ---------------------------------------------------------------------------


# ``target/openmed-fp-eval/findings.jsonl`` is produced by a previous OpenMed
# benchmark run (kept under target/ but checked into the repo for IT replay).
_FINDINGS_PATH = (
    Path(__file__).resolve().parents[2]
    / "target"
    / "openmed-fp-eval"
    / "findings.jsonl"
)


def _load_openmed_false_positives(
    limit: int = 8,
    min_value_length: int = 3,
) -> List[dict]:
    """Return ``limit`` real OpenMed false positives, deduped by value.

    Args:
        limit: Number of distinct FPs to keep. Higher = better coverage but
            longer test runtime (~3-5s per LLM call on Qwen 3.6 35B).
        min_value_length: Drop pathologically short values (single char,
            etc.) that yield no useful prompt context.
    """
    if not _FINDINGS_PATH.exists():
        pytest.skip(
            f"OpenMed benchmark dataset not found at {_FINDINGS_PATH}. "
            "Run the openmed-fp-eval benchmark first."
        )

    seen_values: set[str] = set()
    selected: List[dict] = []
    for line in _FINDINGS_PATH.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        if record.get("verdict") != "FP":
            continue
        value = record.get("value")
        if not value or len(value) < min_value_length or value in seen_values:
            continue
        seen_values.add(value)
        selected.append(record)
        if len(selected) >= limit:
            break

    return selected


def _build_text_and_entities(
    fps: List[dict],
) -> Tuple[str, List[PIIEntity]]:
    """Build a synthetic French context that embeds each FP value.

    Each FP gets its own sentence whose phrasing mimics the original ``axis``
    (e.g. ``long_context``, ``explicit_negatives``) so the judge sees a
    similar disambiguation signal to the one OpenMed had at detection time.

    Returns:
        ``(text, entities)`` where ``entities[i]`` is anchored on the
        substring ``fps[i]["value"]`` inside ``text`` and tagged
        ``DetectorSource.OPENMED``.
    """
    sentences: List[str] = []
    positions: List[int] = []

    for fp in fps:
        value = fp["value"]
        pii_type_lower = fp["pii_type"].replace("_", " ").lower()
        sentence = (
            f"Dans la documentation technique, le champ {pii_type_lower} "
            f"est référencé comme {value} pour fins de revue."
        )
        positions.append(sum(len(s) + 1 for s in sentences))  # offset of new sentence (+1 for joiner space)
        sentences.append(sentence)

    text = " ".join(sentences)
    entities: List[PIIEntity] = []
    for fp, sentence_offset in zip(fps, positions):
        value = fp["value"]
        # Locate the value WITHIN this sentence (anchored to avoid colliding
        # with another FP that happens to share a prefix).
        sentence_end = sentence_offset + len(sentences[positions.index(sentence_offset)])
        local_idx = text.find(value, sentence_offset, sentence_end)
        if local_idx < 0:
            # Defensive: skip FPs whose value cannot be located (shouldn't
            # happen given how we build the sentence, but guarding anyway).
            continue
        entities.append(
            PIIEntity(
                text=value,
                pii_type=fp["pii_type"],
                type_label=fp["pii_type"],
                start=local_idx,
                end=local_idx + len(value),
                score=float(fp.get("score", 0.85)),
                source=DetectorSource.OPENMED,
            )
        )

    return text, entities


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def throughput() -> ThroughputLogger:
    """Per-test ThroughputLogger drained before pytest re-captures stdout."""
    instance = ThroughputLogger()
    try:
        yield instance
    finally:
        instance.shutdown(timeout=2.0)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_judge_rejects_real_openmed_false_positives_when_audited(
    throughput: ThroughputLogger,
) -> None:
    """Spec §10 extension — audit_sources={OPENMED} -> majority of OpenMed FPs rejected.

    Uses 8 real OpenMed FPs (verdict='FP' in findings.jsonl). The judge
    must reject at least 50% of them; we use a soft floor because Qwen 3.6
    occasionally hedges ambiguous values to UNSURE (kept), and because some
    OpenMed 'FPs' are genuinely close calls.
    """
    fps = _load_openmed_false_positives(limit=8)
    assert fps, "findings.jsonl must yield at least one usable FP record"

    text, entities = _build_text_and_entities(fps)
    assert entities, "every selected FP must produce a PIIEntity"
    log.info(
        "[OPENMED-FP-IT] selected %d FPs across types=%s",
        len(entities),
        sorted({e.pii_type for e in entities}),
    )

    validator = LLMJudgeValidator(
        base_url=BASE_URL,
        audit_sources={DetectorSource.OPENMED},
        fail_open=True,  # transient LM Studio hiccups -> keep, don't punish
        max_workers=4,
    )
    try:
        request_id = uuid.uuid4().hex[:12]
        start = time.monotonic()
        kept = validator.filter(text, entities)
        duration_s = time.monotonic() - start

        rejected_count = len(entities) - len(kept)
        throughput.log_phase(
            "multi_source_audit",
            request_id=request_id,
            chars=len(text),
            duration_s=duration_s,
            entities_in=len(entities),
            entities_kept=len(kept),
            entities_rejected=rejected_count,
            llm_total_calls=len(entities),
            audit_sources="OPENMED",
        )

        rejection_rate = rejected_count / len(entities)
        kept_values = sorted(entity.text for entity in kept)
        log.info(
            "[OPENMED-FP-IT] request_id=%s rejected=%d/%d (%.0f%%) "
            "kept_values=%s",
            request_id,
            rejected_count,
            len(entities),
            rejection_rate * 100,
            kept_values,
        )

        assert rejection_rate >= 0.5, (
            f"Spec §10 — judge rejected only {rejection_rate:.0%} of "
            f"{len(entities)} real OpenMed FPs when audit_sources={{OPENMED}} "
            f"(expected >= 50%). Kept values: {kept_values}"
        )
    finally:
        validator.shutdown()


def test_judge_passthroughs_openmed_when_audit_sources_excludes_openmed(
    throughput: ThroughputLogger,
) -> None:
    """Spec §2.5 rétro-compat — audit_sources={GLINER} (default) -> OpenMed passthrough.

    Critical safety net: the MVP default audit_sources is ``{GLINER}``, and
    deployments that never set the env / TOML must keep the original
    GLiNER-only scope. We feed the same OpenMed FPs but expect ZERO
    rejections (the judge does not even invoke LM Studio for them).
    """
    fps = _load_openmed_false_positives(limit=4)
    text, entities = _build_text_and_entities(fps)

    validator = LLMJudgeValidator(
        base_url=BASE_URL,
        audit_sources={DetectorSource.GLINER},  # MVP default
        fail_open=True,
    )
    try:
        request_id = uuid.uuid4().hex[:12]
        start = time.monotonic()
        kept = validator.filter(text, entities)
        duration_s = time.monotonic() - start

        throughput.log_phase(
            "multi_source_audit",
            request_id=request_id,
            chars=len(text),
            duration_s=duration_s,
            entities_in=len(entities),
            entities_kept=len(kept),
            entities_rejected=len(entities) - len(kept),
            llm_total_calls=0,  # passthrough: no LLM call expected
            audit_sources="GLINER",
        )

        assert len(kept) == len(entities), (
            "Spec §2.5 — with audit_sources={GLINER}, OpenMed entities must "
            f"passthrough unchanged. Got {len(kept)}/{len(entities)} kept."
        )
        # Performance contract for the passthrough path: no LLM round-trip
        # means the call must complete in well under a second even with 4
        # entities (purely O(n) Python).
        assert duration_s < 1.0, (
            "Passthrough path took >= 1s; the judge unexpectedly invoked "
            "LM Studio for non-audited sources."
        )
    finally:
        validator.shutdown()


if __name__ == "__main__":  # pragma: no cover - manual entry point
    pytest.main([__file__, "-s", "-v", "--log-cli-level=INFO", "--no-cov"])
