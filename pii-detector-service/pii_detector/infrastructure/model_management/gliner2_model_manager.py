"""GLiNER2 model management for PII detection.

Distinct from :class:`GLiNERModelManager`: the ``gliner2`` library is a separate
package from ``gliner`` (different load API and tokenizer access). GLiNER2 wraps
a ``microsoft/deberta-v3-large`` encoder behind a multi-task head and is loaded
through ``GLiNER2.from_pretrained(model_id)``.

POC L0 (2026-05-31) confirmed on ``fastino/gliner2-large-v1``:
  - ``GLiNER2.from_pretrained`` loads in ~5s on CPU.
  - the object exposes no usable ``.tokenizer`` attribute, so we fall back to
    ``AutoTokenizer.from_pretrained(model_id)`` (returns a fast tokenizer).
"""
from __future__ import annotations

import contextlib
import io
import logging
from typing import Any

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.domain.exception.exceptions import ModelLoadError


class Gliner2ModelManager:
    """Handles GLiNER2 model downloading and loading operations."""

    def __init__(self, config: DetectionConfig):
        self.config = config
        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")
        self._model: Any = None

    def download_model(self) -> None:
        """GLiNER2 downloads weights lazily on first ``from_pretrained`` call."""
        self.logger.info(
            "GLiNER2 model %s will be downloaded on first load", self.config.model_id
        )

    def load_model(self) -> Any:
        """Load the GLiNER2 model via ``GLiNER2.from_pretrained``.

        Returns:
            Loaded ``GLiNER2`` model instance.

        Raises:
            ModelLoadError: if the library is missing or loading fails.
        """
        self.logger.info("Loading GLiNER2 model: %s", self.config.model_id)
        try:
            from gliner2 import GLiNER2

            # gliner2 (>=1.3) prints a config banner containing an emoji via
            # plain print(); on a Windows cp1252 console this raises
            # UnicodeEncodeError and aborts loading. Swallow that chatty stdout
            # into an in-memory buffer so loading is encoding-independent.
            with contextlib.redirect_stdout(io.StringIO()):
                self._model = GLiNER2.from_pretrained(self.config.model_id)
            self.logger.info("GLiNER2 model loaded successfully")
            return self._model
        except ImportError as exc:
            self.logger.error("gliner2 library not installed")
            raise ModelLoadError(
                "gliner2 library not installed. Install with: pip install gliner2"
            ) from exc
        except Exception as exc:
            self.logger.error("Error loading GLiNER2 model: %s", exc)
            raise ModelLoadError(f"Failed to load GLiNER2 model: {exc}") from exc

    def get_tokenizer(self) -> Any:
        """Return a tokenizer for subword chunking.

        Tries the GLiNER2 object first (no reliable attribute as of lib 1.3.1),
        then falls back to ``AutoTokenizer.from_pretrained(model_id)`` which the
        POC validated returns a fast tokenizer supporting
        ``return_offsets_mapping``.
        """
        for attr in ("tokenizer",):
            candidate = getattr(self._model, attr, None)
            if candidate is not None and hasattr(candidate, "__call__"):
                self.logger.debug("Using GLiNER2.%s as tokenizer", attr)
                return candidate

        from transformers import AutoTokenizer

        self.logger.debug(
            "GLiNER2 exposes no usable tokenizer attribute; "
            "falling back to AutoTokenizer for %s",
            self.config.model_id,
        )
        return AutoTokenizer.from_pretrained(self.config.model_id)
