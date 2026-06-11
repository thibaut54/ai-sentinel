"""
LLM-as-Judge post-detection validator (Qwen 3.6 via LM Studio).

This module exposes :class:`LLMJudgeValidator`, an implementation of the
:class:`~pii_detector.domain.port.pii_post_filter_protocol.PIIPostFilterProtocol`
that audits detected PII entities by asking a Qwen 3.6 thinking model whether
each finding is a true positive or a false positive.

Key design points (see spec section 2.3):

- **Remote-only MVP**: the ``local`` backend is documented but stays a no-op
  (LM Studio handles inference on a remote GPU).
- **Dynamic model resolution**: at first use we GET ``/v1/models`` and pick
  ``qwen/qwen3.6-35b-a3b`` (exact match) or a fuzzy fallback excluding the
  fine-tunes (``uncensored``, ``heretic``, ``distilled``, ``aggressive``,
  ``finetune``).
- **Strict JSON Schema**: payload uses ``response_format: json_schema`` with
  ``strict: true`` and ``max_tokens=2048`` to absorb Qwen 3.6's reasoning
  tokens (verified empirically in spec section 1.6).
- **Defensive parser**: ``message.reasoning_content`` is read in priority,
  ``message.content`` as fallback. Defense-in-depth strips ``<think>``
  blocks and markdown fences if the runtime ever leaks them.
- **Fail-open policy** (configurable): on timeout / HTTP error / JSON
  invalid, the entity is kept (recall preserved at all costs).
- **Concurrency**: a :class:`ThreadPoolExecutor` parallelises HTTP calls
  (``max_workers=4`` by default, aligned with LM Studio's
  ``Max Concurrent Predictions``). Cleanly shut down via ``atexit``.

References:
- Spec: ``_bmad-output/planning-artifacts/llm-judge-qwen-spec.md``
  sections 1.5 (constraints), 1.6 (empirical tests), 2.3 (adapter),
  2.4 (payload), 2.5 (decision rule), 2.6 (configuration).
"""

from __future__ import annotations

import atexit
import json
import logging
import os
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Set, Tuple

