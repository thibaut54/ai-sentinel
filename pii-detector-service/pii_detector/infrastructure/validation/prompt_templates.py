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

import re
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
    "FORMATAGE (CRITIQUE) :\n"
    "- Les separateurs (espaces, tirets \"-\", points \".\", slashes \"/\") sont\n"
    "  IGNORES pour evaluer le format. Reconstruis la sequence en supprimant\n"
    "  les separateurs AVANT de juger.\n"
    '- Ex : "356-938-035-643-809" -> "356938035643809" = 15 chiffres = IMEI\n'
    "  valide = TP. Un identifiant correctement formate avec des separateurs\n"
    "  reste un TRUE_POSITIVE.\n"
    "- Le champ `value_digits_only` du finding te donne deja la sequence sans\n"
    "  separateurs : utilise-le pour compter les chiffres.\n"
    "- Idem pour IBAN avec espaces, carte de credit avec espaces/tirets,\n"
    "  numero de telephone avec separateurs varies.\n"
    "\n"
    "Exemples (formats canoniques) :\n"
    '- "10.217.4.11" / IP_ADDRESS              -> TP (IPv4, RFC 1918 OK).\n'
    '- "374111111111111" / CREDIT_CARD         -> TP (AmEx 15 ch.).\n'
    '- "CH6930000011100005458" / IBAN          -> TP.\n'
    '- "1A1zP1eP5QGefi2DMPTfTL..." / CRYPTO_WALLET -> TP.\n'
    '- "756.3407.8913.03" / AVS_NUMBER         -> TP.\n'
    '- "021 316 01 57" / PHONE_NUMBER          -> TP.\n'
    "Exemples (mauvais type -> FP) :\n"
    '- "PCV-1189" / NATIONAL_ID                -> FP (code projet).\n'
    '- "41780007878" / SOCIALNUM               -> FP (mobile, pas AVS).\n'
    '- "92366499.59" / BANK_ACCOUNT_NUMBER     -> FP (montant).\n'
    '- "DGAIC" / PASSWORD                      -> FP (acronyme).\n'
    "Exemples (separateurs -> NE PAS rejeter a cause du formatage) :\n"
    '- "356-938-035-643-809" / IMEI            -> TP (15 ch. apres normalisation).\n'
    '- "8 6 9 4 6 2 0 5 0 2 7 4 3 1 9" / IMEI  -> TP (15 ch. apres normalisation).\n'
    '- "CH69 3000 0011 1000 0545 8" / IBAN     -> TP (espaces -> normalise).\n'
    '- "4242 4242 4242 4242" / CREDIT_CARD     -> TP (Visa 16 ch. avec espaces).\n'
    "\n"
    "REGLES DE SORTIE (CRITIQUES) :\n"
    "- Reponds UNIQUEMENT par un objet JSON valide, rien d'autre.\n"
    "- N'inclus AUCUN texte avant ou apres le JSON.\n"
    "- N'inclus AUCUN bloc <think>...</think>, aucun raisonnement explicite.\n"
    "- Pas de markdown, pas de ```json fences.\n"
    "- Ordre des champs IMPOSE : `reason` d'abord (justifie en 1 phrase),\n"
    "  puis `confidence`, puis `verdict` en dernier. Le verdict doit DECOULER\n"
    "  du reason.\n"
    "- Schema strict :\n"
    '  {"reason": "<1 phrase francais, max 300 chars>",\n'
    '   "confidence": <float 0..1>,\n'
    '   "verdict": "TRUE_POSITIVE"|"FALSE_POSITIVE"|"UNSURE"}'
)


# ---------------------------------------------------------------------------
# Prompt variants (offline A/B comparison harness — see
# tests/integration/test_llm_judge_prompt_comparison.py)
# ---------------------------------------------------------------------------

# Asymmetric context clause appended to the baseline. It probes whether telling
# the judge to weigh the *intent* of the surrounding text lets it reject format
# look-alikes (a 64-hex SHA256 tagged ETHEREUM_ADDRESS, a 15-digit order id
# tagged IMEI) WITHOUT eroding recall on ``canonical_no_clue`` findings (valid
# PII that simply has no contextual cue around it). The rule is deliberately
# asymmetric: context can only ADD a rejection, never withdraw a positive.
_CONTEXT_AWARE_RULE = (
    "CONTEXTE (CRITIQUE, ASYMETRIQUE) :\n"
    "- Le contexte LEVE l'ambiguite : s'il designe explicitement un AUTRE\n"
    '  type (ex: "sha256(...)", "hash", "digest", "checksum", "commit",\n'
    '  "exemple", "id technique", "numero de commande", "port"), c\'est\n'
    "  FALSE_POSITIVE meme si le format ressemble au pii_type revendique.\n"
    "- L'ABSENCE d'indice contextuel n'est PAS un motif de rejet : un format\n"
    "  et une semantique corrects suffisent. Un IMEI a 15 chiffres valide\n"
    '  sans le mot "IMEI" autour reste un TRUE_POSITIVE. Ne baisse JAMAIS le\n'
    "  verdict au seul motif qu'aucun indice contextuel n'entoure la value.\n"
    "\n"
)

# v2 = v1 with the asymmetric context clause inserted right before the output
# rules. Built by substitution (not re-authored) so the few-shots and the
# strict reason-first JSON contract stay byte-identical to SYSTEM_PROMPT: the
# only variable under test is the context clause, not prompt wording noise.
SYSTEM_PROMPT_CONTEXT_AWARE = SYSTEM_PROMPT.replace(
    "REGLES DE SORTIE (CRITIQUES) :",
    _CONTEXT_AWARE_RULE + "REGLES DE SORTIE (CRITIQUES) :",
    1,
)


# Registry consumed by the prompt-comparison eval. Keys are stable ids used in
# the report; values are injected into ``LLMJudgeValidator`` via its
# ``system_prompt`` constructor arg. ``v1_baseline`` MUST stay identical to the
# production ``SYSTEM_PROMPT`` so the eval measures deltas against prod, and the
# winning variant can later be promoted by replacing ``SYSTEM_PROMPT`` itself.
PROMPT_VARIANTS: Dict[str, str] = {
    "v1_baseline": SYSTEM_PROMPT,
    "v2_context_aware": SYSTEM_PROMPT_CONTEXT_AWARE,
}


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
