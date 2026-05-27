"""
Prompt templates and verdict schema for the Qwen 3.6 LLM-as-Judge.

The prompt text is externalized to ``config/llm-judge-prompts.toml`` so it can
be edited and A/B-compared without touching code (spec section 2.4). The strict
JSON output rules required by the LM Studio runtime are part of that prompt.

Artefacts exported:

- :data:`PROMPT_VARIANTS`      ``{name: system_prompt}`` for every variant
  declared in the TOML (consumed by the prompt-comparison harness).
- :data:`ACTIVE_VARIANT`       name of the variant selected as production
  default via ``active_variant`` in the TOML.
- :data:`SYSTEM_PROMPT`        the active variant -- French system prompt with
  ``/no_think``, Swiss-context few-shots and the strict JSON output rules.
- :func:`build_user_prompt`    Builds the per-finding user prompt with a
  300-char context window before/after the entity.
- :class:`PiiVerdict`          Pydantic model used to validate the JSON
  payload returned by the model.
- :data:`VERDICT_SCHEMA`       OpenAI-compatible JSON Schema for the
  ``response_format: {type: "json_schema", strict: true}`` payload.

Empirical references:
- Spec section 1.6: ``response_format: json_schema strict`` is the only
  reliable strategy on Qwen 3.6 with LM Studio; without it the JSON can
  be truncated inside ``reasoning_content``.
- Spec section 2.4: the prompts, now externalized to
  ``config/llm-judge-prompts.toml``.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, Literal, Tuple

from pydantic import BaseModel, Field, field_validator

try:
    import tomllib  # type: ignore[import-not-found]  # Python 3.11+
except ImportError:  # pragma: no cover - py3.9/3.10 fallback
    import tomli as tomllib  # type: ignore[import-not-found,no-redef]


# ---------------------------------------------------------------------------
# Prompts (externalized to config/llm-judge-prompts.toml)
# ---------------------------------------------------------------------------


# The prompt text lives in config/llm-judge-prompts.toml so it can be edited and
# A/B-compared without touching code. This module loads it at import time and
# exposes:
#   - PROMPT_VARIANTS : {name: system_prompt} for every [variants.*] block
#   - ACTIVE_VARIANT  : the name selected by `active_variant`
#   - SYSTEM_PROMPT   : PROMPT_VARIANTS[ACTIVE_VARIANT] -- the production default
# The prompt-comparison harness iterates PROMPT_VARIANTS; LLMJudgeValidator uses
# SYSTEM_PROMPT as its default system_prompt.
_PROMPTS_TOML_PATH = (
    Path(__file__).resolve().parents[3] / "config" / "llm-judge-prompts.toml"
)


def _load_prompt_variants() -> Tuple[Dict[str, str], str]:
    """Load prompt variants and the active variant name from the TOML file.

    The prompt is essential to the judge, so a missing or malformed file is a
    hard error rather than a silent fallback that could ship a wrong prompt.

    Returns:
        ``(variants, active_variant)`` -- ``variants`` maps each variant name
        to its ``system_prompt`` string.

    Raises:
        RuntimeError: if the file is missing, unparseable, declares no
            variants, or names an ``active_variant`` that is not declared.
    """
    try:
        with open(_PROMPTS_TOML_PATH, "rb") as f:
            data = tomllib.load(f)
    except FileNotFoundError as exc:
        raise RuntimeError(
            f"LLM-judge prompts file not found: {_PROMPTS_TOML_PATH} "
            "(it carries the judge system prompts)."
        ) from exc
    except tomllib.TOMLDecodeError as exc:
        raise RuntimeError(
            f"Could not parse {_PROMPTS_TOML_PATH}: {exc}"
        ) from exc

    raw_variants = data.get("variants")
    if not isinstance(raw_variants, dict) or not raw_variants:
        raise RuntimeError(
            f"{_PROMPTS_TOML_PATH} declares no [variants.*] block."
        )

    variants: Dict[str, str] = {}
    for name, block in raw_variants.items():
        prompt = block.get("system_prompt") if isinstance(block, dict) else None
        if not isinstance(prompt, str) or not prompt.strip():
            raise RuntimeError(
                f"{_PROMPTS_TOML_PATH}: variant {name!r} has no non-empty "
                "'system_prompt'."
            )
        variants[name] = prompt

    active = data.get("active_variant")
    if active not in variants:
        raise RuntimeError(
            f"{_PROMPTS_TOML_PATH}: active_variant={active!r} is not one of "
            f"the declared variants {sorted(variants)}."
        )
    return variants, active


# Registry consumed by the prompt-comparison eval (one entry per variant).
PROMPT_VARIANTS, ACTIVE_VARIANT = _load_prompt_variants()

# Production default: the active variant. LLMJudgeValidator falls back on this
# when no explicit system_prompt is passed, so promoting a new winner is a
# one-line `active_variant` change in the TOML.
SYSTEM_PROMPT = PROMPT_VARIANTS[ACTIVE_VARIANT]

# Back-compat alias kept for imports/tests; identical to SYSTEM_PROMPT whenever
# v2_context_aware is the active variant.
SYSTEM_PROMPT_CONTEXT_AWARE = PROMPT_VARIANTS.get("v2_context_aware", SYSTEM_PROMPT)


# Default context window when neither the caller nor the TOML overrides it.
DEFAULT_CONTEXT_WINDOW = 300


_USER_PROMPT_TEMPLATE = (
    "Finding a juger :\n"
    "  pii_type          = {pii_type}\n"
    "  type_label        = {type_label}\n"
    '  value             = "{value}"\n'
    '  value_digits_only = "{value_digits_only}"\n'
    '  context           = "...{ctx_before} >>>{value}<<< {ctx_after}..."'
)

# Separators stripped from ``value`` to expose the canonical sequence to the
# judge (spaces, dashes, dots, slashes). Lets the model count digits without
# reconstructing the value itself under Q4_K_M noise.
_SEPARATOR_RE = re.compile(r"[\s\-./]")


def extract_context(
    text: str, start: int, end: int, window: int = DEFAULT_CONTEXT_WINDOW
) -> Tuple[str, str]:
    """Extract a context window before and after a substring in ``text``.

    The window is clamped at the text bounds; callers receive what is
    actually available rather than padded strings.

    Args:
        text: Source text (the same passed to
            :meth:`LLMJudgeValidator.filter`).
        start: Start offset of the entity in ``text`` (inclusive).
        end: End offset of the entity in ``text`` (exclusive).
        window: Maximum number of characters to extract on each side.
            Defaults to :data:`DEFAULT_CONTEXT_WINDOW` (300).

    Returns:
        ``(ctx_before, ctx_after)`` -- substrings of ``text`` of length at
        most ``window``.
    """
    ctx_start = max(0, start - window)
    ctx_end = min(len(text), end + window)
    return text[ctx_start:start], text[end:ctx_end]


def _entity_attr(entity: Any, *names: str, default: Any = "") -> Any:
    """Read the first available attribute (or mapping key) from ``entity``.

    The detection pipeline mixes :class:`PIIEntity` dataclasses, plain
    dicts and dynamic attributes attached at runtime (e.g. ``source``,
    ``page_title``). This helper keeps the prompt builder agnostic of the
    concrete shape so it can be tested in isolation.
    """
    for name in names:
        if hasattr(entity, name):
            return getattr(entity, name)
        if isinstance(entity, dict) and name in entity:
            return entity[name]
    return default


def build_user_prompt(
    entity: Any,
    text: str,
    context_window: int = DEFAULT_CONTEXT_WINDOW,
) -> str:
    """Render the user prompt for a single PII finding.

    The 300-char context window (default) is extracted from ``text`` using
    the entity's ``start``/``end`` offsets. When the entity sits near the
    start or the end of the document the window is truncated -- never
    padded -- so the prompt stays faithful to the source.

    Args:
        entity: A :class:`PIIEntity`-like object exposing at least
            ``pii_type``, ``text``, ``start`` and ``end``. Optional
            attribute: ``type_label`` (falls back to ``pii_type``).
        text: The full source text (the same passed to
            :meth:`LLMJudgeValidator.filter`).
        context_window: Number of characters to extract before and after
            the entity. Defaults to :data:`DEFAULT_CONTEXT_WINDOW`.

    Returns:
        The rendered user prompt (string).
    """
    pii_type = _entity_attr(entity, "pii_type", "type", default="UNKNOWN")
    # type_label may be present but empty (the PIIEntity dataclass populates
    # it even when no localized label exists); fall back on pii_type so the
    # prompt always carries a meaningful French signal to the judge.
    type_label = _entity_attr(entity, "type_label", "type_fr", default="") or pii_type
    value = _entity_attr(entity, "text", "value", default="")
    start = int(_entity_attr(entity, "start", default=0))
    end = int(_entity_attr(entity, "end", default=0))

    value_digits_only = _SEPARATOR_RE.sub("", str(value))
    ctx_before, ctx_after = extract_context(text, start, end, context_window)

    return _USER_PROMPT_TEMPLATE.format(
        pii_type=pii_type,
        type_label=type_label,
        value=value,
        value_digits_only=value_digits_only,
        ctx_before=ctx_before,
        ctx_after=ctx_after,
    )


# ---------------------------------------------------------------------------
# Pydantic verdict
# ---------------------------------------------------------------------------


VerdictLiteral = Literal["TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE"]


class PiiVerdict(BaseModel):
    """Validated verdict returned by the Qwen 3.6 judge.

    Used after :func:`_extract_json_payload` (commit 3) to enforce the
    expected shape on the parsed JSON. The pydantic validation also
    serves as a second line of defence against malformed responses that
    might slip through the LM Studio JSON Schema strict mode.
    """

    # Field order mirrors VERDICT_SCHEMA: reason first so the model emits its
    # justification before committing to the verdict (a bounded in-schema
    # chain-of-thought), confidence next, verdict last.
    reason: str = Field(max_length=300)
    confidence: float = Field(ge=0.0, le=1.0)
    verdict: VerdictLiteral

    @field_validator("reason")
    @classmethod
    def _strip_reason(cls, value: str) -> str:
        return value.strip()


# OpenAI-compatible JSON Schema used in the LM Studio payload with
# ``response_format: {"type": "json_schema", "strict": true}``. The
# schema is intentionally mirrored on :class:`PiiVerdict` so the two
# remain in sync.
# Property order is significant: with strict grammar-constrained decoding the
# model produces keys in declared order. Placing ``reason`` first forces a
# short natural-language justification to be decoded before the ``verdict``
# enum is committed, conditioning the verdict on those tokens (an in-schema
# mini chain-of-thought that stays parseable and bounded).
VERDICT_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "properties": {
        "reason": {"type": "string", "maxLength": 300},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "verdict": {
            "type": "string",
            "enum": ["TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE"],
        },
    },
    "required": ["reason", "confidence", "verdict"],
    "additionalProperties": False,
}


__all__ = [
    "ACTIVE_VARIANT",
    "DEFAULT_CONTEXT_WINDOW",
    "PROMPT_VARIANTS",
    "PiiVerdict",
    "SYSTEM_PROMPT",
    "SYSTEM_PROMPT_CONTEXT_AWARE",
    "VERDICT_SCHEMA",
    "VerdictLiteral",
    "build_user_prompt",
    "extract_context",
]
