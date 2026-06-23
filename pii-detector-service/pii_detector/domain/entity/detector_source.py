from enum import Enum

class DetectorSource(Enum):
    """Source of the detected PII entity."""
    UNKNOWN_SOURCE = "UNKNOWN_SOURCE"
    GLINER = "GLINER"
    PRESIDIO = "PRESIDIO"
    REGEX = "REGEX"
    OPENMED = "OPENMED"
    GLINER2 = "GLINER2"
    # Not a PII detector: the LLM-as-judge post-filter, surfaced as a pseudo
    # detector in run-stats so its velocity (seconds per judged PII) and the
    # number of PII it discarded can be measured. Never labels a real entity.
    JUDGE = "JUDGE"
    # Not a PII detector: the deterministic format pre-filter, surfaced as a
    # pseudo detector in run-stats so the number of PII it discarded can be
    # measured. Never labels a real entity's source.
    PREFILTER = "PREFILTER"
    # Specialised LLM PII detector (Ministral-PII). Permanently exempt from the
    # LLM-as-judge post-filter (same model nature): its entities stay NOT_AUDITED.
    MINISTRAL = "MINISTRAL"