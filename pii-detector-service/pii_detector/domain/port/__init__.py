"""Domain ports (interfaces) - Dependency Inversion Principle."""

from .pii_detector_protocol import PIIDetectorProtocol
from .pii_post_filter_protocol import PIIPostFilterProtocol

__all__ = ["PIIDetectorProtocol", "PIIPostFilterProtocol"]
