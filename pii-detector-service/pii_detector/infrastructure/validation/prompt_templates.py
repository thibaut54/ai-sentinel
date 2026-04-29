from __future__ import annotations

# NOTE: the prompt below mandates a strict format ("[N]: VERDICT" with the
# bracket and colon required) but the parser in `llm_validator._VERDICT_PATTERN`
# is intentionally more permissive (accepts space, tab, dot or dash separators
# and missing brackets) so a Gemma 4 quirk does not silently kill rejection
# decisions. Defense-in-depth: tighten the prompt, loosen the parser.

from typing import List, Tuple

BATCH_ENTITY_TEMPLATE = (
    "Tu es un expert en protection des donnees personnelles (PII).\n"
    "Un systeme de detection automatique a identifie les elements suivants.\n"
    "Pour chacun, determine s'il s'agit d'un vrai positif (TRUE_POSITIVE)\n"
    "ou d'un faux positif (FALSE_POSITIVE).\n"
    "\n"
    "{entities_block}\n"
    "\n"
    "Reponds UNIQUEMENT au format suivant, un verdict par ligne, sans texte\n"
    "additionnel, sans commentaire, sans ligne vide :\n"
    "[0]: TRUE_POSITIVE\n"
    "[1]: FALSE_POSITIVE\n"
    "\n"
    "Le crochet, les deux-points et le verdict sont obligatoires."
)

_ENTITY_LINE_TEMPLATE = (
    '[{index}] Type: {pii_type_label} | Texte: "{entity_text}"'
    ' | Contexte: "...{context}..."'
)

SINGLE_ENTITY_TEMPLATE = (
    "Tu es un expert en protection des donnees personnelles (PII).\n"
    "Un systeme de detection automatique a identifie le texte suivant comme etant\n"
    "un(e) {pii_type_label} :\n"
    "\n"
    'Texte detecte : "{entity_text}"\n'
    'Contexte : "...{context_before} [{entity_text}] {context_after}..."\n'
    "\n"
    "Est-ce reellement un(e) {pii_type_label}, ou est-ce un faux positif\n"
    "(par exemple un nom de projet, un acronyme technique, un identifiant\n"
    "applicatif, un terme metier, etc.) ?\n"
    "\n"
    "Reponds UNIQUEMENT par :\n"
    "- TRUE_POSITIVE si c'est reellement un(e) {pii_type_label}\n"
    "- FALSE_POSITIVE si c'est un faux positif\n"
    "\n"
    "Reponse :"
)


def extract_context(
    source_text: str, start: int, end: int, window: int = 200
) -> Tuple[str, str]:
    ctx_start = max(0, start - window)
    ctx_end = min(len(source_text), end + window)
    return source_text[ctx_start:start], source_text[end:ctx_end]


def build_batch_prompt(
    entities: List, source_text: str, context_window: int = 200
) -> str:
    lines = [
        _ENTITY_LINE_TEMPLATE.format(
            index=i,
            # Use the localized label (e.g. "Nom de personne") for consistency
            # with build_single_prompt and to give Gemma a French signal that
            # matches the rest of the prompt instead of an opaque enum name.
            pii_type_label=getattr(e, "type_label", None) or e.pii_type,
            entity_text=e.text,
            context=source_text[
                max(0, e.start - context_window):
                min(len(source_text), e.end + context_window)
            ],
        )
        for i, e in enumerate(entities)
    ]
    entities_block = "\n".join(lines)
    return BATCH_ENTITY_TEMPLATE.format(entities_block=entities_block)


def build_single_prompt(
    entity, source_text: str, context_window: int = 200
) -> str:
    before, after = extract_context(
        source_text, entity.start, entity.end, context_window
    )
    return SINGLE_ENTITY_TEMPLATE.format(
        pii_type_label=entity.type_label,
        entity_text=entity.text,
        context_before=before,
        context_after=after,
    )
