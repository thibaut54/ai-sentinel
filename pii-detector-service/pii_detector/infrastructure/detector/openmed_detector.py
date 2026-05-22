"""
OpenMed Privacy Filter Multilingual PII detector.

Wraps ``OpenMed/privacy-filter-multilingual`` as a ``PIIDetectorProtocol``
implementation. We instantiate ``PrivacyFilterTorchPipeline`` **once** at
detector load time and reuse it across calls.

Why we bypass ``openmed.extract_pii``: that helper internally calls
``create_privacy_filter_pipeline(model_name)`` on every invocation, which has
no caching and triggers a full ``AutoModel.from_pretrained`` reload of the
2.7 GB model. On CPU that's 5-10 s of cold start *per detection*. Caching the
pipeline ourselves cuts a 480-call benchmark from ~1h to a few minutes.

The pipeline already runs the model's native **BIOES Viterbi decoder**
(constrained span decoding) via the custom
``OpenAIPrivacyFilterForTokenClassification`` architecture loaded with
``trust_remote_code=True``, so we get clean spans without any post-processing.

The model's 128k context window is handled natively (no chunking needed).

Per-type thresholds and the OpenMed label -> canonical ``pii_type`` mapping are
resolved from the ``pii_type_config`` DB rows (detector='OPENMED').
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional, Tuple

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.exception.exceptions import ModelNotLoadedError, PIIDetectionError

OPENMED_MODEL_ID = "OpenMed/privacy-filter-multilingual"
# Permissive global threshold at this layer; per-type DB thresholds make the
# final cut downstream.
DEFAULT_GLOBAL_THRESHOLD = 0.30


class OpenMedDetector:
    """PII detector backed by ``OpenMed/privacy-filter-multilingual``.

    Implements ``PIIDetectorProtocol``: ``model_id``, ``detect_pii``,
    ``mask_pii``, ``download_model``, ``load_model``.
    """

    def __init__(self, config: Optional[DetectionConfig] = None):
        self.config = config or DetectionConfig(model_id=OPENMED_MODEL_ID)
        # Force the OpenMed model id even if a generic config was passed.
        self._model_id = OPENMED_MODEL_ID
        self.device = self.config.device or "cpu"
        self.threshold = getattr(self.config, "threshold", DEFAULT_GLOBAL_THRESHOLD) or DEFAULT_GLOBAL_THRESHOLD

        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")
        self._pipeline: Any = None
        self._loaded: bool = False

    # ------------------------------------------------------------------
    # PIIDetectorProtocol
    # ------------------------------------------------------------------

    @property
    def model_id(self) -> str:
        return self._model_id

    def download_model(self) -> None:
        """Trigger HF download + pipeline construction (idempotent — cached
        under HF_HOME)."""
        self._ensure_loaded()

    def load_model(self) -> None:
        """Eagerly instantiate the ``PrivacyFilterTorchPipeline`` so the
        2.7 GB model is loaded once. Subsequent ``detect_pii`` calls reuse it."""
        self._ensure_loaded()

    def detect_pii(
        self,
        text: str,
        threshold: Optional[float] = None,
        pii_type_configs: Optional[Dict] = None,
    ) -> List[PIIEntity]:
        if not text:
            return []
        if not self._loaded:
            try:
                self._ensure_loaded()
            except PIIDetectionError:
                raise
            except Exception as exc:  # pragma: no cover - defensive
                raise ModelNotLoadedError(
                    f"OpenMed library could not be loaded: {exc}"
                ) from exc

        effective_threshold = threshold if threshold is not None else self.threshold
        detection_id = f"openmed_{int(time.time() * 1000) % 10000}"

        try:
            label_mapping, scoring_overrides = self._resolve_runtime_config(pii_type_configs)
            self.logger.debug(
                "[%s] OpenMed runtime config: %d labels mapped, %d thresholds",
                detection_id, len(label_mapping), len(scoring_overrides),
            )

            raw_entities = self._run_inference(text, effective_threshold)
            entities = self._convert_to_pii_entities(raw_entities, text, label_mapping)
            entities = self._apply_per_type_thresholds(entities, scoring_overrides)

            self.logger.info(
                "[%s] OpenMed detection complete: %d raw -> %d kept",
                detection_id, len(raw_entities), len(entities),
            )
            return entities
        except Exception as exc:  # pragma: no cover - defensive logging
            self.logger.error(
                "[%s] OpenMed detection failed: %s", detection_id, exc, exc_info=True
            )
            raise PIIDetectionError(f"OpenMed PII detection failed: {exc}") from exc

    def mask_pii(
        self, text: str, threshold: Optional[float] = None
    ) -> Tuple[str, List[PIIEntity]]:
        entities = self.detect_pii(text, threshold)
        masked = self._apply_masks(text, entities)
        return masked, entities

    # ------------------------------------------------------------------
    # openmed lifecycle
    # ------------------------------------------------------------------

    def _ensure_loaded(self) -> None:
        if self._loaded:
            return
        try:
            from openmed.torch.privacy_filter import PrivacyFilterTorchPipeline
        except ImportError as exc:  # pragma: no cover - import guard
            raise PIIDetectionError(
                "OpenMed detector requires the `openmed[hf]` package "
                "(pip install \"openmed[hf]\")"
            ) from exc

        try:
            import transformers
        except ImportError as exc:  # pragma: no cover - import guard
            raise PIIDetectionError(
                "OpenMed detector requires the `transformers` package (>= 5.0)"
            ) from exc

        major = int(transformers.__version__.split(".", 1)[0])
        if major < 5:
            raise PIIDetectionError(
                "OpenMed/privacy-filter-multilingual requires transformers >= 5.0 "
                f"(installed: {transformers.__version__}). Disable openmed_enabled "
                "or upgrade transformers to 5.x."
            )

        load_start = time.time()
        # Build the privacy-filter pipeline ONCE. PrivacyFilterTorchPipeline
        # loads the tokenizer, the 2.7 GB model, and wires the HF token-
        # classification pipeline with aggregation_strategy="simple" which
        # triggers the model's native BIOES Viterbi decoder.
        self._pipeline = PrivacyFilterTorchPipeline(
            self._model_id,
            device=self.device if self.device != "cpu" else None,
        )
        self._loaded = True
        self.logger.info(
            "OpenMed detector ready (model=%s, device=%s, load_time=%.2fs)",
            self._model_id, self._pipeline.device, time.time() - load_start,
        )

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    def _run_inference(self, text: str, threshold: float) -> List[Any]:
        """Call the cached pipeline and return raw entity dicts above threshold.

        The pipeline's output schema is the HF token-classification format
        (``entity_group``, ``score``, ``start``, ``end``, ``word``). The
        Viterbi decoder runs inside the model itself via the custom
        ``OpenAIPrivacyFilterForTokenClassification`` architecture, so no
        post-merging is needed here.
        """
        raw = self._pipeline(text)
        threshold_f = float(threshold)
        return [item for item in raw if float(item.get("score", 0.0)) >= threshold_f]

    # ------------------------------------------------------------------
    # Mapping + filtering
    # ------------------------------------------------------------------

    def _convert_to_pii_entities(
        self,
        entities: List[Any],
        text: str,
        label_mapping: Dict[str, str],
    ) -> List[PIIEntity]:
        """Convert pipeline dicts to ``PIIEntity``, filtering by label mapping.

        The pipeline emits HF-style dicts: ``entity_group`` (or ``entity``),
        ``score``, ``start``, ``end``, ``word``.
        """
        results: List[PIIEntity] = []
        for ent in entities:
            label = ent.get("entity_group") or ent.get("entity") or ""
            if not label or label == "O":
                continue
            pii_type = label_mapping.get(label)
            if not pii_type:
                # Label not in the DB mapping for OPENMED -> skip
                continue
            start = int(ent.get("start", 0) or 0)
            end = int(ent.get("end", 0) or 0)
            entity_text = (
                text[start:end] if 0 <= start < end <= len(text)
                else str(ent.get("word", "") or "")
            )
            score = float(ent.get("score", 0.0) or 0.0)
            results.append(
                PIIEntity(
                    text=entity_text,
                    pii_type=pii_type,
                    type_label=pii_type,
                    start=start,
                    end=end,
                    score=score,
                    source=DetectorSource.OPENMED,
                )
            )
        return results

    def _apply_per_type_thresholds(
        self, entities: List[PIIEntity], scoring_overrides: Dict[str, float]
    ) -> List[PIIEntity]:
        if not scoring_overrides:
            return entities
        kept: List[PIIEntity] = []
        for entity in entities:
            per_type = scoring_overrides.get(entity.pii_type)
            if per_type is not None and entity.score < per_type:
                continue
            kept.append(entity)
        return kept

    # ------------------------------------------------------------------
    # Configuration resolution
    # ------------------------------------------------------------------

    def _resolve_runtime_config(
        self, pii_type_configs: Optional[Dict]
    ) -> Tuple[Dict[str, str], Dict[str, float]]:
        configs = pii_type_configs or self._fetch_configs_from_db()
        if not configs:
            self.logger.warning(
                "No OPENMED pii_type configs available — detector will emit nothing"
            )
            return {}, {}

        label_mapping: Dict[str, str] = {}
        scoring_overrides: Dict[str, float] = {}

        for key, cfg in configs.items():
            detector_value = cfg.get("detector") if isinstance(cfg, dict) else None
            if isinstance(key, str) and key.startswith("OPENMED:"):
                pii_type = key.split(":", 1)[1]
            elif detector_value == "OPENMED":
                pii_type = cfg.get("pii_type") or (
                    key if isinstance(key, str) and ":" not in key else None
                )
            else:
                continue
            if not pii_type:
                continue
            if not cfg.get("enabled", False):
                continue
            detector_label = cfg.get("detector_label")
            if detector_label:
                label_mapping[detector_label] = pii_type
            threshold = cfg.get("threshold")
            if threshold is not None:
                scoring_overrides[pii_type] = float(threshold)

        return label_mapping, scoring_overrides

    def _fetch_configs_from_db(self) -> Optional[Dict]:
        try:
            from pii_detector.infrastructure.adapter.out.database_config_adapter import (
                get_database_config_adapter,
            )

            adapter = get_database_config_adapter()
            return adapter.fetch_pii_type_configs(detector="OPENMED")
        except Exception as exc:
            self.logger.warning(
                "Failed to fetch OPENMED pii_type configs from DB: %s", exc
            )
            return None

    # ------------------------------------------------------------------
    # Masking
    # ------------------------------------------------------------------

    @staticmethod
    def _apply_masks(text: str, entities: List[PIIEntity]) -> str:
        if not entities:
            return text
        sorted_entities = sorted(entities, key=lambda e: e.start)
        parts: List[str] = []
        last_pos = 0
        for entity in sorted_entities:
            if entity.start < last_pos:
                continue
            parts.append(text[last_pos: entity.start])
            parts.append(f"[{entity.pii_type}]")
            last_pos = entity.end
        parts.append(text[last_pos:])
        return "".join(parts)
