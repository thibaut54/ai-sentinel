"""
Protocol defining the contract for post-detection PII filters.

This module defines the PIIPostFilterProtocol used by the application layer
to plug filters that run AFTER the primary detection + merge pipeline
(e.g. the deterministic format post-filter). Filters audit the merged
entity list and decide which entities to keep or discard.

Architecture:
- Domain port: depends only on domain entities (PIIEntity)
- Implementations live in the infrastructure layer
- Multiple filters can be composed sequentially via a pipeline
"""

from typing import List, Protocol, runtime_checkable

from pii_detector.domain.entity.pii_entity import PIIEntity


@runtime_checkable
class PIIPostFilterProtocol(Protocol):
    """
    Port for filters applied after detection and merge.

    Implementations must:
    - Be idempotent: filter(filter(t, e)) == filter(t, e)
    - Never mutate input entities (return a new list of references)
    - Fail-open on errors when configured to preserve recall

    Examples of post-filters:
    - Deterministic format post-filter (precision audit on merged entities)
    - Allow/block lists by value or by source
    - Confidence threshold gating
    """

    @property
    def name(self) -> str:
        """Stable identifier for the filter, used in logs and metrics."""
        ...

    def filter(
        self, text: str, entities: List[PIIEntity]
    ) -> List[PIIEntity]:
        """
        Return the subset of entities to keep after judgment.

        Args:
            text: Full source text (needed for context extraction).
            entities: Merged entities from the detection pipeline.

        Returns:
            A new list containing the kept entities (subset of input).
        """
        ...
