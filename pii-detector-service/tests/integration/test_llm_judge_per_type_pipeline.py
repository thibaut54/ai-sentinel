"""Live integration test for the v7 per-type LLM-judge routing.

This is the per-type counterpart of :mod:`test_llm_judge_pipeline`. It runs the
real :class:`LLMJudgeValidator` end-to-end against a running LM Studio instance
with ``per_type_prompts=True`` (Option B of the PROD_INTEGRATION plan), so each
finding is judged with the ``[types.<pii_type>]`` body of
``config/llm-judge-prompts-per-type.toml`` (``v7_per_type_hybrid_ext``) instead
of the single global ``SYSTEM_PROMPT``.

What it proves (plan §B.5 / verification step 2):
    1. Per-type routing is wired: the system prompt actually sent for a
       ``BANK_ACCOUNT`` finding equals ``builder("BANK_ACCOUNT")`` — the
       per-type body, not the global prompt.
    2. Recall is preserved on the new GLiNER2 taxonomy: real TRUE_POSITIVE
       findings across financial / security / crypto types are kept (the whole
       reason v7 exists — the generic prompt over-rejects BANK_ACCOUNT).

There is intentionally no httpx mock here. If LM Studio is unreachable the whole
module is skipped via ``pytestmark`` (same probe as the global pipeline IT).

Run with::

    rtk pytest tests/integration/test_llm_judge_per_type_pipeline.py -s -v

Cost note: ~3-5 s per verdict on Qwen 3.6 35B A3B; this suite issues ~6 verdicts.
"""

from __future__ import annotations

import logging
import os
import uuid
from typing import List, Tuple

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.validation import prompt_templates as pt
from pii_detector.infrastructure.validation.llm_validator import (
    _DEFAULT_BASE_URL,
    _DEFAULT_PREFERRED_MODEL,
    _load_llm_judge_toml_defaults,
    LLMJudgeValidator,
)

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# LM Studio reachability (mirrors test_llm_judge_pipeline)
# ---------------------------------------------------------------------------


BASE_URL = (
    os.getenv("LLM_JUDGE_BASE_URL")
    or _load_llm_judge_toml_defaults().get("base_url")
    or _DEFAULT_BASE_URL
)

PREFERRED_MODEL = (
    os.getenv("LLM_JUDGE_PREFERRED_MODEL")
    or _load_llm_judge_toml_defaults().get("preferred_model")
    or _DEFAULT_PREFERRED_MODEL
)

_FINETUNE_BLACKLIST: Tuple[str, ...] = (
    "uncensored",
    "heretic",
    "distilled",
    "aggressive",
    "finetune",
)


def _find_qwen_base_model(
    ids: List[str], preferred: str = PREFERRED_MODEL
) -> str | None:
    """Return the model the judge will resolve: exact ``preferred`` then a
    Qwen 3.6 A3B base model id (no fine-tune). Mirrors prod resolution."""
    if preferred in ids:
        return preferred
    for model_id in ids:
        lower = model_id.lower()
        if "qwen3.6" not in lower or "a3b" not in lower:
            continue
        if any(token in lower for token in _FINETUNE_BLACKLIST):
            continue
        return model_id
    return None


def _lm_studio_reachable() -> bool:
    """Probe ``/v1/models`` then a 1-token completion (model actually warm)."""
    try:
        response = httpx.get(f"{BASE_URL}/models", timeout=15.0)
        response.raise_for_status()
        ids = [m["id"] for m in response.json().get("data", [])]
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        log.warning(
            "[LM-STUDIO-PROBE] /v1/models failed on %s (%s: %s); suite skipped.",
            BASE_URL,
            exc.__class__.__name__,
            exc,
        )
        return False

    model_id = _find_qwen_base_model(ids)
    if model_id is None:
        log.warning(
            "[LM-STUDIO-PROBE] no Qwen 3.6 A3B base model on %s (available=%s).",
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
            "[LM-STUDIO-PROBE] inference probe failed on %s (%s: %s).",
            BASE_URL,
            exc.__class__.__name__,
            exc,
        )
        return False
    return probe.status_code == 200


pytestmark = pytest.mark.skipif(
    not _lm_studio_reachable(),
    reason=(
        f"LM Studio not reachable on {BASE_URL} — run LM Studio with "
        "Qwen 3.6 35B A3B + Structured Output to enable this suite."
    ),
)