import httpx

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.judge_status import JudgeStatus
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.port.pii_post_filter_protocol import (
    PIIPostFilterProtocol,
)
from pii_detector.infrastructure.validation.prompt_templates import (
    DEFAULT_CONTEXT_WINDOW,
    PiiVerdict,
    SYSTEM_PROMPT,
    VERDICT_SCHEMA,
    build_user_prompt,
    load_per_type_prompts,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------


# Backend keys (the ``local`` backend is intentionally out of scope for the
# MVP; it stays here so a future commit can plug llama-cpp without changing
# the public contract).
BACKEND_REMOTE = "remote"
BACKEND_LOCAL = "local"

# Suffixes that identify Qwen 3.6 fine-tunes we must NOT pick by default.
# See spec section 8.4: the LM Studio instance hosts several uncensored or
# distilled variants whose behaviour has not been validated for this MVP.
_FINETUNE_BLACKLIST: Tuple[str, ...] = (
    "uncensored",
    "heretic",
    "distilled",
    "aggressive",
    "finetune",
)

# Hardcoded final-safety fallbacks. The canonical source of truth for
# operator-facing defaults is ``[llm_judge]`` in
# ``config/detection-settings.toml`` (loaded via
# :func:`_load_llm_judge_toml_defaults`). These constants are only consulted
# when the TOML cannot be read (early-import, missing file, parse error) so
# the validator keeps a sane default in degraded environments.
_FUZZY_MATCH_TOKENS = ("qwen3.6", "a3b")
_DEFAULT_BASE_URL = "http://127.0.0.1:1234/v1"
_DEFAULT_PREFERRED_MODEL = "qwen/qwen3.6-35b-a3b"


# ---------------------------------------------------------------------------
# Generation config (groups the rarely-overridden sampling knobs)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class LlmValidatorConfig:
    """Generation parameters for the ``/chat/completions`` payload.

    Groups the four sampling/budget knobs that share the same concern
    (how the model generates the verdict) so the validator constructor
    stays under the parameter budget. The defaults mirror spec section 2.6.
    """

    context_window: int = DEFAULT_CONTEXT_WINDOW
    max_tokens: int = 2048
    temperature: float = 0.2
    top_p: float = 1.0


# ---------------------------------------------------------------------------
# TOML defaults loader (single source of truth: [llm_judge] section)
# ---------------------------------------------------------------------------

_TOML_DEFAULTS_CACHE: Optional[Dict[str, Any]] = None
_TOML_DEFAULTS_LOCK = threading.Lock()


def _load_llm_judge_toml_defaults() -> Dict[str, Any]:
    """Return the ``[llm_judge]`` section of ``detection-settings.toml``.

    This is the **single source of truth** for operator-facing defaults
    (base_url, preferred_model, timeouts, ...). The result is cached at
    module level so the TOML is read at most once per process.

    On any IO / parse error the function logs a WARNING and returns an
    empty dict, so callers must combine the result with hardcoded
    fallbacks (see :data:`_DEFAULT_BASE_URL` etc).

    Returns:
        Dict of ``[llm_judge]`` settings, or ``{}`` if unavailable.
    """
    global _TOML_DEFAULTS_CACHE
    if _TOML_DEFAULTS_CACHE is not None:
        return _TOML_DEFAULTS_CACHE
    with _TOML_DEFAULTS_LOCK:
        if _TOML_DEFAULTS_CACHE is not None:
            return _TOML_DEFAULTS_CACHE
        try:
            try:
                import tomllib  # type: ignore[import-not-found]  # Python 3.11+
            except ImportError:  # pragma: no cover - py3.9/3.10 fallback
                import tomli as tomllib  # type: ignore[import-not-found,no-redef]

            # llm_validator.py lives at
            # pii_detector/infrastructure/validation/llm_validator.py
            # Service root is 4 levels up, config/ sits at that root.
            config_path = (
                Path(__file__).resolve().parents[3]
                / "config"
                / "detection-settings.toml"
            )
            with open(config_path, "rb") as f:
                config = tomllib.load(f)
            _TOML_DEFAULTS_CACHE = dict(config.get("llm_judge", {}))
        except Exception as exc:  # pragma: no cover - defensive logging path
            logger.warning(
                "Could not load [llm_judge] defaults from "
                "config/detection-settings.toml (%s: %s); falling back "
                "on hardcoded constants",
                exc.__class__.__name__,
                exc,
            )
            _TOML_DEFAULTS_CACHE = {}
    return _TOML_DEFAULTS_CACHE


def _reset_toml_defaults_cache_for_tests() -> None:
    """Reset the cached TOML defaults (test-only helper)."""
    global _TOML_DEFAULTS_CACHE
    with _TOML_DEFAULTS_LOCK:
        _TOML_DEFAULTS_CACHE = None


# ---------------------------------------------------------------------------
# Env helpers
# ---------------------------------------------------------------------------


def _env_bool(key: str, default: bool) -> bool:
    """Parse ``key`` as a boolean (truthy if value in ``{1, true, yes, on}``)."""
    raw = os.getenv(key)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(key: str, default: int) -> int:
    raw = os.getenv(key)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        logger.warning(
            "Invalid integer for env var %s=%r, falling back on default %d",
            key,
            raw,
            default,
        )
        return default


def _env_float(key: str, default: float) -> float:
    raw = os.getenv(key)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        logger.warning(
            "Invalid float for env var %s=%r, falling back on default %s",
            key,
            raw,
            default,
        )
        return default


# Default audit set kept narrow (MVP §2.5 compatibility): only GLiNER
# entities are sent to the judge unless the caller opts in via env / TOML /
# constructor (see :class:`LLMJudgeValidator`).
_DEFAULT_AUDIT_SOURCES: Tuple[DetectorSource, ...] = (DetectorSource.GLINER,)


def _parse_audit_sources(raw: Optional[str]) -> Set[DetectorSource]:
    """Parse a CSV string into a set of :class:`DetectorSource`.

    The grammar is intentionally lenient (env var + TOML are operator-facing
    knobs): tokens are split on commas, whitespace is stripped, and casing
    is normalised to upper-case. Unknown tokens are logged at WARNING and
    skipped (so a typo does not silently disable the judge).

    Args:
        raw: Comma-separated string (e.g. ``"GLINER,OPENMED"``). ``None``
            or empty falls back on :data:`_DEFAULT_AUDIT_SOURCES`.

    Returns:
        Non-empty set of audited :class:`DetectorSource`. Falls back on
        ``{GLINER}`` whenever the parse yields nothing usable (so the
        validator never silently degrades into a passthrough).
    """
    if raw is None or not raw.strip():
        return set(_DEFAULT_AUDIT_SOURCES)

    resolved: Set[DetectorSource] = set()
    for token in raw.split(","):
        cleaned = token.strip().upper()
        if not cleaned:
            continue
        try:
            resolved.add(DetectorSource(cleaned))
        except ValueError:
            logger.warning(
                "Unknown DetectorSource %r in LLM_JUDGE_AUDIT_SOURCES; skipped",
                cleaned,
            )
    if not resolved:
        logger.warning(
            "LLM_JUDGE_AUDIT_SOURCES=%r yielded no valid source; falling "
            "back on default {GLINER}",
            raw,
        )
        return set(_DEFAULT_AUDIT_SOURCES)
    return resolved


# ---------------------------------------------------------------------------
# Module-level functions (testable in isolation)
# ---------------------------------------------------------------------------


def _resolve_model_id(
    base_url: str,
    preferred: str = _DEFAULT_PREFERRED_MODEL,
    timeout: float = 5.0,
) -> str:
    """Call ``GET /v1/models`` and return the model id to use.

    Resolution order (spec section 2.4):

    1. Exact match on ``preferred``.
    2. Fuzzy match containing both ``qwen3.6`` and ``a3b`` and excluding any
       fine-tune marker (see :data:`_FINETUNE_BLACKLIST`).
    3. ``RuntimeError`` if nothing matches.

    Args:
        base_url: LM Studio base URL (with the ``/v1`` suffix).
        preferred: Canonical model id; tried first via exact match.
        timeout: Per-call HTTP timeout (seconds).

    Returns:
        The exact id to inject into the chat completion payload.

    Raises:
        RuntimeError: When no candidate is found on the server.
    """
    url = f"{base_url.rstrip('/')}/models"
    resp = httpx.get(url, timeout=timeout)
    resp.raise_for_status()
    ids = [m["id"] for m in resp.json().get("data", [])]

    if preferred in ids:
        return preferred

    candidates: List[str] = []
    for model_id in ids:
        lower = model_id.lower()
        if not all(token in lower for token in _FUZZY_MATCH_TOKENS):
            continue
        if any(b in lower for b in _FINETUNE_BLACKLIST):
            continue
        candidates.append(model_id)

    if not candidates:
        raise RuntimeError(
            f"No Qwen 3.6 A3B model exposed by LM Studio at {base_url}. "
            f"Available: {ids!r}"
        )
    return candidates[0]


def _extract_json_payload(response_json: dict) -> dict:
    """Extract the JSON verdict from a LM Studio + Qwen 3.6 response.

    Qwen 3.6 is a thinking model. With ``response_format: json_schema strict``
    LM Studio places the well-formed JSON inside
    ``message.reasoning_content``, leaving ``message.content`` empty
    (verified empirically -- spec section 1.6 test 3). This parser reads
    ``reasoning_content`` in priority and falls back on ``content``, with a
    defense-in-depth pass to strip residual ``<think>`` blocks and markdown
    fences.

    Args:
        response_json: Decoded HTTP response body from ``/chat/completions``.

    Returns:
        The parsed JSON payload (a dict).

    Raises:
        ValueError: If both ``reasoning_content`` and ``content`` are empty.
        json.JSONDecodeError: If the extracted text is not valid JSON.
    """
    msg = response_json["choices"][0]["message"]
    raw = (msg.get("reasoning_content") or msg.get("content") or "").strip()
    if not raw:
        raise ValueError(
            "Empty reasoning_content and content in LM Studio response"
        )

    # Defense-in-depth: in case LM Studio ever surfaces a non-strict path,
    # strip residual <think> blocks and markdown fences before parsing.
    raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.DOTALL).strip()
    raw = re.sub(r"^```(?:json)?\s*", "", raw)
    if raw.endswith("```"):
        raw = raw[:-3].rstrip()
    if not raw.startswith("{"):
        match = re.search(r"\{.*\}", raw, flags=re.DOTALL)
        if match:
            raw = match.group(0)

    return json.loads(raw)


