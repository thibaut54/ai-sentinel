"""
Configuration for machine learning model settings.

This module centralizes model-related configuration such as
model identifiers, thresholds, and runtime parameters.
"""

from dataclasses import dataclass


@dataclass
class ModelConfig:
    """Configuration for ML model identifiers and runtime parameters."""

    @classmethod
    def from_env(cls) -> "ModelConfig":
        """Placeholder for future environment-based configuration. Currently returns a default instance."""
        return cls()

    def validate(self) -> None:
        """Placeholder for future configuration validation. Currently a no-op."""
        pass
