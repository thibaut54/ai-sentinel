from enum import Enum


class JudgeStatus(Enum):
    """Outcome of the LLM-as-judge post-filter for a kept PII entity.

    Mirrors the ``JudgeStatus`` proto enum. ``FALSE_POSITIVE`` is absent on
    purpose: such entities are discarded (surfaced in ``discarded_entities``),
    so they never carry a status. Attached to kept entities so callers can
    tell a judge-validated finding apart from one kept without being judged
    (judge disabled, source not audited, per-type exempt) or kept by the
    fail-open policy after a judge call failed.
    """

    UNSPECIFIED = "JUDGE_STATUS_UNSPECIFIED"
    NOT_AUDITED = "NOT_AUDITED"
    VALIDATED_TRUE_POSITIVE = "VALIDATED_TRUE_POSITIVE"
    VALIDATED_UNSURE = "VALIDATED_UNSURE"
    FAIL_OPEN_KEPT = "FAIL_OPEN_KEPT"
