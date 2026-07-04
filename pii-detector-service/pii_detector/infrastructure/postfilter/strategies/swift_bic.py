"""SWIFT/BIC post-filter strategy (structural validation via python-stdnum).

Rejects a value only when it makes a full-length BIC claim (exactly 8 or 11
alphanumeric characters after normalisation) AND :func:`stdnum.bic.is_valid`
reports the structure invalid (bank code letters, ISO country code, branch).
There is no checksum in a BIC, so this is a pure structural Tier C filter:
any truncated / fragment / exotic form fails open.
"""

import re

from stdnum import bic as _bic

from pii_detector.infrastructure.postfilter.postfilter_strategy import (
    PASS,
    PostfilterVerdict,
)

_BIC_CLAIM = re.compile(r"^[A-Z0-9]{8}(?:[A-Z0-9]{3})?$")


class SwiftBicStrategy:
    """Reject full-length BIC claims failing the structural validation."""

    pii_type = "SWIFT_BIC"

    def evaluate(self, value: str) -> PostfilterVerdict:
        if not isinstance(value, str):
            return PASS
        s = value.strip().upper()
        if not _BIC_CLAIM.match(s):  # truncated / fragment -> keep
            return PASS
        try:
            if _bic.is_valid(s):
                return PASS
            return PostfilterVerdict(False, "swift_bic structure failed")
        except Exception:  # fail-open absolute
            return PASS