# ---------------------------------------------------------------------------
# Fixture text + GLiNER2 TRUE_POSITIVE findings (one per per-type body)
# ---------------------------------------------------------------------------


# A realistic technical document carrying valid PII whose FORMAT matches the
# claimed pii_type. Under v7 per-type routing every GLiNER2 finding below must
# survive (recall sacred). The values are chosen to be structurally valid so
# the per-type bodies classify them TRUE_POSITIVE.
TEXT_WITH_GLINER2_PII = (
    "Coordonnees bancaires du beneficiaire : IBAN CH6930000011100005458, "
    "compte interne 0023 6589 12. Le wallet de remboursement crypto est "
    "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq. Pour le SAV, conserver l'IMEI "
    "356938035643809 de l'appareil retourne. Cle d'API du connecteur : "
    "sk-live-9f3b2c7a1e4d806b5a2f9c3e7d1b4a60. Mot de passe initial de la "
    "console : Tr0ub4dour&3xK9. Adresse du noeud applicatif : 10.217.4.11."
)


# (value, pii_type, source) — all GLiNER source so all are audited; all are
# structurally valid TRUE_POSITIVES that v7 per-type must keep.
TRUE_POSITIVE_FINDINGS: List[Tuple[str, str, DetectorSource]] = [
    ("CH6930000011100005458", "IBAN", DetectorSource.GLINER),
    ("0023 6589 12", "BANK_ACCOUNT", DetectorSource.GLINER),
    (
        "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
        "BITCOIN_ADDRESS",
        DetectorSource.GLINER,
    ),
    ("356938035643809", "IMEI", DetectorSource.GLINER),
    ("sk-live-9f3b2c7a1e4d806b5a2f9c3e7d1b4a60", "API_KEY", DetectorSource.GLINER),
    ("Tr0ub4dour&3xK9", "PASSWORD", DetectorSource.GLINER),
]


def _build_pii_entity(
    text: str, value: str, pii_type: str, source: DetectorSource
) -> PIIEntity:
    """Anchor ``value`` inside ``text`` and build a GLiNER-style ``PIIEntity``."""
    start = text.index(value)
    return PIIEntity(
        text=value,
        pii_type=pii_type,
        type_label=pii_type,
        start=start,
        end=start + len(value),
        score=0.85,
        source=source,
    )


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def per_type_validator() -> LLMJudgeValidator:
    """Real validator with per-type routing ON, pointing at LM Studio."""
    pt._reset_per_type_cache_for_tests()
    instance = LLMJudgeValidator(base_url=BASE_URL, per_type_prompts=True)
    try:
        yield instance
    finally:
        instance.shutdown()
        pt._reset_per_type_cache_for_tests()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_per_type_routing_is_enabled_and_loaded(
    per_type_validator: LLMJudgeValidator,
) -> None:
    """The validator must have loaded the v7 spec and a working builder."""
    assert per_type_validator.per_type_prompts is True
    assert per_type_validator._per_type_name == "v7_per_type_hybrid_ext"
    assert per_type_validator._per_type_builder is not None


def test_per_type_system_prompt_is_actually_sent(
    per_type_validator: LLMJudgeValidator,
) -> None:
    """Spy the HTTP payload: the system prompt sent for a BANK_ACCOUNT finding
    must be the per-type body, not the global SYSTEM_PROMPT."""
    _name, build = pt.load_per_type_prompts()
    expected_system_prompt = build("BANK_ACCOUNT")

    sent_payloads: List[dict] = []
    real_post = per_type_validator._get_client().post

    def _spy_post(url, json=None, **kwargs):  # noqa: A002 - mirror httpx kwarg
        sent_payloads.append(json)
        return real_post(url, json=json, **kwargs)

    client = per_type_validator._get_client()
    original_post = client.post
    client.post = _spy_post  # type: ignore[assignment]
    try:
        entity = _build_pii_entity(
            TEXT_WITH_GLINER2_PII, "0023 6589 12", "BANK_ACCOUNT",
            DetectorSource.GLINER,
        )
        per_type_validator._judge_one(
            "0023 6589 12", entity, uuid.uuid4().hex[:12]
        )
    finally:
        client.post = original_post  # type: ignore[assignment]

    assert sent_payloads, "no HTTP call captured"
    system_sent = sent_payloads[0]["messages"][0]["content"]
    assert system_sent == expected_system_prompt
    assert system_sent != pt.SYSTEM_PROMPT
    assert "TYPE = BANK_ACCOUNT" in system_sent


