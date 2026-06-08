"""IP_ADDRESS pre-filter strategy (stdlib only, zero dependency).

Rejects a value only when :func:`ipaddress.ip_address` raises on it (a
positive parse failure). Everything else fails open. Python 3.11+ rejects
leading-zero octets (CVE-2021-29921), which is the behaviour we want here.

Measured on the reference corpus (``data-analysis.md``): 518/518 false
positives caught, 0 real IPv4 lost (the 36 finding collisions are
mis-typed AVS numbers and log dates, not addresses).
"""

import ipaddress

from pii_detector.infrastructure.prefilter.prefilter_strategy import (
    PASS,
    PrefilterVerdict,
)


class IpAddressStrategy:
    """Reject values that look like an IP but fail :mod:`ipaddress` parsing."""

    pii_type = "IP_ADDRESS"

    def evaluate(self, value: str) -> PrefilterVerdict:
        if not isinstance(value, str):  # type barrier (research §5)
            return PASS
        s = value.strip()
        if not s or s.isdigit():  # avoid implicit ip_address(int) parsing
            return PASS
        if "." not in s and ":" not in s:  # does not look like an IP -> keep
            return PASS
        try:
            ipaddress.ip_address(s)
            return PASS
        except ValueError:
            return PrefilterVerdict(False, "ip_address parse failed")
