"""Strategy contract for the deterministic format pre-filter.

This module defines the building blocks shared by every per-``pii_type``
pre-filter strategy executed **before** the LLM judge (Stage B):

- :class:`PrefilterVerdict` -- the immutable result of evaluating one value
  (``keep`` plus a deterministic ``reason``).
- :data:`PASS` -- the canonical "keep" verdict reused by every strategy that
  fails open.
- :class:`PrefilterStrategy` -- the structural :class:`typing.Protocol` each
  strategy implements (a normalised ``pii_type`` key plus ``evaluate``).

Golden rule of every :meth:`PrefilterStrategy.evaluate` implementation:
return ``keep=False`` **only** on a positive, complete checksum / parse
failure. Any non-canonical, truncated or non-``str`` value must return
:data:`PASS` (fail-open) so a true positive is never dropped (see
``data-analysis.md`` -- the corpus findings still contain fragments and noise).
"""

from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class PrefilterVerdict:
    """Immutable verdict produced by a :class:`PrefilterStrategy`.

    Attributes:
        keep: ``True`` to keep the entity (PASS), ``False`` to reject it as a
            mechanically-impossible false positive.
        reason: Deterministic justification (e.g. ``"ip_address parse
            failed"``); empty for a plain PASS.
    """

    keep: bool
    reason: str


# Canonical "keep" verdict. Reused by every strategy on the fail-open path so
# no allocation / message is needed when a value is simply not in scope.
PASS = PrefilterVerdict(keep=True, reason="")


@runtime_checkable
class PrefilterStrategy(Protocol):
    """Structural contract for a single per-``pii_type`` pre-filter rule.

    Implementations expose:

    - :attr:`pii_type`: the normalised UPPER_SNAKE key the strategy applies to
      (e.g. ``"IP_ADDRESS"``), matching the gRPC ``pii_type`` normalisation.
    - :meth:`evaluate`: a pure, deterministic verdict for a single value.
    """

    pii_type: str

    def evaluate(self, value: str) -> PrefilterVerdict:
        """Return the deterministic keep/reject verdict for ``value``."""
        ...
