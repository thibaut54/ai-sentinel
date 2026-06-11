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

# Sliding-window chunking parameters used when the input exceeds
# ``CHUNK_TRIGGER_CHARS``. Tuned for CPU inference on
# OpenMed/privacy-filter-multilingual:
#   - effective banded attention = 257 tokens, so any window >> 257 captures
#     full local context; we use 1024.
#   - overlap of 256 tokens is well above the maximum PII span length (a few
#     dozen tokens), and matches the band radius so any token near a chunk
#     edge in window N has a non-edge position in window N+1.
#   - 'simple' aggregation already runs the model's Viterbi BIOES decoder
#     per chunk; we only need to merge spans across overlaps in Python.
CHUNK_WINDOW_TOKENS = 1024
CHUNK_OVERLAP_TOKENS = 256
CHUNK_TRIGGER_CHARS = 4000


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
            label_mapping, scoring_overrides, type_labels = self._resolve_runtime_config(pii_type_configs)
            self.logger.debug(
                "[%s] OpenMed runtime config: %d labels mapped, %d thresholds",
                detection_id, len(label_mapping), len(scoring_overrides),
            )

            raw_entities = self._run_inference(text, effective_threshold)
            entities = self._convert_to_pii_entities(
                raw_entities, text, label_mapping, type_labels
            )
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
        ``OpenAIPrivacyFilterForTokenClassification`` architecture.

        For texts above ``CHUNK_TRIGGER_CHARS`` we switch to sliding-window
        chunking: the model's 128k native context is fine functionally, but a
        single 25k-token forward pass on CPU is prohibitively slow and risks
        request-level timeouts. Chunking trades a small post-merge cost for
        bounded per-call latency.
        """
        threshold_f = float(threshold)
        if len(text) <= CHUNK_TRIGGER_CHARS:
            raw_pre_merge = self._pipeline(text)
        else:
            raw_pre_merge = self._run_inference_chunked(text)
        # Recoalesce adjacent BIOES fragments the openmed pipeline emits
        # as separate entities (e.g. "Tr0ub4dor!202" + "4" -> "Tr0ub4dor!2024").
        # Documented in doc/openmed-integration-spec.md §4.3, diagnosed
        # empirically on 2026-05-25 via OPENMED_RAW_DUMP — cf. lessons.md
        # entry DIAGNOSE_ML_PIPELINE_BUGS_EMPIRICALLY_BEFORE_PROPOSING_FIXES.
        raw = self._merge_adjacent_fragments(raw_pre_merge, text)
        # Diagnostic dump of pre/post-merge pipeline output BEFORE threshold
        # filtering. Gated by env var so it never fires in prod. Used to
        # validate the merge fix and to investigate future fragmentation
        # regressions. Activate with: OPENMED_RAW_DUMP=1
        import os
        if os.environ.get("OPENMED_RAW_DUMP"):
            self.logger.warning(
                "[RAW_DUMP] text_len=%d threshold=%.2f pre_merge=%d "
                "post_merge=%d text_preview=%r",
                len(text), threshold_f, len(raw_pre_merge), len(raw),
                text[:120],
            )
            for i, item in enumerate(raw):
                start = int(item.get("start", 0) or 0)
                end = int(item.get("end", 0) or 0)
                slice_ = text[start:end] if 0 <= start < end <= len(text) else "<OOB>"
                self.logger.warning(
                    "[RAW_DUMP]   #%02d label=%s start=%d end=%d "
                    "score=%.3f word=%r text_slice=%r",
                    i,
                    item.get("entity_group") or item.get("entity"),
                    start, end,
                    float(item.get("score", 0.0) or 0.0),
                    item.get("word"),
                    slice_,
                )
        return [item for item in raw if float(item.get("score", 0.0)) >= threshold_f]

    def _run_inference_chunked(self, text: str) -> List[Any]:
        """Run the pipeline on sliding token-aligned windows and merge spans.

        Strategy (see Privacy Filter model card §2 banded attention 257):
          1. Tokenize once with ``return_offsets_mapping`` to know each
             token's character span in the original text.
          2. Walk the token stream with window ``CHUNK_WINDOW_TOKENS`` and
             stride ``CHUNK_WINDOW_TOKENS - CHUNK_OVERLAP_TOKENS``.
          3. For each window, decode the corresponding substring with the HF
             pipeline (which runs the model's Viterbi BIOES decoder).
          4. The pipeline returns offsets *relative to that substring*; we
             rebase to global character offsets.
          5. Merge across overlaps: drop spans that are exact duplicates
             (same global ``(start, end, label)``); for overlapping spans
             with the same label, keep the one with the highest score.
        """
        tokenizer = self._pipeline.tokenizer
        enc = tokenizer(
            text,
            return_offsets_mapping=True,
            add_special_tokens=False,
            truncation=False,
        )
        offsets = enc["offset_mapping"]
        total_tokens = len(offsets)
        if total_tokens == 0:
            return []

        stride = max(1, CHUNK_WINDOW_TOKENS - CHUNK_OVERLAP_TOKENS)
        all_raw: List[Dict[str, Any]] = []
        for token_start in range(0, total_tokens, stride):
            token_end = min(token_start + CHUNK_WINDOW_TOKENS, total_tokens)
            # Char-level slice aligned on subword boundaries
            char_start = offsets[token_start][0]
            char_end = offsets[token_end - 1][1]
            if char_end <= char_start:
                continue
            chunk_text = text[char_start:char_end]
            chunk_raw = self._pipeline(chunk_text)
            for ent in chunk_raw:
                rebased = dict(ent)
                rebased["start"] = int(ent.get("start", 0) or 0) + char_start
                rebased["end"] = int(ent.get("end", 0) or 0) + char_start
                # 'word' from chunk-local view stays valid since we just shifted offsets
                all_raw.append(rebased)
            if token_end >= total_tokens:
                break

        return self._merge_overlapping_spans(all_raw)

    @staticmethod
    def _merge_overlapping_spans(spans: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Deduplicate spans produced by overlapping chunks.

        Two spans are considered duplicates when they share the same label
        (``entity_group``) and overlap on character offsets; we keep the one
        with the highest score and the widest span.
        """
        if not spans:
            return []
        # Sort by start ASC, then by score DESC so the strongest span wins
        # when we hit overlapping candidates with the same label.
        spans_sorted = sorted(
            spans,
            key=lambda s: (
                int(s.get("start", 0) or 0),
                -float(s.get("score", 0.0) or 0.0),
            ),
        )
        merged: List[Dict[str, Any]] = []
        for ent in spans_sorted:
            if not OpenMedDetector._absorb_into_overlapping(merged, ent):
                merged.append(ent)
        return merged

    @staticmethod
    def _absorb_into_overlapping(
        merged: List[Dict[str, Any]], ent: Dict[str, Any]
    ) -> bool:
        """Try to merge ``ent`` into a same-label overlapping span in ``merged``.

        Returns ``True`` when ``ent`` overlaps an existing span (and replaces
        it in place if ``ent`` is wider / higher-scored), ``False`` when it has
        no overlap and must be appended by the caller.
        """
        label = ent.get("entity_group") or ent.get("entity") or ""
        start = int(ent.get("start", 0) or 0)
        end = int(ent.get("end", 0) or 0)
        score = float(ent.get("score", 0.0) or 0.0)
        for idx, kept in enumerate(merged):
            if not OpenMedDetector._overlaps_same_label(kept, label, start, end):
                continue
            kept_score = float(kept.get("score", 0.0) or 0.0)
            kept_width = int(kept.get("end", 0) or 0) - int(kept.get("start", 0) or 0)
            # Prefer wider span; tie-break on score.
            if (end - start, score) > (kept_width, kept_score):
                merged[idx] = ent
            return True
        return False

    @staticmethod
    def _overlaps_same_label(
        kept: Dict[str, Any], label: str, start: int, end: int
    ) -> bool:
        """Return ``True`` when ``kept`` shares ``label`` and overlaps ``[start, end)``.

        Touching spans (``start == kept_end`` or ``end == kept_start``) count as
        adjacent, not overlapping.
        """
        kept_label = kept.get("entity_group") or kept.get("entity") or ""
        if kept_label != label:
            return False
        kept_start = int(kept.get("start", 0) or 0)
        kept_end = int(kept.get("end", 0) or 0)
        return start < kept_end and end > kept_start

    @staticmethod
    def _merge_adjacent_fragments(
        entities: List[Dict[str, Any]],
        text: str,
        max_gap_chars: int = 2,
    ) -> List[Dict[str, Any]]:
        """Recoalesce sub-token BPE fragments that the openmed pipeline emits
        as separate entities.

        Empirically (RAW_DUMP diagnostic of 2026-05-25), the pipeline returns
        BIOES boundary-tagged tokens as **separate** entities instead of a
        single coalesced span. For example, on ``"Mot de passe utilisateur : Tr0ub4dor!2024"``
        the pipeline emits two entities:

        * ``#00 PASSWORD (27, 40) "Tr0ub4dor!202"``
        * ``#01 PASSWORD (40, 41) "4"``

        Both touch (gap = 0) and share the same ``entity_group``. Downstream
        IoU matching at 0.5 then fails on the second fragment, inflating the
        FP rate. This is the bug documented in
        ``doc/openmed-integration-spec.md`` §4.3 (sub-token reconstruction)
        but never implemented until now.

        Merge predicate (intentionally tight to avoid silently chaining
        unrelated spans):

        * same ``entity_group`` / ``entity``
        * gap (= next.start - prev.end) in [0, ``max_gap_chars``]
        * the gap text contains no line break (``\\n``, ``\\r``) — that's a
          structural boundary that should NOT be crossed
        * gap text may contain a single short separator like ``" "``,
          ``"-"``, ``"."``, ``"_"`` (filled by tolerating up to
          ``max_gap_chars`` arbitrary chars)

        The merged entity inherits the first fragment's start, the last
        fragment's end, and the max score across fragments. The ``word``
        field becomes the text slice, which is the only ground-truth
        representation of the merged span.
        """
        if not entities:
            return []
        sorted_e = sorted(entities, key=lambda e: int(e.get("start", 0) or 0))
        merged: List[Dict[str, Any]] = [dict(sorted_e[0])]
        for ent in sorted_e[1:]:
            last = merged[-1]
            if OpenMedDetector._is_mergeable_fragment(last, ent, text, max_gap_chars):
                OpenMedDetector._extend_fragment(last, ent, text)
            else:
                merged.append(dict(ent))
        return merged

    @staticmethod
    def _is_mergeable_fragment(
        last: Dict[str, Any],
        ent: Dict[str, Any],
        text: str,
        max_gap_chars: int,
    ) -> bool:
        """Return ``True`` when ``ent`` is a same-label adjacent fragment of ``last``.

        The gap (``ent.start - last.end``) must lie in ``[0, max_gap_chars]``
        and contain no line break, which would be a structural boundary.
        """
        last_label = last.get("entity_group") or last.get("entity") or ""
        ent_label = ent.get("entity_group") or ent.get("entity") or ""
        if last_label != ent_label:
            return False
        ent_start = int(ent.get("start", 0) or 0)
        last_end = int(last.get("end", 0) or 0)
        gap = ent_start - last_end
        if gap < 0 or gap > max_gap_chars:
            return False
        gap_text = (
            text[last_end:ent_start]
            if 0 <= last_end <= ent_start <= len(text) else ""
        )
        return "\n" not in gap_text and "\r" not in gap_text

    @staticmethod
    def _extend_fragment(
        last: Dict[str, Any], ent: Dict[str, Any], text: str
    ) -> None:
        """Extend ``last`` in place to cover ``ent`` (end, max score, word)."""
        new_end = int(ent.get("end", 0) or 0)
        last["end"] = new_end
        last["score"] = max(
            float(last.get("score", 0.0) or 0.0),
            float(ent.get("score", 0.0) or 0.0),
        )
        # Refresh the word field to the actual merged text slice (the
        # original fragments' ``word`` no longer represents the span).
        last_start = int(last.get("start", 0) or 0)
        if 0 <= last_start < new_end <= len(text):
            last["word"] = text[last_start:new_end]

    # ------------------------------------------------------------------
    # Mapping + filtering
    # ------------------------------------------------------------------

    def _convert_to_pii_entities(
        self,
        entities: List[Any],
        text: str,
        label_mapping: Dict[str, str],
        type_labels: Optional[Dict[str, str]] = None,
    ) -> List[PIIEntity]:
        """Convert pipeline dicts to ``PIIEntity``, filtering by label mapping.

        The pipeline emits HF-style dicts: ``entity_group`` (or ``entity``),
        ``score``, ``start``, ``end``, ``word``.

        ``type_labels`` maps a canonical ``pii_type`` to the human-readable
        label sent to the LLM judge (``PIIEntity.type_label``). It lets a type
        carry a descriptive semantic signal (e.g. ACCOUNT_NAME -> "reference
        client") instead of the raw enum name. Falls back to ``pii_type``.
        """
        type_labels = type_labels or {}
        results: List[PIIEntity] = []
        unmapped: Dict[str, List[str]] = {}
        for ent in entities:
            label = ent.get("entity_group") or ent.get("entity") or ""
            if not label or label == "O":
                continue
            pii_type = label_mapping.get(label)
            if not pii_type:
                # Label not in the DB mapping for OPENMED -> skip.
                # Diagnostic: the model DID detect an entity, but under a label
                # our OPENMED mapping doesn't know (case/format mismatch in
                # data.sql detector_label, or a label outside our config).
                # Collect here, log once per call below.
                unmapped.setdefault(label, []).append(
                    self._entity_text_slice(ent, text)
                )
                continue
            results.append(
                self._build_pii_entity(ent, text, pii_type, type_labels)
            )
        if unmapped:
            self.logger.warning(
                "[label-miss] OpenMed emitted %d entit(ies) with label(s) NOT in "
                "the OPENMED mapping (silently dropped). Mapping keys=%s | "
                "Unmapped labels -> sample values: %s",
                sum(len(v) for v in unmapped.values()),
                sorted(label_mapping.keys()),
                {lbl: vals[:3] for lbl, vals in unmapped.items()},
            )
        return results

    @staticmethod
    def _entity_text_slice(ent: Dict[str, Any], text: str) -> str:
        """Return the original text slice for a raw entity, falling back on ``word``."""
        start = int(ent.get("start", 0) or 0)
        end = int(ent.get("end", 0) or 0)
        if 0 <= start < end <= len(text):
            return text[start:end]
        return str(ent.get("word", "") or "")

    @staticmethod
    def _build_pii_entity(
        ent: Dict[str, Any],
        text: str,
        pii_type: str,
        type_labels: Dict[str, str],
    ) -> PIIEntity:
        """Build a :class:`PIIEntity` from a mapped raw OpenMed entity dict."""
        start = int(ent.get("start", 0) or 0)
        end = int(ent.get("end", 0) or 0)
        return PIIEntity(
            text=OpenMedDetector._entity_text_slice(ent, text),
            pii_type=pii_type,
            type_label=type_labels.get(pii_type, pii_type),
            start=start,
            end=end,
            score=float(ent.get("score", 0.0) or 0.0),
            source=DetectorSource.OPENMED,
        )

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
    ) -> Tuple[Dict[str, str], Dict[str, float], Dict[str, str]]:
        configs = pii_type_configs or self._fetch_configs_from_db()
        if not configs:
            self.logger.warning(
                "No OPENMED pii_type configs available — detector will emit nothing"
            )
            return {}, {}, {}

        label_mapping: Dict[str, str] = {}
        scoring_overrides: Dict[str, float] = {}
        type_labels: Dict[str, str] = {}

        for key, cfg in configs.items():
            pii_type = self._resolve_pii_type_for_entry(key, cfg)
            if not pii_type or not cfg.get("enabled", False):
                continue
            self._fill_runtime_dicts(
                pii_type, cfg, label_mapping, scoring_overrides, type_labels
            )

        return label_mapping, scoring_overrides, type_labels

    @staticmethod
    def _resolve_pii_type_for_entry(key: Any, cfg: Any) -> Optional[str]:
        """Resolve the canonical ``pii_type`` for one config entry.

        Accepts both the ``OPENMED:<type>`` keyed shape and the flat shape
        where the row carries ``detector == "OPENMED"``. Returns ``None`` when
        the entry is not an OPENMED config (caller skips it).
        """
        if isinstance(key, str) and key.startswith("OPENMED:"):
            return key.split(":", 1)[1]
        detector_value = cfg.get("detector") if isinstance(cfg, dict) else None
        if detector_value == "OPENMED":
            return cfg.get("pii_type") or (
                key if isinstance(key, str) and ":" not in key else None
            )
        return None

    @staticmethod
    def _fill_runtime_dicts(
        pii_type: str,
        cfg: Dict[str, Any],
        label_mapping: Dict[str, str],
        scoring_overrides: Dict[str, float],
        type_labels: Dict[str, str],
    ) -> None:
        """Populate the label / threshold / type_label maps for one entry."""
        detector_label = cfg.get("detector_label")
        if detector_label:
            label_mapping[detector_label] = pii_type
        threshold = cfg.get("threshold")
        if threshold is not None:
            scoring_overrides[pii_type] = float(threshold)
        # Optional human-readable label forwarded to the LLM judge. When
        # absent, _convert_to_pii_entities falls back to the pii_type.
        type_label = cfg.get("type_label")
        if type_label:
            type_labels[pii_type] = str(type_label)

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