def _log_inference_metrics(response_json: dict, request_id: str) -> None:
    """Log inference cost metrics (Qwen 3.6 surfaces reasoning tokens).

    The log tag ``[LLM-JUDGE] inference`` is parsed by the throughput
    reporter (spec section 3.3).
    """
    usage = response_json.get("usage", {})
    details = usage.get("completion_tokens_details", {})
    logger.info(
        "[LLM-JUDGE] inference request_id=%s prompt_tokens=%d "
        "completion_tokens=%d reasoning_tokens=%d total_tokens=%d",
        request_id,
        usage.get("prompt_tokens", 0),
        usage.get("completion_tokens", 0),
        details.get("reasoning_tokens", 0),
        usage.get("total_tokens", 0),
    )


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


_INSTANCE: Optional["LLMJudgeValidator"] = None
_INSTANCE_LOCK = threading.Lock()


def get_instance() -> "LLMJudgeValidator":
    """Return the process-wide :class:`LLMJudgeValidator` singleton.

    The validator is built lazily (no HTTP I/O at import time). The atexit
    shutdown hook is wired by the constructor so the executor is cleaned up
    even if the singleton is built late in the process lifetime.
    """
    global _INSTANCE
    if _INSTANCE is None:
        with _INSTANCE_LOCK:
            if _INSTANCE is None:
                _INSTANCE = LLMJudgeValidator()
    return _INSTANCE


