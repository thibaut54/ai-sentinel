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
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

import httpx
from pydantic import ValidationError

from pii_detector.domain.entity.detector_source import DetectorSource
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

# Preferred model id (exact match priority) and fuzzy match tokens.
_DEFAULT_PREFERRED_MODEL = "qwen/qwen3.6-35b-a3b"
_FUZZY_MATCH_TOKENS = ("qwen3.6", "a3b")

# Default LM Studio endpoint (validated empirically on 2026-05-25).
_DEFAULT_BASE_URL = "http://172.22.22.63:1234/v1"


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
    raw = re.sub(r"\s*```$", "", raw)
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

    NAME = "llm-judge-qwen-3.6"

    def __init__(
        self,
        base_url: Optional[str] = None,
        preferred_model: Optional[str] = None,
        timeout_seconds: Optional[float] = None,
        max_batch_size: Optional[int] = None,
        max_workers: Optional[int] = None,
        fail_open: Optional[bool] = None,
        backend: Optional[str] = None,
        context_window: int = DEFAULT_CONTEXT_WINDOW,
        max_tokens: int = 2048,
        temperature: float = 0.2,
        top_p: float = 1.0,
        http_client: Optional[httpx.Client] = None,
        audit_sources: Optional[Iterable[DetectorSource]] = None,
    ) -> None:
        """Build the validator.

        Args:
            base_url: LM Studio base URL ending in ``/v1``. Defaults to
                ``LLM_JUDGE_BASE_URL`` env var or :data:`_DEFAULT_BASE_URL`.
            preferred_model: Canonical model id to resolve first against
                ``GET /v1/models`` (env: ``LLM_JUDGE_PREFERRED_MODEL``).
            timeout_seconds: Per-call HTTP timeout in seconds (env:
                ``LLM_JUDGE_TIMEOUT_SECONDS``).
            max_batch_size: Reserved for future batching; currently 1.
            max_workers: Parallel HTTP calls (env: ``LLM_JUDGE_MAX_WORKERS``).
            fail_open: When ``True`` (default), any error keeps the entity.
            backend: ``remote`` (default) or ``local`` (no-op for MVP).
            context_window: Chars before / after the entity sent in the
                prompt.
            max_tokens: Generation budget (absorbs Qwen 3.6 reasoning).
            temperature: Sampling temperature.
            top_p: Nucleus sampling threshold.
            http_client: Optional pre-built :class:`httpx.Client` (useful
                in tests).
            audit_sources: Iterable of :class:`DetectorSource` to audit
                with the LLM judge. When ``None`` (default), the env var
                ``LLM_JUDGE_AUDIT_SOURCES`` is consulted, then the value
                falls back on ``{GLINER}`` (MVP §2.5 backwards
                compatibility). Entities whose source is **not** in this
                set bypass the judge and are returned unchanged.
        """
        self.base_url = (
            base_url
            or os.getenv("LLM_JUDGE_BASE_URL")
            or _DEFAULT_BASE_URL
        ).rstrip("/")
        self.preferred_model = (
            preferred_model
            or os.getenv("LLM_JUDGE_PREFERRED_MODEL")
            or _DEFAULT_PREFERRED_MODEL
        )
        self.timeout_seconds = (
            timeout_seconds
            if timeout_seconds is not None
            else _env_float("LLM_JUDGE_TIMEOUT_SECONDS", 120.0)
        )
        self.max_batch_size = (
            max_batch_size
            if max_batch_size is not None
            else _env_int("LLM_JUDGE_MAX_BATCH_SIZE", 1)
        )
        self.max_workers = (
            max_workers
            if max_workers is not None
            else _env_int("LLM_JUDGE_MAX_WORKERS", 4)
        )
        self.fail_open = (
            fail_open
            if fail_open is not None
            else _env_bool("LLM_JUDGE_FAIL_OPEN", True)
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
        self.context_window = context_window
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.top_p = top_p

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
        return self.NAME

    def filter(
        self, text: str, entities: List[PIIEntity]
    ) -> List[PIIEntity]:
        """Filter entities whose source is in :attr:`audit_sources`.

        Detectors not listed in :attr:`audit_sources` bypass the LLM and
        are kept as-is (recall-preserving passthrough). Audited entities
        are submitted in parallel via the thread-pool executor.

        Defaults to ``{GLINER}`` for MVP §2.5 backwards compatibility;
        extend via :attr:`audit_sources` to also audit OpenMed / Regex /
        Presidio outputs (spec §10).
        """
        if self.backend == BACKEND_LOCAL:
            # Local backend is intentionally a no-op for the MVP.
            return list(entities)
        if not entities:
            return []

        audited_entities: List[PIIEntity] = []
        for entity in entities:
            if self._is_audited(entity):
                audited_entities.append(entity)

        if not audited_entities:
            return list(entities)

        verdicts = self._judge_batch(text, audited_entities)
        kept_ids = {
            id(entity)
            for entity, verdict in zip(audited_entities, verdicts)
            if self._should_keep(verdict)
        }

        # Preserve the original ordering while filtering out the rejected
        # audited entities; non-audited entities are passed through as-is.
        result: List[PIIEntity] = []
        for entity in entities:
            if self._is_audited(entity):
                if id(entity) in kept_ids:
                    result.append(entity)
            else:
                result.append(entity)
        return result

    # -- Internals -----------------------------------------------------------

    def _is_audited(self, entity: PIIEntity) -> bool:
        """Return ``True`` if ``entity`` should be sent to the judge.

        Accepts both :class:`DetectorSource` enum values and plain strings
        (legacy paths attach the source as a string in some detectors).
        Unknown or missing sources are treated as non-audited
        (passthrough).
        """
        source = getattr(entity, "source", None)
        if isinstance(source, DetectorSource):
            return source in self.audit_sources
        if isinstance(source, str):
            try:
                normalised = DetectorSource(source.upper())
            except ValueError:
                return False
            return normalised in self.audit_sources
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
            response_json = self._invoke_remote(user_prompt, request_id)
            _log_inference_metrics(response_json, request_id)
            payload = _extract_json_payload(response_json)
            return PiiVerdict.model_validate(payload)
        except (
            httpx.TimeoutException,
            httpx.HTTPError,
            ValueError,
            json.JSONDecodeError,
            ValidationError,
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

    def _build_payload(self, user_prompt: str) -> Dict[str, Any]:
        return {
            "model": self._resolve_model_id_lazy(),
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
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

    def _invoke_remote(self, user_prompt: str, request_id: str) -> dict:
        """POST ``/chat/completions`` and return the decoded JSON response."""
        client = self._get_client()
        url = f"{self.base_url}/chat/completions"
        payload = self._build_payload(user_prompt)
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
    "_extract_json_payload",
    "_log_inference_metrics",
    "_parse_audit_sources",
    "_resolve_model_id",
    "get_instance",
]
