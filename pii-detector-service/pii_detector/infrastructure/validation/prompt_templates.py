"""
Prompt templates and verdict schema for the Qwen 3.6 LLM-as-Judge.

This module is a direct port of the prompts validated by the external
evaluation pipeline (``D:\\ai-sentinel-result-eval``) and tightened with
the strict JSON output rules required by the LM Studio runtime
(spec section 2.4).

Three artefacts are exported:

- :data:`SYSTEM_PROMPT`        French system prompt with ``/no_think``,
  10 Swiss-context few-shots and the strict JSON output rules.
- :func:`build_user_prompt`    Builds the per-finding user prompt with a
  300-char context window before/after the entity.
- :class:`PiiVerdict`          Pydantic model used to validate the JSON
  payload returned by the model.
- :data:`VERDICT_SCHEMA`       OpenAI-compatible JSON Schema for the
  ``response_format: {type: "json_schema", strict: true}`` payload
  (commit 3).

Empirical references:
- Spec section 1.6: ``response_format: json_schema strict`` is the only
  reliable strategy on Qwen 3.6 with LM Studio; without it the JSON can
  be truncated inside ``reasoning_content``.
- Spec section 2.4: the prompts replicated below.
"""

from __future__ import annotations

from typing import Any, Dict, Literal, Tuple

from pydantic import BaseModel, Field, field_validator


# ---------------------------------------------------------------------------
# Prompts
# ---------------------------------------------------------------------------


# Ported verbatim from spec section 2.4. The leading ``/no_think`` and the
# REGLES DE SORTIE block are best-effort hints for the model: LM Studio
# ignores both (verified empirically 2026-05-25), but they remain harmless
# and act as defence-in-depth if the runtime ever starts honouring them.
SYSTEM_PROMPT = (
    "/no_think\n"
    "Tu audites la PRECISION de classification d'un detecteur de PII.\n"
    "\n"
    "QUESTION UNIQUE : la `value` extraite a-t-elle le FORMAT et la SEMANTIQUE\n"
    "du `pii_type` revendique ? Tu ne juges PAS si c'est sensible, ni\n"
    "production/test, ni public/prive (RFC 1918 OK), ni connu/exemple.\n"
    "\n"
    "- TRUE_POSITIVE  : format et semantique du pii_type respectes.\n"
    "- FALSE_POSITIVE : la value est d'un autre type (code projet en NATIONAL_ID,\n"
    "  montant en BANK_ACCOUNT_NUMBER, acronyme en PASSWORD, nom de variable en\n"
    "  SESSION_ID, chemin en API_KEY, etc.).\n"
    "- UNSURE         : format ambigu (confidence < 0.6).\n"
    "\n"
    "Exemples :\n"
    '- "10.217.4.11" / IP_ADDRESS              -> TP (IPv4, RFC 1918 OK).\n'
    '- "374111111111111" / CREDIT_CARD         -> TP (AmEx 15 ch.).\n'
    '- "CH6930000011100005458" / IBAN          -> TP.\n'
    '- "1A1zP1eP5QGefi2DMPTfTL..." / CRYPTO_WALLET -> TP.\n'
    '- "756.3407.8913.03" / AVS_NUMBER         -> TP.\n'
    '- "021 316 01 57" / PHONE_NUMBER          -> TP.\n'
    '- "PCV-1189" / NATIONAL_ID                -> FP (code projet).\n'
    '- "41780007878" / SOCIALNUM               -> FP (mobile, pas AVS).\n'
    '- "92366499.59" / BANK_ACCOUNT_NUMBER     -> FP (montant).\n'
    '- "DGAIC" / PASSWORD                      -> FP (acronyme).\n'
    "\n"
    "REGLES DE SORTIE (CRITIQUES) :\n"
    "- Reponds UNIQUEMENT par un objet JSON valide, rien d'autre.\n"
    "- N'inclus AUCUN texte avant ou apres le JSON.\n"
    "- N'inclus AUCUN bloc <think>...</think>, aucun raisonnement explicite.\n"
    "- Pas de markdown, pas de ```json fences.\n"
    "- Schema strict :\n"
    '  {"verdict": "TRUE_POSITIVE"|"FALSE_POSITIVE"|"UNSURE",\n'
    '   "confidence": <float 0..1>,\n'
    '   "reason": "<1 phrase francais, max 300 chars>"}'
)


# Default context window when neither the caller nor the TOML overrides it.
DEFAULT_CONTEXT_WINDOW = 300


_USER_PROMPT_TEMPLATE = (
    "Finding a juger :\n"
    "  pii_type     = {pii_type}\n"
    "  type_label   = {type_label}\n"
    '  value        = "{value}"\n'
    "  detector     = {detector}\n"
    "  score        = {score:.3f}\n"
    '  page_title   = "{page_title}"\n'
    "  source_file  = {source_file}\n"
    '  context      = "...{ctx_before} >>>{value}<<< {ctx_after}..."'
)


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
            attributes: ``type_label``, ``source``, ``score``,
            ``page_title``, ``source_file``.
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
    detector_obj = _entity_attr(entity, "source", "detector", default="UNKNOWN")
    detector = getattr(detector_obj, "value", str(detector_obj))
    score = float(_entity_attr(entity, "score", default=0.0) or 0.0)
    page_title = _entity_attr(entity, "page_title", default="")
    source_file = _entity_attr(entity, "source_file", default="")
    start = int(_entity_attr(entity, "start", default=0))
    end = int(_entity_attr(entity, "end", default=0))

    ctx_before, ctx_after = extract_context(text, start, end, context_window)

    return _USER_PROMPT_TEMPLATE.format(
        pii_type=pii_type,
        type_label=type_label,
        value=value,
        detector=detector,
        score=score,
        page_title=page_title,
        source_file=source_file,
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

    verdict: VerdictLiteral
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str = Field(max_length=300)

    @field_validator("reason")
    @classmethod
    def _strip_reason(cls, value: str) -> str:
        return value.strip()


# OpenAI-compatible JSON Schema used in the LM Studio payload with
# ``response_format: {"type": "json_schema", "strict": true}``. The
# schema is intentionally mirrored on :class:`PiiVerdict` so the two
# remain in sync.
VERDICT_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "properties": {
        "verdict": {
            "type": "string",
            "enum": ["TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE"],
        },
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "reason": {"type": "string", "maxLength": 300},
    },
    "required": ["verdict", "confidence", "reason"],
    "additionalProperties": False,
}


__all__ = [
    "DEFAULT_CONTEXT_WINDOW",
    "PiiVerdict",
    "SYSTEM_PROMPT",
    "VERDICT_SCHEMA",
    "VerdictLiteral",
    "build_user_prompt",
    "extract_context",
]
