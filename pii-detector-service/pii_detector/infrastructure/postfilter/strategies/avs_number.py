"""AVS_NUMBER pre-filter strategy (756 prefix + EAN-13 via python-stdnum).

Strips every non-digit then rejects a value only when it is a 13-digit number
starting with ``756`` whose EAN-13 check digit is wrong -- i.e. only when
:func:`stdnum.ch.ssn.validate` raises :class:`InvalidChecksum`. Every other
case fails open:

- 11-digit legacy AVS (no EAN-13 checksum) -> ``InvalidLength`` -> PASS;
- any other length -> ``InvalidLength`` -> PASS;
- prefix != 756 -> ``InvalidComponent`` -> PASS (NOT a reject -- a 13-digit
  number with a foreign prefix is out of the AVS-13 scope);
- non-``str`` value or any unexpected exception -> PASS (fail-open absolute).

Ground truth (research §1): ``756.9217.0769.85`` valid (PASS),
``756.9217.0769.84`` checksum KO (reject), ``756.1234.1234.12`` wrong key
(reject).

Measured on the reference corpus (``data-analysis.md``): 0/5 false positives
caught -- the 5 discards all carry a valid EAN-13 key and were flagged as false
positives for the wrong reason (a 9-digit AVS format was expected). The 13/1034
finding collisions
are synthetic test AVS (``756.1234.1234.12``), not real numbers. 0 real FP gain
on this corpus; kept for consistency and prod protection against a malformed
AVS-13.
"""

import re

from stdnum.ch import ssn
from stdnum.exceptions import InvalidChecksum

from pii_detector.infrastructure.postfilter.postfilter_strategy import (
    PASS,
    PostfilterVerdict,
)

_NON_DIGITS = re.compile(r"\D")


class AvsNumberStrategy:
    """Reject 13-digit 756-prefixed AVS failing the EAN-13 checksum."""

    pii_type = "AVS_NUMBER"

    def evaluate(self, value: str) -> PostfilterVerdict:
        if not isinstance(value, str):  # type barrier (research §5)
            return PASS
        digits = _NON_DIGITS.sub("", value)  # strip dots / spaces / noise
        try:
            ssn.validate(digits)  # 13 digits + 756 prefix + EAN-13
            return PASS
        except InvalidChecksum:  # 13 digits, 756, wrong key -> reject
            return PostfilterVerdict(False, "avs_number ean-13 checksum failed")
        except Exception:  # InvalidLength / InvalidComponent / ... -> keep
            return PASS
