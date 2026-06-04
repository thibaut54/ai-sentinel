"""GLiNER2-based PII detector implementing ``PIIDetectorProtocol``.

GLiNER2 (lib ``gliner2``) is a multi-task evolution of GLiNER. Unlike GLiNER's
``predict_entities(text, labels)``, GLiNER2 takes a *schema* mapping each entity
label to a natural-language **description** (``{label: description}``) and runs
``model.extract(text, schema, threshold)``.

This is integrated as an additional **parallel** detection source (D2 — ensemble
with GLiNER, never a substitution) and is **disabled by default** (D4); the
``gliner2_enabled`` DB flag is the kill-switch.

POC L0 (2026-05-31) on ``fastino/gliner2-large-v1`` validated the raw output
contract used here:

    model.extract(text, schema, threshold,
                  format_results=False, include_spans=True,
                  include_confidence=True)
    -> [OrderedDict({label: [{"text", "confidence", "start", "end"}, ...], ...})]

with **character offsets** such that ``text[start:end] == entity_text`` (4/4 on
FR fixtures). Only the NER head feeds ``PIIEntity`` (MVP — classification and
structured heads are ignored, spec §4.3).

Long documents are chunked with :class:`GlinerSubwordChunker` (384/80, spec
§4.4); chunk-local offsets are rebased to the original text and ``(start, end,
pii_type)`` duplicates from overlaps are dropped.
"""
from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional, Tuple

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.exception.exceptions import ModelNotLoadedError, PIIDetectionError
from pii_detector.infrastructure.detector._gliner_common import (
    apply_entity_scoring_filter,
    apply_masks,
    build_pii_type_mapping_from_configs,
    build_scoring_overrides_from_configs,
    iterate_detector_configs,
)
from pii_detector.infrastructure.model_management.gliner2_model_manager import (
    Gliner2ModelManager,
)
from pii_detector.infrastructure.text_processing.semantic_chunker import (
    GlinerSubwordChunker,
)

GLINER2_DEFAULT_MODEL_ID = "fastino/gliner2-large-v1"
# Recalibrated for GLiNER2 (scores NOT comparable to GLiNER) — spec §4.6.
DEFAULT_GLOBAL_THRESHOLD = 0.50
# Chunking: smaller windows keep GLiNER2 recall high on dense PII spans; attention
# is quadratic so a 384-token window with 80-token overlap is the cost/coverage
# trade-off retained after the corpus-scan bench (spec §4.4).
CHUNK_SIZE_TOKENS = 384
CHUNK_OVERLAP_TOKENS = 80
# Provenance namespace used both for DB lookup and PIIEntity.source.
DETECTOR_NAMESPACE = "GLINER2"


