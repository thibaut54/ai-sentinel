"""CARD_NUMBER pre-filter strategy (length 13-19 + Luhn via python-stdnum).

Strips the usual card separators ``[\\s\\-./]`` then rejects a value only when
the cleaned digits are 13-19 long AND :func:`stdnum.luhn.is_valid` reports the
mod-10 checksum invalid. Any other case fails open: a non purely-numeric value
after stripping (letters, IMEI noise), a length outside 13-19 (possibly a span
truncated by chunking), a non-``str`` value, or any exception.

The filter judges format only, never test-vs-prod: the canonical test cards
(``4242 4242 4242 4242``, ``4111 1111 1111 1111``) pass Luhn by construction
(ISO 7812) and therefore PASS -- blacklisting them would leave the format-only
scope (research §3).

Measured on the reference corpus (``data-analysis.md``): 3/21 false positives
caught (14-digit numbers the judge also rejects), 0 real card lost (the 9 Luhn
collisions -- e.g. ``33333333333331`` -- are session/log numbers that pass Luhn
by chance, never an actual card). Marginal FP gain, kept for prod protection
against any well-formed but Luhn-invalid PAN.
"""

import re

from stdnum import luhn
from stdnum.exceptions import ValidationError

from pii_detector.infrastructure.postfilter.postfilter_strategy import (
    PASS,
    PostfilterVerdict,
)

_CARD_SEPARATORS = re.compile(r"[\s\-./]")


class CardNumberStrategy:
    """Reject 13-19 digit values failing the Luhn (mod-10) checksum."""

    pii_type = "CARD_NUMBER"

    def evaluate(self, value: str) -> PostfilterVerdict:
        if not isinstance(value, str):  # type barrier (research §5)
            return PASS
        digits = _CARD_SEPARATORS.sub("", value.strip())
        if not digits.isdigit():  # letters / noise after strip -> keep
            return PASS
        if not 13 <= len(digits) <= 19:  # outside PAN range (truncated?) -> keep
            return PASS
        try:
            if luhn.is_valid(digits):  # mod-10; test cards pass -> keep
                return PASS
            return PostfilterVerdict(False, "card_number luhn failed")
        except (ValidationError, Exception):  # fail-open absolute
            return PASS
