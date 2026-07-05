"""MAC_ADDRESS pre-filter strategy ("consistent separator" rule).

Rejects a value only when it mixes ``:`` **and** ``-`` separators. A canonical
MAC uses a single separator type (``98:e7:43:a6:3f:0a`` or
``98-e7-43-a6-3f-0a``), never both; a value containing both is a time range
``HH:MM:SS-HH:MM:SS``.

FORBIDDEN ALTERNATIVE: do NOT implement a "6 hex octets" rule. Every digit
0-9 is a valid hex digit, so ``13:56:49-13:56:52`` matches
``(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}`` exactly like a real MAC and the
time ranges slip through (measured 0/12 caught). Measured here: 12/12 false
positives caught, 0/226 finding collisions (``data-analysis.md``).
"""

from pii_detector.infrastructure.postfilter.postfilter_strategy import (
    PASS,
    PostfilterVerdict,
)


class MacAddressStrategy:
    """Reject values that mix ``:`` and ``-`` separators (not a MAC)."""

    pii_type = "MAC_ADDRESS"

    def evaluate(self, value: str) -> PostfilterVerdict:
        if not isinstance(value, str):
            return PASS
        s = value.strip()
        # A canonical MAC uses ONE separator type. Mixing ':' AND '-' is
        # impossible for a MAC -> it is a time range HH:MM:SS-HH:MM:SS.
        if ":" in s and "-" in s:
            return PostfilterVerdict(
                False, "mixed ':' and '-' separators (not a MAC)"
            )
        return PASS
