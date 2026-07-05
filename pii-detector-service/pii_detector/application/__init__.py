"""
Application layer for PII detection.
Exports use cases, orchestration components, factories, and application configuration.

This layer orchestrates domain logic and coordinates between domain and infrastructure.
"""

# Import and re-export application configuration
from .config.detection_policy import DetectionConfig

# NOTE: orchestration components are NOT imported here to avoid circular imports.
# Import them directly from their modules when needed:
#   from pii_detector.application.orchestration.composite_detector import CompositePIIDetector

__all__ = [
    # ===== CONFIGURATION =====
    "DetectionConfig",
]
