"""
LLM-as-Judge post-detection validator (Qwen 3.6 via LM Studio).

This module exposes :class:`LLMJudgeValidator`, an implementation of the
:class:`~pii_detector.domain.port.pii_post_filter_protocol.PIIPostFilterProtocol`
that audits detected PII entities by asking a Qwen 3.6 thinking model whether
each finding is a true positive or a false positive.

Current state: SKELETON only. The actual wiring (HTTP client, prompt building,
JSON parsing) is implemented in commits 2 and 3 of the
``feature/qwen-as-judge`` plan.

References:
- Spec: ``_bmad-output/planning-artifacts/llm-judge-qwen-spec.md`` (sections
  2.3 and 2.4 for the adapter contract).
- Predecessor branch: ``feature/gemma4-as-judge``.
"""

from __future__ import annotations

import logging
from typing import List, Optional

from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.port.pii_post_filter_protocol import (
    PIIPostFilterProtocol,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Singleton lazy stub (will be expanded in commit 3).
# ---------------------------------------------------------------------------
_INSTANCE: Optional["LLMJudgeValidator"] = None


def get_instance() -> "LLMJudgeValidator":
    """Return the process-wide :class:`LLMJudgeValidator` singleton.

    The singleton is intentionally lazy: it is built on first call so that
    importing this module does not trigger HTTP I/O. The actual HTTP client,
    model resolution and atexit shutdown are wired in commit 3.
    """
    global _INSTANCE
    if _INSTANCE is None:
        _INSTANCE = LLMJudgeValidator()
    return _INSTANCE


class LLMJudgeValidator(PIIPostFilterProtocol):
    """LLM-as-Judge post-filter (Qwen 3.6 — skeleton).

    At this stage :meth:`filter` is a no-op that returns the entities
    untouched. It exists only so that the wiring (port -> adapter ->
    composite detector) can be put in place without dragging the full HTTP
    client implementation into this commit.

    Commit roadmap:
    - Commit 1 (this one): skeleton, no-op filter, port wired.
    - Commit 2: prompt templates + Pydantic verdict schema.
    - Commit 3: LM Studio HTTP client, /v1/models resolution, JSON parsing,
      ``response_format: json_schema strict`` payload.
    """

    NAME = "llm-judge-qwen-3.6"

    def __init__(self) -> None:
        # Placeholder slots that will hold the wired client/executor in
        # commit 3. Keep them here so the public surface is stable.
        self._client = None
        self._executor = None
        self._resolved_model_id: Optional[str] = None

    # -- PIIPostFilterProtocol -----------------------------------------------

    @property
    def name(self) -> str:
        """Stable identifier for the filter, used in logs and metrics."""
        return self.NAME

    def filter(
        self, text: str, entities: List[PIIEntity]
    ) -> List[PIIEntity]:
        """No-op skeleton; returns the input list unchanged.

        Args:
            text: Source text (unused at this stage).
            entities: Merged entities from the detection pipeline.

        Returns:
            A shallow copy of ``entities`` (callers must not rely on identity).
        """
        logger.debug(
            "LLMJudgeValidator skeleton -- wiring deferred (entities=%d)",
            len(entities),
        )
        # Return a shallow copy to honor the contract "never mutate inputs".
        return list(entities)