def _reset_singleton_for_tests() -> None:
    """Reset the module-level singleton (test-only helper)."""
    global _INSTANCE
    with _INSTANCE_LOCK:
        if _INSTANCE is not None:
            _INSTANCE.shutdown()
        _INSTANCE = None


# ---------------------------------------------------------------------------
# Validator
# ---------------------------------------------------------------------------


class LLMJudgeValidator(PIIPostFilterProtocol):
    """LLM-as-Judge post-filter targeting Qwen 3.6 via LM Studio.

    Behaviour summary:

    - The set of audited detectors is configurable via
      :attr:`audit_sources` (post-MVP extension, see spec §10). The MVP
      default is ``{DetectorSource.GLINER}`` so deployments that ship
      without explicit configuration keep the original §2.5 scope. To
      audit other detectors (OpenMed FPs, Regex over-matches, etc.), set
      ``audit_sources`` either via the constructor, the
      ``LLM_JUDGE_AUDIT_SOURCES`` env var, or the
      ``[llm_judge].audit_sources`` TOML field.
    - Each finding is converted to a single ``/chat/completions`` call
      using the system + user prompts from
      :mod:`prompt_templates`. ``response_format`` is the OpenAI-compatible
      ``json_schema strict`` shape.
    - The verdict is validated by :class:`PiiVerdict` (Pydantic) and acted
      upon:

      - ``FALSE_POSITIVE`` -> entity discarded
      - ``TRUE_POSITIVE``  -> entity kept
      - ``UNSURE``         -> entity kept (recall-preserving policy)

    - On any error (timeout, HTTP, JSON invalid) the entity is kept when
      :attr:`fail_open` is ``True`` (default).

    The default values mirror the spec section 2.6 environment variables
    so the validator behaves identically whether configured via env or
    constructor.
    """

    SOURCE_NAME = "llm-judge-qwen-3.6"

    def __init__(
        self,
        base_url: Optional[str] = None,
        preferred_model: Optional[str] = None,
        timeout_seconds: Optional[float] = None,
        max_batch_size: Optional[int] = None,
        max_workers: Optional[int] = None,
        fail_open: Optional[bool] = None,
        backend: Optional[str] = None,
        generation: Optional[LlmValidatorConfig] = None,
        http_client: Optional[httpx.Client] = None,
        audit_sources: Optional[Iterable[DetectorSource]] = None,
        system_prompt: Optional[str] = None,
        per_type_prompts: Optional[bool] = None,
    ) -> None:
        """Build the validator.

        Args:
            base_url: LM Studio base URL ending in ``/v1``. Defaults to
                ``LLM_JUDGE_BASE_URL`` env var, then to
                ``[llm_judge].base_url`` in
                ``config/detection-settings.toml`` (single source of
                truth), then to :data:`_DEFAULT_BASE_URL`.
            preferred_model: Canonical model id to resolve first against
                ``GET /v1/models`` (env: ``LLM_JUDGE_PREFERRED_MODEL``).
            timeout_seconds: Per-call HTTP timeout in seconds (env:
                ``LLM_JUDGE_TIMEOUT_SECONDS``).
            max_batch_size: Reserved for future batching; currently 1.
            max_workers: Parallel HTTP calls (env: ``LLM_JUDGE_MAX_WORKERS``).
            fail_open: When ``True`` (default), any error keeps the entity.
            backend: ``remote`` (default) or ``local`` (no-op for MVP).
            generation: Sampling / budget knobs bundled in
                :class:`LlmValidatorConfig` (``context_window``,
                ``max_tokens``, ``temperature``, ``top_p``). When ``None``
                (default), the dataclass defaults are used.
            http_client: Optional pre-built :class:`httpx.Client` (useful
                in tests).
            audit_sources: Iterable of :class:`DetectorSource` to audit
                with the LLM judge. When ``None`` (default), the env var
                ``LLM_JUDGE_AUDIT_SOURCES`` is consulted, then the value
                falls back on ``{GLINER}`` (MVP §2.5 backwards
                compatibility). Entities whose source is **not** in this
                set bypass the judge and are returned unchanged.
            system_prompt: System prompt injected as the ``system`` message
                of every ``/chat/completions`` call. When ``None`` (default),
                the canonical :data:`prompt_templates.SYSTEM_PROMPT` is used,
                so production behaviour is unchanged. Eval harnesses pass a
                variant from :data:`prompt_templates.PROMPT_VARIANTS` here to
                A/B-test prompt formulations without mutating the module
                constant.
            per_type_prompts: Enable v7 per-``pii_type`` system prompt
                routing. Defaults to env ``LLM_JUDGE_PER_TYPE_PROMPTS``, then
                ``[llm_judge].per_type_prompts``, then ``False``.
        """
        # Precedence: explicit constructor arg > env var > TOML (single
        # source of truth) > hardcoded final fallback.
        toml_defaults = _load_llm_judge_toml_defaults()
        self.base_url = (
            base_url
            or os.getenv("LLM_JUDGE_BASE_URL")
            or toml_defaults.get("base_url")
            or _DEFAULT_BASE_URL
        ).rstrip("/")
        self.preferred_model = (
            preferred_model
            or os.getenv("LLM_JUDGE_PREFERRED_MODEL")
            or toml_defaults.get("preferred_model")
            or _DEFAULT_PREFERRED_MODEL
        )
        self.timeout_seconds = (
            timeout_seconds
            if timeout_seconds is not None
            else _env_float(
                "LLM_JUDGE_TIMEOUT_SECONDS",
                float(toml_defaults.get("timeout_seconds", 120.0)),
            )
        )
        self.max_batch_size = (
            max_batch_size
            if max_batch_size is not None
            else _env_int(
                "LLM_JUDGE_MAX_BATCH_SIZE",
                int(toml_defaults.get("max_batch_size", 1)),
            )
        )
        self.max_workers = (
            max_workers
            if max_workers is not None
            else _env_int(
                "LLM_JUDGE_MAX_WORKERS",
                int(toml_defaults.get("max_workers", 4)),
            )
        )
        self.fail_open = (
            fail_open
            if fail_open is not None
            else _env_bool(
                "LLM_JUDGE_FAIL_OPEN",
                bool(toml_defaults.get("fail_open", True)),
            )
        )
        self.backend = (
            backend or os.getenv("LLM_JUDGE_BACKEND") or BACKEND_REMOTE
        ).strip().lower()
        # audit_sources precedence: constructor > env > {GLINER} default.
        # Resolution lives in :func:`_parse_audit_sources` so the parsing
        # rules (CSV, whitespace, casing, unknown tokens) stay testable in
        # isolation from the constructor.
        if audit_sources is None:
            self.audit_sources: Set[DetectorSource] = _parse_audit_sources(
                os.getenv("LLM_JUDGE_AUDIT_SOURCES")
            )
        else:
            self.audit_sources = set(audit_sources) or set(
                _DEFAULT_AUDIT_SOURCES
            )
        # System prompt precedence: explicit constructor arg > module default.
        # Defaulting to SYSTEM_PROMPT keeps prod identical; the override exists
        # so the prompt-comparison eval can inject PROMPT_VARIANTS entries
        # without ever mutating the canonical constant.
        self.system_prompt = system_prompt or SYSTEM_PROMPT
        self.per_type_prompts = (
            per_type_prompts
            if per_type_prompts is not None
            else _env_bool(
                "LLM_JUDGE_PER_TYPE_PROMPTS",
                bool(toml_defaults.get("per_type_prompts", False)),
            )
        )
        self._per_type_name: Optional[str] = None
        self._per_type_builder: Optional[Callable[[str], str]] = None
        if self.per_type_prompts:
            self._per_type_name, self._per_type_builder = load_per_type_prompts()
        generation = generation or LlmValidatorConfig()
        self.context_window = generation.context_window
        self.max_tokens = generation.max_tokens
        self.temperature = generation.temperature
        self.top_p = generation.top_p

        self._client: Optional[httpx.Client] = http_client
        self._owns_client = http_client is None
        self._executor: Optional[ThreadPoolExecutor] = ThreadPoolExecutor(
            max_workers=max(1, self.max_workers),
            thread_name_prefix="llm-judge",
        )
        self._resolved_model_id: Optional[str] = None
        self._resolve_lock = threading.Lock()
        self._shutdown_called = False

        atexit.register(self.shutdown)

        if self.backend == BACKEND_LOCAL:
            logger.warning(
                "[LLM-JUDGE] local backend out of scope for MVP "
                "(use 'remote' or disable judge). Filter is a no-op."
            )

    # -- PIIPostFilterProtocol -----------------------------------------------

    @property
    def name(self) -> str:
        """Stable identifier for the filter, used in logs and metrics."""
        return self.SOURCE_NAME

    def filter(
        self,
        text: str,
        entities: List[PIIEntity],
        audit_sources: Optional[Set[DetectorSource]] = None,
    ) -> List[PIIEntity]:
        """Filter entities whose source is in the audited set.

        Detectors not in the audited set bypass the LLM and are kept as-is
        (recall-preserving passthrough). Audited entities are submitted in
        parallel via the thread-pool executor.

        ``audit_sources`` overrides the instance default for this call only
        (per-request routing driven by the per-detector judge flags). When
        ``None`` the instance :attr:`audit_sources` is used (``{GLINER}`` by
        default for MVP §2.5 backwards compatibility).
        """
        kept, _rejections = self.filter_with_verdicts(
            text, entities, audit_sources=audit_sources
        )
        return kept

    def filter_with_verdicts(
        self,
        text: str,
        entities: List[PIIEntity],
        audit_sources: Optional[Set[DetectorSource]] = None,
    ) -> Tuple[List[PIIEntity], List[Tuple[PIIEntity, PiiVerdict]]]:
        """Filter entities and also return the rejected ones with verdicts.

        Same semantics as :meth:`filter`, but exposes the discarded
        entities so callers can surface the judge's false-positive
        rejections (e.g. in the gRPC response for measurement purposes).

        ``audit_sources`` overrides the instance default for this call only;
        see :meth:`filter`.

        Returns:
            ``(kept, rejections)`` where ``kept`` preserves the original
            ordering (audited survivors + non-audited passthrough) and
            ``rejections`` lists each discarded entity with the
            ``FALSE_POSITIVE`` verdict that motivated the rejection.
            Fail-open entities (verdict ``None``) are always kept and
            never appear in ``rejections``.
        """
        if self.backend == BACKEND_LOCAL:
            # Local backend is intentionally a no-op for the MVP: nothing is
            # judged, so every kept entity is NOT_AUDITED.
            return self._passthrough_not_audited(entities)
        if not entities:
            return [], []

        audited_entities = [
            entity
            for entity in entities
            if self._is_audited(entity, audit_sources)
        ]
        if not audited_entities:
            return self._passthrough_not_audited(entities)

        verdicts = self._judge_batch(text, audited_entities)
        verdict_by_id = {
            id(entity): verdict
            for entity, verdict in zip(audited_entities, verdicts)
        }
        return self._partition_judged(entities, audit_sources, verdict_by_id)

    def _passthrough_not_audited(
        self, entities: List[PIIEntity]
    ) -> Tuple[List[PIIEntity], List[Tuple[PIIEntity, PiiVerdict]]]:
        """Tag every entity NOT_AUDITED and keep them all (no rejection)."""
        passthrough = list(entities)
        for entity in passthrough:
            self._tag_judge_status(entity, JudgeStatus.NOT_AUDITED)
        return passthrough, []

    def _partition_judged(
        self,
        entities: List[PIIEntity],
        audit_sources: Optional[Set[DetectorSource]],
        verdict_by_id: Dict[int, Optional[PiiVerdict]],
    ) -> Tuple[List[PIIEntity], List[Tuple[PIIEntity, PiiVerdict]]]:
        """Split entities into kept / rejected, preserving original ordering.

        Non-audited entities pass through as-is; audited ones are kept or
        rejected per their verdict. Each kept entity is tagged with the judge
        status so callers can distinguish a validated finding from one kept
        unjudged (not audited) or kept by the fail-open policy after a failed
        judge call.
        """
        kept: List[PIIEntity] = []
        rejections: List[Tuple[PIIEntity, PiiVerdict]] = []
        for entity in entities:
            if not self._is_audited(entity, audit_sources):
                self._tag_judge_status(entity, JudgeStatus.NOT_AUDITED)
                kept.append(entity)
                continue
            verdict = verdict_by_id.get(id(entity))
            if self._should_keep(verdict):
                self._tag_judge_status(entity, self._status_for_kept(verdict))
                kept.append(entity)
            else:
                rejections.append((entity, verdict))
        return kept, rejections

    @staticmethod
    def _tag_judge_status(entity: PIIEntity, status: JudgeStatus) -> None:
        """Attach the judge status to a kept entity (best-effort).

        Uses the same dynamic-attribute convention detectors use for
        ``source``; never raises so a non-attributable entity can't break
        the filter.
        """
        try:
            entity.judge_status = status
        except (AttributeError, TypeError):  # pragma: no cover - defensive
            pass

    @staticmethod
    def _status_for_kept(verdict: Optional[PiiVerdict]) -> JudgeStatus:
        """Map a judge verdict to the status of an entity that was kept.

        ``None`` means the judge call failed and the entity survived by the
        fail-open policy. A ``TRUE_POSITIVE`` is a validated finding;
        anything else kept (``UNSURE``) is the recall-preserving case.
        ``FALSE_POSITIVE`` never reaches this method (those are rejected).
        """
        if verdict is None:
            return JudgeStatus.FAIL_OPEN_KEPT
        if verdict.verdict == "TRUE_POSITIVE":
            return JudgeStatus.VALIDATED_TRUE_POSITIVE
        return JudgeStatus.VALIDATED_UNSURE

    # -- Internals -----------------------------------------------------------

    def _is_audited(
        self,
        entity: PIIEntity,
        audit_sources: Optional[Set[DetectorSource]] = None,
    ) -> bool:
        """Return ``True`` if ``entity`` should be sent to the judge.

        Accepts both :class:`DetectorSource` enum values and plain strings
        (legacy paths attach the source as a string in some detectors).
        Unknown or missing sources are treated as non-audited
        (passthrough).

        ``audit_sources`` overrides the instance default for this check only
        (per-request routing); ``None`` falls back on :attr:`audit_sources`.
        """
        sources = audit_sources if audit_sources is not None else self.audit_sources
        source = getattr(entity, "source", None)
        if isinstance(source, DetectorSource):
            return source in sources
        if isinstance(source, str):
            try:
                normalised = DetectorSource(source.upper())
            except ValueError:
                return False
            return normalised in sources
        return False

    @staticmethod
    def _should_keep(verdict: Optional[PiiVerdict]) -> bool:
        """Implement the MVP decision rule (spec section 2.5).

        ``FALSE_POSITIVE`` -> discard, anything else (including ``None``
        when fail-open) -> keep.
        """
        if verdict is None:
            return True
        return verdict.verdict != "FALSE_POSITIVE"

    def _judge_batch(
        self, text: str, entities: List[PIIEntity]
    ) -> List[Optional[PiiVerdict]]:
        """Submit one judge call per entity using the executor."""
        if self._executor is None:  # pragma: no cover - defensive
            raise RuntimeError("LLMJudgeValidator already shut down")

        futures = []
        for entity in entities:
            request_id = uuid.uuid4().hex[:12]
            futures.append(
                self._executor.submit(
                    self._judge_one, text, entity, request_id
                )
            )

        results: List[Optional[PiiVerdict]] = []
        for future in futures:
            try:
                results.append(future.result(timeout=self.timeout_seconds))
            except Exception:
                logger.warning(
                    "[LLM-JUDGE] judge call failed; %s entity",
                    "keeping" if self.fail_open else "discarding",
                    exc_info=True,
                )
                results.append(
                    None if self.fail_open else self._reject_verdict()
                )
        return results

    @staticmethod
    def _reject_verdict() -> PiiVerdict:
        """Build a synthetic FALSE_POSITIVE verdict (used when fail-closed)."""
        return PiiVerdict(
            verdict="FALSE_POSITIVE",
            confidence=0.0,
            reason="forced rejection (fail_open=false)",
        )

    def _judge_one(
        self, text: str, entity: PIIEntity, request_id: str
    ) -> Optional[PiiVerdict]:
        """Build the prompt, hit LM Studio, and validate the verdict.

        Returns ``None`` on any error when :attr:`fail_open` is ``True``;
        otherwise re-raises so the caller can synthesise a rejection.
        """
        try:
            user_prompt = build_user_prompt(
                entity, text, context_window=self.context_window
            )
            system_prompt = None
            if self.per_type_prompts and self._per_type_builder is not None:
                pii_type = getattr(entity, "pii_type", None) or getattr(
                    entity, "type", "UNKNOWN"
                )
                system_prompt = self._per_type_builder(pii_type)
            response_json = self._invoke_remote(
                user_prompt, request_id, system_prompt=system_prompt
            )
            _log_inference_metrics(response_json, request_id)
            payload = _extract_json_payload(response_json)
            return PiiVerdict.model_validate(payload)
        except (
            httpx.HTTPError,
            ValueError,
        ) as exc:
            logger.warning(
                "[LLM-JUDGE] request_id=%s failed (%s: %s); fail_open=%s",
                request_id,
                exc.__class__.__name__,
                exc,
                self.fail_open,
            )
            if self.fail_open:
                return None
            raise

    def _resolve_model_id_lazy(self) -> str:
        """Resolve the LM Studio model id once and cache it for reuse."""
        if self._resolved_model_id is not None:
            return self._resolved_model_id
        with self._resolve_lock:
            if self._resolved_model_id is None:
                self._resolved_model_id = _resolve_model_id(
                    self.base_url, self.preferred_model
                )
                logger.info(
                    "[LLM-JUDGE] resolved model id=%s (preferred=%s)",
                    self._resolved_model_id,
                    self.preferred_model,
                )
        return self._resolved_model_id

    def _get_client(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(timeout=self.timeout_seconds)
        return self._client

    def _build_payload(
        self, user_prompt: str, system_prompt: Optional[str] = None
    ) -> Dict[str, Any]:
        return {
            "model": self._resolve_model_id_lazy(),
            "messages": [
                {"role": "system", "content": system_prompt or self.system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self.temperature,
            "top_p": self.top_p,
            "max_tokens": self.max_tokens,
            "stream": False,
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": "pii_verdict",
                    "strict": True,
                    "schema": VERDICT_SCHEMA,
                },
            },
            # Best-effort -- LM Studio ignores this kwarg today (spec
            # section 1.5) but it is forward-compatible.
            "chat_template_kwargs": {"enable_thinking": False},
        }

    def _invoke_remote(
        self, user_prompt: str, request_id: str, system_prompt: Optional[str] = None
    ) -> dict:
        """POST ``/chat/completions`` and return the decoded JSON response."""
        client = self._get_client()
        url = f"{self.base_url}/chat/completions"
        payload = self._build_payload(user_prompt, system_prompt=system_prompt)
        start = time.monotonic()
        resp = client.post(url, json=payload, timeout=self.timeout_seconds)
        elapsed = time.monotonic() - start
        resp.raise_for_status()
        data = resp.json()
        logger.debug(
            "[LLM-JUDGE] request_id=%s completed in %.3fs (status=%d)",
            request_id,
            elapsed,
            resp.status_code,
        )
        return data

    # -- Lifecycle -----------------------------------------------------------

    def shutdown(self) -> None:
        """Shutdown the executor and close the HTTP client. Idempotent."""
        if self._shutdown_called:
            return
        self._shutdown_called = True
        if self._executor is not None:
            self._executor.shutdown(wait=False)
            self._executor = None
        if self._client is not None and self._owns_client:
            try:
                self._client.close()
            except Exception:  # pragma: no cover - defensive
                logger.debug("LLM judge HTTP client close failed", exc_info=True)
            self._client = None


__all__ = [
    "BACKEND_LOCAL",
    "BACKEND_REMOTE",
    "LLMJudgeValidator",
    "LlmValidatorConfig",
    "_extract_json_payload",
    "_log_inference_metrics",
    "_parse_audit_sources",
    "_resolve_model_id",
    "get_instance",
]
