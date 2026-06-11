"""Deterministic format pre-filter post-detection validator (Stage B).

This module exposes :class:`FormatPrefilterValidator`, an implementation of
the :class:`~pii_detector.domain.port.pii_post_filter_protocol.PIIPostFilterProtocol`
that runs **before** the LLM judge and rejects only the
mechanically-impossible findings (positive checksum / parse failures) per
``pii_type``, via the GO strategies in :mod:`registry`.

Key design points (mirrors :mod:`llm_validator` so the gRPC plumbing is
reused unchanged):

- **Fail-open absolute**: a non-canonical value, an unmapped ``pii_type``, or
  any exception raised by a strategy keeps the entity (recall preserved at all
  costs -- see ``data-analysis.md``).
- **Shared discard channel**: :meth:`filter_with_verdicts` returns the same
  ``(kept, rejections)`` shape as
  :meth:`LLMJudgeValidator.filter_with_verdicts`, so
  ``_add_discarded_entities_to_response`` serialises pre-filter rejections
  without any change. Each rejection carries a :class:`_PrefilterVerdict`
  duck-typed on :class:`PiiVerdict` (``.verdict`` / ``.confidence`` /
  ``.reason``).
- **No HTTP / no model**: the filter is purely deterministic, so there is no
  network call, no thread-pool, and no lifecycle to shut down.

References: ``my-files/prefilter-work/PLAN.md`` sections 1.5-1.6.
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from typing import List, Optional, Tuple

from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.port.pii_post_filter_protocol import (
    PIIPostFilterProtocol,
)
from pii_detector.infrastructure.prefilter.registry import STRATEGIES

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Verdict duck-type
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class _PrefilterVerdict:
    """Duck-typed verdict matching :class:`PiiVerdict` (.verdict/.confidence/.reason).

    ``_add_discarded_entities_to_response`` reads these three attributes via
    ``getattr`` only, so this lightweight dataclass plugs into the shared
    ``discarded_entities`` channel without importing pydantic.
    """

    verdict: str
    confidence: float
    reason: str


# ---------------------------------------------------------------------------
# pii_type normalisation (local 3-liner -- avoids importing the gRPC module)
# ---------------------------------------------------------------------------


def _normalize_pii_type(pii_type) -> str:
    """Normalise a ``pii_type`` to the UPPER_SNAKE key used by the registry.

    Reimplements the gRPC ``_normalize_pii_type_for_grpc`` rule locally so the
    infrastructure pre-filter never imports the inbound gRPC adapter. Free-text
    GLiNER2 labels (``card number``) become ``CARD_NUMBER``.
    """
    if pii_type is None or pii_type == "":
        return "UNKNOWN"
    return str(pii_type).upper().replace(" ", "_").replace("-", "_")


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


_INSTANCE: Optional["FormatPrefilterValidator"] = None
_INSTANCE_LOCK = threading.Lock()


def get_instance() -> "FormatPrefilterValidator":
    """Return the process-wide :class:`FormatPrefilterValidator` singleton.

    Built lazily (no I/O at import time) so the no-prefilter path never
    allocates anything.
    """
    global _INSTANCE
    if _INSTANCE is None:
        with _INSTANCE_LOCK:
            if _INSTANCE is None:
                _INSTANCE = FormatPrefilterValidator()
    return _INSTANCE


def _reset_singleton_for_tests() -> None:
    """Reset the module-level singleton (test-only helper)."""
    global _INSTANCE
    with _INSTANCE_LOCK:
        _INSTANCE = None


# ---------------------------------------------------------------------------
# Validator
# ---------------------------------------------------------------------------


class FormatPrefilterValidator(PIIPostFilterProtocol):
    """Deterministic format pre-filter post-filter (Stage B, before the judge).

    Behaviour summary:

    - For each entity, the ``pii_type`` is normalised and looked up in
      :data:`registry.STRATEGIES`. Unmapped types pass through unchanged.
    - The matched strategy's :meth:`evaluate` decides keep / reject. A
      ``keep=False`` verdict (positive checksum / parse failure) discards the
      entity with a synthetic ``FALSE_POSITIVE`` verdict (confidence 1.0).
    - Any exception raised by a strategy keeps the entity (defensive
      fail-open).
    """

    SOURCE_NAME = "format-prefilter"

    # -- PIIPostFilterProtocol -----------------------------------------------

    @property
    def name(self) -> str:
        """Stable identifier for the filter, used in logs and metrics."""
        return self.SOURCE_NAME

    def filter(
        self, text: str, entities: List[PIIEntity]
    ) -> List[PIIEntity]:
        """Return the subset of entities to keep after the format pre-filter."""
        return self.filter_with_verdicts(text, entities)[0]

    def filter_with_verdicts(
        self, text: str, entities: List[PIIEntity]
    ) -> Tuple[List[PIIEntity], List[Tuple[PIIEntity, _PrefilterVerdict]]]:
        """Filter entities and also return the rejected ones with verdicts.

        Same ``(kept, rejections)`` contract as
        :meth:`LLMJudgeValidator.filter_with_verdicts`: ``kept`` preserves the
        original ordering and ``rejections`` lists each discarded entity with
        the ``FALSE_POSITIVE`` verdict that motivated the rejection. Entities
        kept by fail-open never appear in ``rejections``.
        """
        kept: List[PIIEntity] = []
        rejections: List[Tuple[PIIEntity, _PrefilterVerdict]] = []
        for entity in entities:
            pii_type = _normalize_pii_type(entity.pii_type)
            strategy = STRATEGIES.get(pii_type)
            if strategy is None:  # type not in scope -> keep
                kept.append(entity)
                continue
            try:
                verdict = strategy.evaluate(str(entity.text))
            except Exception:  # defensive fail-open
                kept.append(entity)
                continue
            if verdict.keep:
                kept.append(entity)
            else:
                rejections.append(
                    (
                        entity,
                        _PrefilterVerdict(
                            verdict="FALSE_POSITIVE",
                            confidence=1.0,
                            reason=verdict.reason,
                        ),
                    )
                )
        return kept, rejections


__all__ = [
    "FormatPrefilterValidator",
    "get_instance",
]
