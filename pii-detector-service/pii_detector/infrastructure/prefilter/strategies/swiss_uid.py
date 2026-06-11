"""TAX_ID / TAX_NUMBER pre-filter strategy (Swiss UID + mod-11 via stdnum).

Rejects a value **only** when its cleaned form matches the strict Swiss UID
shape ``CHE`` + 9 digits AND :func:`stdnum.ch.uid.is_valid` reports the mod-11
check digit invalid. Cleaning strips spaces, the ``-``/``.`` separators and the
tolerated VAT suffixes (``TVA``/``MWST``/``IVA``), so ``CHE-123.456.789``,
``CHE 123.456.789``, ``CHE123456789`` and ``CHE-123.456.789 MWST`` all reduce
to the same ``CHE123456789`` strict form. Every other shape fails open:

- a truncated fragment (``CHE-123``) -> not the strict form -> PASS;
- a foreign / non-CHE tax id out of the CHE+9 template (``TX940039``) -> PASS;
- a non-``str`` value or any unexpected exception -> PASS (fail-open absolute).

Unlike :class:`AvsNumberStrategy` we gate on a local regex *before* calling
``is_valid``: ``stdnum`` returns ``False`` for ``CHE-123`` and ``TX940039``
too, but those are out-of-scope shapes that must PASS, not reject.

Ground truth (verified against ``stdnum.ch.uid`` before pinning the tests):
``CHE-116.281.710`` (UID of the Confederation) valid -> PASS;
``CHE-116.281.711`` mod-11 KO -> reject; ``CHE-123`` truncated -> PASS;
``TX940039`` foreign tax id -> PASS.

Measured on the reference corpus (``data-analysis.md``): 0/37 false positives
matched the strict CHE+9 shape, so this strategy catches nothing on that
corpus -- the measured FP gain is exactly nil. It is kept purely as prod
protection against a malformed Swiss UID (same rationale as
:class:`IbanStrategy`), never counted in the FP ROI.
"""

import re

from stdnum.ch import uid

from pii_detector.infrastructure.prefilter.prefilter_strategy import (
    PASS,
    PrefilterVerdict,
)

# Tolerated VAT register suffixes (TVA / MWST / IVA), stripped before matching
# the strict shape so "CHE-123.456.789 MWST" reduces to "CHE123456789".
_VAT_SUFFIXES = ("TVA", "MWST", "IVA")
# Canonical UID separators (space / dot / hyphen) removed before the shape check.
_SEPARATORS = re.compile(r"[\s.\-]")
# Strict Swiss UID form: "CHE" + exactly 9 digits, nothing else.
_UID_FORM = re.compile(r"^CHE\d{9}$")


class SwissUidStrategy:
    """Reject strict-form Swiss UIDs (CHE + 9 digits) failing the mod-11 key."""

    pii_type = "TAX_ID"

    def evaluate(self, value: str) -> PrefilterVerdict:
        if not isinstance(value, str):  # type barrier (research §5)
            return PASS
        cleaned = value.strip().upper()
        for suffix in _VAT_SUFFIXES:
            if cleaned.endswith(suffix):
                cleaned = cleaned[: -len(suffix)].rstrip()
                break
        cleaned = _SEPARATORS.sub("", cleaned)
        if not _UID_FORM.match(cleaned):  # not the strict CHE+9 form -> keep
            return PASS
        try:
            if uid.is_valid(cleaned):  # CHE + 9 digits + mod-11 check digit
                return PASS
            return PrefilterVerdict(False, "swiss_uid mod-11 check digit failed")
        except Exception:  # fail-open absolute
            return PASS