def test_per_type_routing_preserves_true_positives(
    per_type_validator: LLMJudgeValidator,
) -> None:
    """Recall check (plan §B.5): every structurally valid GLiNER2 TP must be
    kept under v7 per-type routing — this is the regression the generic prompt
    introduced on the new taxonomy (BANK_ACCOUNT over-rejection)."""
    entities = [
        _build_pii_entity(TEXT_WITH_GLINER2_PII, value, pii_type, source)
        for value, pii_type, source in TRUE_POSITIVE_FINDINGS
    ]

    kept = per_type_validator.filter(TEXT_WITH_GLINER2_PII, entities)
    kept_values = {e.text for e in kept}

    log.info(
        "[LLM-JUDGE PER-TYPE IT] in=%d kept=%d kept_values=%s",
        len(entities),
        len(kept),
        sorted(kept_values),
    )

    expected = {value for value, _, _ in TRUE_POSITIVE_FINDINGS}
    missing = expected - kept_values
    assert not missing, (
        f"v7 per-type wrongly rejected these TRUE_POSITIVE findings: "
        f"{sorted(missing)}. Re-run once to discount sampling variance; if "
        f"persistent, the per-type body for that type over-rejects."
    )


def test_per_detector_routing_audits_only_flagged_sources(
    per_type_validator: LLMJudgeValidator,
) -> None:
    """End-to-end per-detector routing against the live judge.

    Mirrors what the gRPC service does: derive the audited source set from the
    per-detector judge flags (``_audit_sources_from_flags``) and pass it to the
    validator. With only ``gliner2_judge_enabled=True``, a GLiNER2 finding is
    judged while a REGEX finding passes through untouched."""
    # ``in`` is a reserved word, so the package path can't be imported with a
    # plain ``import`` statement -> use importlib (same as the unit tests).
    import importlib

    PIIDetectionServicer = importlib.import_module(
        "pii_detector.infrastructure.adapter.in.grpc.pii_service"
    ).PIIDetectionServicer

    flags_gliner2_only = {
        "gliner_judge_enabled": False,
        "presidio_judge_enabled": False,
        "regex_judge_enabled": False,
        "openmed_judge_enabled": False,
        "gliner2_judge_enabled": True,
    }
    audit_sources = PIIDetectionServicer._audit_sources_from_flags(
        flags_gliner2_only
    )
    assert audit_sources == {DetectorSource.GLINER2}

    gliner2_tp = _build_pii_entity(
        TEXT_WITH_GLINER2_PII, "CH6930000011100005458", "IBAN",
        DetectorSource.GLINER2,
    )
    regex_entity = _build_pii_entity(
        TEXT_WITH_GLINER2_PII, "10.217.4.11", "IP_ADDRESS",
        DetectorSource.REGEX,
    )

    kept = per_type_validator.filter(
        TEXT_WITH_GLINER2_PII,
        [gliner2_tp, regex_entity],
        audit_sources=audit_sources,
    )
    kept_values = {e.text for e in kept}

    # GLiNER2 finding was judged (valid IBAN -> TRUE_POSITIVE -> kept) and the
    # REGEX finding bypassed the judge entirely (not in the audited set).
    assert "CH6930000011100005458" in kept_values
    assert "10.217.4.11" in kept_values
    assert getattr(regex_entity, "judge_status", None) is not None

    # Inverse: no detector flag on -> empty audit set -> nothing judged.
    empty_audit = PIIDetectionServicer._audit_sources_from_flags(
        {k: False for k in flags_gliner2_only}
    )
    assert empty_audit == set()
    kept_none = per_type_validator.filter(
        TEXT_WITH_GLINER2_PII, [gliner2_tp], audit_sources=empty_audit
    )
    assert {e.text for e in kept_none} == {"CH6930000011100005458"}


if __name__ == "__main__":  # pragma: no cover - manual entry point
    pytest.main([__file__, "-s", "-v"])