class Gliner2Detector:
    """PII detector backed by a GLiNER2 model (``gliner2`` library).

    Implements ``PIIDetectorProtocol``: ``model_id``, ``detect_pii``,
    ``mask_pii``, ``download_model``, ``load_model``.
    """

    def __init__(self, config: Optional[DetectionConfig] = None):
        self.config = config or DetectionConfig(model_id=GLINER2_DEFAULT_MODEL_ID)
        self.device = self.config.device or "cpu"
        # Explicit None check so an intentionally configured 0.0 threshold is
        # preserved instead of being treated as falsy and overridden.
        self.threshold = (
            self.config.threshold
            if self.config.threshold is not None
            else DEFAULT_GLOBAL_THRESHOLD
        )

        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")
        self.model_manager = Gliner2ModelManager(self.config)
        self.model: Optional[Any] = None
        self.chunker: Optional[GlinerSubwordChunker] = None
        self.logger.info("GLiNER2 Detector initialized with device: %s", self.device)

    # ------------------------------------------------------------------
    # PIIDetectorProtocol
    # ------------------------------------------------------------------

    @property
    def model_id(self) -> str:
        return self.config.model_id or GLINER2_DEFAULT_MODEL_ID

    def download_model(self) -> None:
        self.model_manager.download_model()

    def load_model(self) -> None:
        self.model = self.model_manager.load_model()
        self._initialize_chunker()
        self.logger.info("GLiNER2 model loaded successfully")

    def detect_pii(
        self,
        text: str,
        threshold: Optional[float] = None,
        pii_type_configs: Optional[Dict] = None,
    ) -> List[PIIEntity]:
        if not text:
            return []
        if not self.model:
            raise ModelNotLoadedError("The GLiNER2 model must be loaded before use")

        effective_threshold = threshold if threshold is not None else self.threshold
        detection_id = f"gliner2_{int(time.time() * 1000) % 10000}"

        try:
            configs = pii_type_configs or self._fetch_configs_from_db()
            schema_dict, label_mapping, scoring_overrides = self._resolve_runtime_config(
                configs
            )
            if not schema_dict:
                self.logger.warning(
                    "[%s] No enabled GLINER2 pii_type configs — emitting nothing",
                    detection_id,
                )
                return []

            schema = self._build_schema(schema_dict)
            raw_entities = self._run_inference(text, schema, effective_threshold)
            entities = self._convert_to_pii_entities(raw_entities, text, label_mapping)
            entities = apply_entity_scoring_filter(entities, scoring_overrides)

            self.logger.info(
                "[%s] GLiNER2 detection complete: %d raw -> %d kept",
                detection_id, len(raw_entities), len(entities),
            )
            return entities
        except PIIDetectionError:
            raise
        except Exception as exc:
            self.logger.error(
                "[%s] GLiNER2 detection failed: %s", detection_id, exc, exc_info=True
            )
            raise PIIDetectionError(f"GLiNER2 PII detection failed: {exc}") from exc

    def mask_pii(
        self, text: str, threshold: Optional[float] = None
    ) -> Tuple[str, List[PIIEntity]]:
        entities = self.detect_pii(text, threshold)
        return apply_masks(text, entities), entities

    # ------------------------------------------------------------------
    # Lifecycle helpers
    # ------------------------------------------------------------------

    def _initialize_chunker(self) -> None:
        tokenizer = self.model_manager.get_tokenizer()
        self.chunker = GlinerSubwordChunker(
            tokenizer=tokenizer,
            chunk_size=CHUNK_SIZE_TOKENS,
            overlap=CHUNK_OVERLAP_TOKENS,
            logger=self.logger,
        )

    # ------------------------------------------------------------------
    # Schema + inference
    # ------------------------------------------------------------------

    def _build_schema(self, schema_dict: Dict[str, str]) -> Any:
        """Build a GLiNER2 schema from ``{detector_label: description}``."""
        return self.model.create_schema().entities(schema_dict)

    def _run_inference(self, text: str, schema: Any, threshold: float) -> List[Dict]:
        """Run ``extract`` per chunk, rebase offsets, dedup overlaps.

        Returns a flat list of ``{"label", "text", "score", "start", "end"}``
        dicts in ORIGINAL-text coordinates.
        """
        if self.chunker is None:
            raise RuntimeError("Chunker not initialized. Call load_model() first.")

        chunk_results = self.chunker.chunk_text(text)
        aggregated: List[Dict] = []
        seen: set = set()
        for chunk_result in chunk_results:
            raw = self.model.extract(
                chunk_result.text,
                schema,
                threshold=float(threshold),
                format_results=False,
                include_confidence=True,
                include_spans=True,
            )
            for span in self._iter_raw_spans(raw):
                rebased = self._rebase_span(span, chunk_result.start)
                key = (rebased["start"], rebased["end"], rebased["label"])
                if key in seen:
                    continue
                seen.add(key)
                aggregated.append(rebased)
        return aggregated

    @staticmethod
    def _iter_raw_spans(raw: Any) -> List[Dict]:
        """Flatten the GLiNER2 raw output into per-span dicts.

        Two raw shapes are accepted:
        - gliner2 (>=1.3): ``{"entities": [OrderedDict({label: [{"text",
          "confidence","start","end"}, ...]})]}`` — the result is wrapped in an
          ``"entities"`` envelope whose value is a *list* of per-text label maps.
        - POC L0 (legacy): a bare ``[OrderedDict({label: [...]})]`` with no
          envelope.

        Both are normalised by :meth:`_normalise_label_maps`. Handling the
        envelope is mandatory: against the installed 1.3.1 library the old
        parser saw a list under ``"entities"`` (not a dict) and silently emitted
        zero spans, zeroing out every GLiNER2 detection.
        """
        spans: List[Dict] = []
        for label_map in Gliner2Detector._normalise_label_maps(raw):
            for label, items in label_map.items():
                if not isinstance(items, list):
                    continue
                for item in items:
                    parsed = Gliner2Detector._parse_span_item(label, item)
                    if parsed is not None:
                        spans.append(parsed)
        return spans

    @staticmethod
    def _normalise_label_maps(raw: Any) -> List[Dict]:
        """Normalise GLiNER2 raw output into a list of ``{label: [span,...]}`` maps.

        Unwraps the ``{"entities": ...}`` envelope (gliner2 >=1.3) one level —
        its value may be a list of per-text label maps or a single label map —
        then keeps only the dict entries. A bare list / single dict (POC L0
        shape) passes through unchanged.
        """
        if isinstance(raw, dict) and "entities" in raw:
            raw = raw["entities"]
        candidates = raw if isinstance(raw, list) else [raw]
        return [candidate for candidate in candidates if isinstance(candidate, dict)]

    @staticmethod
    def _parse_span_item(label: str, item: Any) -> Optional[Dict]:
        """Normalise one GLiNER2 span (dict or 4-tuple) to a flat dict."""
        if isinstance(item, dict):
            start, end = item.get("start"), item.get("end")
            if start is None or end is None:
                return None
            return {
                "label": label,
                "text": item.get("text", ""),
                "score": float(item.get("confidence", item.get("score", 0.0)) or 0.0),
                "start": int(start),
                "end": int(end),
            }
        if isinstance(item, tuple) and len(item) == 4:
            text, conf, start, end = item
            return {
                "label": label,
                "text": text,
                "score": float(conf or 0.0),
                "start": int(start),
                "end": int(end),
            }
        return None

    @staticmethod
    def _rebase_span(span: Dict, chunk_offset: int) -> Dict:
        """Shift a chunk-local span to original-text coordinates."""
        rebased = dict(span)
        rebased["start"] = span["start"] + chunk_offset
        rebased["end"] = span["end"] + chunk_offset
        return rebased

    # ------------------------------------------------------------------
    # Mapping
    # ------------------------------------------------------------------

    def _convert_to_pii_entities(
        self, raw_entities: List[Dict], text: str, label_mapping: Dict[str, str]
    ) -> List[PIIEntity]:
        results: List[PIIEntity] = []
        for entity in raw_entities:
            label = entity.get("label", "")
            pii_type = label_mapping.get(label)
            if not pii_type:
                # Label emitted by the model but not in our GLINER2 mapping.
                continue
            start = int(entity.get("start", 0) or 0)
            end = int(entity.get("end", 0) or 0)
            entity_text = (
                text[start:end] if 0 <= start < end <= len(text)
                else str(entity.get("text", "") or "")
            )
            results.append(
                PIIEntity(
                    text=entity_text,
                    pii_type=pii_type,
                    type_label=pii_type,
                    start=start,
                    end=end,
                    score=float(entity.get("score", 0.0) or 0.0),
                    source=DetectorSource.GLINER2,
                )
            )
        return results

    # ------------------------------------------------------------------
    # Configuration resolution
    # ------------------------------------------------------------------

    def _resolve_runtime_config(
        self, pii_type_configs: Optional[Dict]
    ) -> Tuple[Dict[str, str], Dict[str, str], Dict[str, float]]:
        """Return ``(schema_dict, label_mapping, scoring_overrides)``.

        - ``schema_dict``: ``{detector_label: description}`` for GLiNER2 inference;
          NULL/empty description falls back to the label itself (spec §4.3) so the
          detector still works before the UI fills descriptions in.
        - ``label_mapping``: ``{detector_label: pii_type}`` (return mapping).
        - ``scoring_overrides``: ``{pii_type: threshold}`` per-type post-filter.
        """
        if not pii_type_configs:
            return {}, {}, {}

        schema_dict: Dict[str, str] = {}
        for _pii_type, config in iterate_detector_configs(
            pii_type_configs, DETECTOR_NAMESPACE
        ):
            if not config.get("enabled", False):
                continue
            label = config.get("detector_label")
            if not label:
                continue
            description = config.get("detector_description")
            schema_dict[label] = description if description else label

        label_mapping = build_pii_type_mapping_from_configs(
            pii_type_configs, DETECTOR_NAMESPACE
        )
        scoring_overrides = build_scoring_overrides_from_configs(
            pii_type_configs, DETECTOR_NAMESPACE
        )
        return schema_dict, label_mapping, scoring_overrides

    def _fetch_configs_from_db(self) -> Optional[Dict]:
        try:
            from pii_detector.infrastructure.adapter.out.database_config_adapter import (
                get_database_config_adapter,
            )

            adapter = get_database_config_adapter()
            return adapter.fetch_pii_type_configs(detector=DETECTOR_NAMESPACE)
        except Exception as exc:
            self.logger.warning(
                "Failed to fetch GLINER2 pii_type configs from DB: %s", exc
            )
            return None

    # Exposed for the gRPC masking path (composite calls ``_apply_masks``).
    @staticmethod
    def _apply_masks(text: str, entities: List[PIIEntity]) -> str:
        return apply_masks(text, entities)
