"""MAC_ADDRESS variant: the FORBIDDEN "6 hex octets" rule (experiment only).

This is the trap rule documented in ``data-analysis.md`` and ``PLAN.md`` 4.3:
reject a value unless it matches ``(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}``.

Because every digit 0-9 is also a valid hex digit, a log time range such as
``13:56:49-13:56:52`` matches this pattern exactly like a real MAC, so the rule
KEEPS it (fail-open) instead of rejecting it. Measured on the corpus: this
variant catches 0/12 of the MAC false positives, versus 12/12 for the
production "consistent separator" rule. It exists purely so the benchmark can
prove the trap is reproducible; it is never registered in the prod registry.
"""

import re

from pii_detector.infrastructure.postfilter.postfilter_strategy import (
    PASS,
    PostfilterVerdict,
)

_SIX_HEX_OCTETS = re.compile(r"^(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")


class MacSixOctetHexStrategy:
    """Reject values that do NOT match a 6-hex-octet MAC shape (the trap)."""

    pii_type = "MAC_ADDRESS"

    def evaluate(self, value: str) -> PostfilterVerdict:
        if not isinstance(value, str):
            return PASS
        s = value.strip()
        if _SIX_HEX_OCTETS.match(s):
            # Looks like 6 hex octets -> "valid MAC" -> keep. This is exactly
            # how the time ranges slip through.
            return PASS
        return PostfilterVerdict(
            False, "does not match 6-hex-octet MAC shape"
        )
