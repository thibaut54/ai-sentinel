"""
Composite PII detector orchestrating ML-based and regex-based detection.

This module provides CompositePIIDetector that combines:
- MultiModelPIIDetector: Orchestrates multiple ML models (GLiNER, Piiranha, etc.)
- RegexDetector: Deterministic pattern matching for structured PII

Business value:
- Leverages strengths of both ML (contextual understanding) and regex (precision)
- Configurable fusion strategies for optimal accuracy
- Extensible architecture for adding new detector types
"""

from __future__ import annotations

import logging
import time
from typing import Dict, List, Optional, Tuple

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.port.pii_detector_protocol import PIIDetectorProtocol
from pii_detector.domain.service.detection_merger import DetectionMerger
from pii_detector.infrastructure.detector.regex_detector import RegexDetector

try:
    from pii_detector.infrastructure.detector.presidio_detector import PresidioDetector
    PRESIDIO_AVAILABLE = True
except ImportError as e:
    PRESIDIO_AVAILABLE = False
    PresidioDetector = None


logger = logging.getLogger(__name__)


class CompositePIIDetector:
    """
    Composite detector orchestrating ML and regex-based PII detection.
    
    This detector implements a hybrid approach that combines:
    1. ML-based detection (via MultiModelPIIDetector) for contextual PII
    2. Regex-based detection for structured, deterministic patterns
    
    Business rules:
    - ML detectors run in parallel via MultiModelPIIDetector
    - Regex detector runs independently for fast pattern matching
    - Results are merged using configurable fusion strategies
    - Overlaps are resolved based on detector priorities
    
    Architecture:
    - Implements PIIDetectorProtocol for transparent integration
    - Delegates ML orchestration to MultiModelPIIDetector
    - Uses DetectionMerger for result fusion
    """
    
    def __init__(
        self,
        ml_detector: Optional[PIIDetectorProtocol] = None,
        regex_detector: Optional[RegexDetector] = None,
        presidio_detector: Optional[PresidioDetector] = None,
        openmed_detector: Optional[PIIDetectorProtocol] = None,
        gliner2_detector: Optional[PIIDetectorProtocol] = None,
        ministral_detector: Optional[PIIDetectorProtocol] = None,
        merger: Optional[DetectionMerger] = None,
        enable_regex: bool = True,
        enable_presidio: bool = True,
        enable_openmed: bool = False,
        enable_gliner2: bool = False,
        enable_ministral: bool = False
    ):
        """
        Initialize composite detector.

        Args:
            ml_detector: ML-based detector (MultiModelPIIDetector or single detector). Can be None if only Presidio/Regex is used.
            regex_detector: Regex-based detector
            presidio_detector: Presidio-based detector
            openmed_detector: OpenMed Privacy Filter Multilingual detector (optional, opt-in)
            gliner2_detector: GLiNER2 detector (optional, opt-in ensemble source)
            ministral_detector: Ministral-PII LLM detector (optional, opt-in ensemble source)
            merger: Detection merger for result fusion
            enable_regex: Enable regex detection (default: True)
            enable_presidio: Enable Presidio detection (default: True)
            enable_openmed: Enable OpenMed detection (default: False, opt-in per DB flag)
            enable_gliner2: Enable GLiNER2 detection (default: False, opt-in per DB flag)
            enable_ministral: Enable Ministral-PII detection (default: False, opt-in per DB flag)
        """
        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")

        # Initialize ML detector
        self.ml_detector = ml_detector
        if self.ml_detector is None:
            self.logger.info("No ML detector provided - using rule-based detection only (Presidio/Regex)")

        # Initialize regex detector if enabled
        self.regex_detector = self._init_regex_detector(regex_detector, enable_regex)
        # Set enable_regex based on actual availability
        self.enable_regex = enable_regex and self.regex_detector is not None

        # Initialize Presidio detector if enabled
        self.presidio_detector, self.enable_presidio = self._init_presidio_detector(
            presidio_detector, enable_presidio
        )

        # Initialize OpenMed detector slot (opt-in)
        self.openmed_detector = openmed_detector
        self.enable_openmed = enable_openmed and self.openmed_detector is not None

        # Initialize GLiNER2 detector slot (opt-in, ensemble source — never a
        # substitution of GLiNER; disabled by default per spec D4)
        self.gliner2_detector = gliner2_detector
        self.enable_gliner2 = enable_gliner2 and self.gliner2_detector is not None

        # Initialize Ministral-PII detector slot (opt-in, ensemble source —
        # specialised LLM extractor, disabled by default per DB flag)
        self.ministral_detector = ministral_detector
        self.enable_ministral = enable_ministral and self.ministral_detector is not None

        # Initialize merger
        self._merger = merger or DetectionMerger(log_provenance=True)

        self.logger.info(
            "CompositePIIDetector initialized: "
            f"ML={'enabled' if ml_detector else 'disabled'}, "
            f"Regex={'enabled' if self.enable_regex else 'disabled'}, "
            f"Presidio={'enabled' if self.enable_presidio else 'disabled'}, "
            f"OpenMed={'enabled' if self.enable_openmed else 'disabled'}, "
            f"GLiNER2={'enabled' if self.enable_gliner2 else 'disabled'}, "
            f"Ministral={'enabled' if self.enable_ministral else 'disabled'}"
        )
    
    def _init_regex_detector(
        self,
        regex_detector: Optional[RegexDetector],
        enable_regex: bool,
    ) -> Optional[RegexDetector]:
        """Return the regex detector, building a default one when enabled."""
        if regex_detector is not None or not enable_regex:
            return regex_detector
        try:
            detector = RegexDetector()
            self.logger.info("RegexDetector initialized successfully")
            return detector
        except Exception as e:
            self.logger.warning(f"Failed to initialize RegexDetector: {e}")
            return None

    def _init_presidio_detector(
        self,
        presidio_detector: Optional[PresidioDetector],
        enable_presidio: bool,
    ) -> Tuple[Optional[PresidioDetector], bool]:
        """Return ``(detector, enabled)``, building a default when enabled."""
        enabled = enable_presidio and PRESIDIO_AVAILABLE
        if presidio_detector is not None or not enabled:
            return presidio_detector, enabled
        try:
            detector = PresidioDetector()
            self.logger.info("PresidioDetector initialized successfully")
            return detector, True
        except Exception as e:
            self.logger.warning(f"Failed to initialize PresidioDetector: {e}")
            return None, False

    @property
    def model_id(self) -> str:
        """Get composite model identifier."""
        if self.ml_detector:
            return f"composite-{self.ml_detector.model_id}"
        return "composite-regex-only"
    
    def download_model(self) -> None:
        """Download models for all underlying detectors."""
        if self.ml_detector:
            try:
                self.ml_detector.download_model()
            except Exception as e:
                self.logger.warning(f"ML detector download failed: {e}")

        if self.regex_detector:
            self.regex_detector.download_model()  # No-op for regex

        if self.presidio_detector:
            # Presidio doesn't need download
            pass

        if self.openmed_detector and self.enable_openmed:
            try:
                self.openmed_detector.download_model()
            except Exception as e:
                self.logger.warning(f"OpenMed detector download failed: {e}")

        if self.gliner2_detector and self.enable_gliner2:
            try:
                self.gliner2_detector.download_model()
            except Exception as e:
                self.logger.warning(f"GLiNER2 detector download failed: {e}")

        if self.ministral_detector and self.enable_ministral:
            try:
                self.ministral_detector.download_model()  # No-op for remote endpoint
            except Exception as e:
                self.logger.warning(f"Ministral detector download failed: {e}")

    def load_model(self) -> None:
        """Load models for all underlying detectors."""
        if self.ml_detector:
            try:
                self.ml_detector.load_model()
            except Exception as e:
                self.logger.error(f"ML detector load failed: {e}")
                raise

        if self.regex_detector:
            self.regex_detector.load_model()  # No-op for regex

        if self.presidio_detector:
            # Presidio models are loaded on first use
            pass

        if self.openmed_detector and self.enable_openmed:
            try:
                self.openmed_detector.load_model()
            except Exception as e:
                # Do not block other detectors if OpenMed fails to load
                # (e.g. transformers < 5 installed). It will be skipped at request time.
                self.logger.error(f"OpenMed detector load failed: {e}")
                self.enable_openmed = False

        if self.gliner2_detector and self.enable_gliner2:
            try:
                self.gliner2_detector.load_model()
            except Exception as e:
                # Do not block other detectors if GLiNER2 fails to load
                # (e.g. gliner2 lib absent). It will be skipped at request time.
                self.logger.error(f"GLiNER2 detector load failed: {e}")
                self.enable_gliner2 = False

        if self.ministral_detector and self.enable_ministral:
            try:
                self.ministral_detector.load_model()  # No-op for remote endpoint
            except Exception as e:
                # Do not block other detectors if Ministral fails to load.
                # It will be skipped at request time.
                self.logger.error(f"Ministral detector load failed: {e}")
                self.enable_ministral = False

    def detect_pii(
        self,
        text: str,
        threshold: Optional[float] = None,
        enable_ml: Optional[bool] = None,
        enable_regex: Optional[bool] = None,
        enable_presidio: Optional[bool] = None,
        enable_openmed: Optional[bool] = None,
        enable_gliner2: Optional[bool] = None,
        enable_ministral: Optional[bool] = None,
        pii_type_configs: Optional[dict] = None,
        chunk_size: Optional[int] = None,
        ministral_chunk_size: Optional[int] = None,
        ministral_overlap: Optional[int] = None
    ) -> List[PIIEntity]:
        """
        Detect PII using both ML and regex detectors.
        
        Business process:
        1. Execute ML detection (if enabled)
        2. Execute regex detection (if enabled)
        3. Execute Presidio detection (if enabled)
        4. Merge results with priority-based fusion
        5. Return deduplicated entities
        
        Business rule: Dynamic configuration allows runtime control of detectors
        based on database configuration without service restart. Detectors remain
        loaded in memory for performance but can be selectively activated per request.
        
        Args:
            text: Text to analyze
            threshold: Optional confidence threshold
            enable_ml: Override ML detector activation (None=use default, True/False=force)
            enable_regex: Override regex detector activation (None=use default, True/False=force)
            enable_presidio: Override Presidio detector activation (None=use default, True/False=force)
            pii_type_configs: Optional fresh PII type configurations from database
            
        Returns:
            Merged list of PII entities
        """
        entities, _stats = self.detect_pii_with_stats(
            text, threshold, enable_ml, enable_regex, enable_presidio,
            enable_openmed, enable_gliner2, enable_ministral, pii_type_configs, chunk_size,
            ministral_chunk_size, ministral_overlap,
        )
        return entities

    def detect_pii_with_stats(
        self,
        text: str,
        threshold: Optional[float] = None,
        enable_ml: Optional[bool] = None,
        enable_regex: Optional[bool] = None,
        enable_presidio: Optional[bool] = None,
        enable_openmed: Optional[bool] = None,
        enable_gliner2: Optional[bool] = None,
        enable_ministral: Optional[bool] = None,
        pii_type_configs: Optional[dict] = None,
        chunk_size: Optional[int] = None,
        ministral_chunk_size: Optional[int] = None,
        ministral_overlap: Optional[int] = None,
    ) -> Tuple[List[PIIEntity], List[Dict]]:
        """Detect PII and return per-detector execution stats alongside results.

        Same contract as :meth:`detect_pii` but additionally returns one stats
        entry per detector that actually ran (even with 0 detections). Each
        stats entry is a plain dict (picklable across the worker-pool boundary)::

            {"source": DetectorSource, "duration_ms": int, "entities_found": int}

        Stats are returned by value (never stored on the instance) because the
        composite is a singleton shared across concurrent gRPC worker threads;
        per-instance mutable state would be a race condition.

        Returns:
            Tuple of (merged_entities, detector_stats).
        """
        if not text:
            return [], []

        use_ml, use_regex, use_presidio, use_openmed, use_gliner2, use_ministral = self._resolve_detector_flags(
            enable_ml, enable_regex, enable_presidio, enable_openmed, enable_gliner2, enable_ministral
        )
        self._log_active_detectors(
            use_ml, use_regex, use_presidio, use_openmed, use_gliner2, use_ministral
        )

        results_per_detector, stats = self._collect_detection_results(
            text, threshold, use_ml, use_regex, use_presidio, use_openmed, use_gliner2,
            use_ministral, pii_type_configs, chunk_size, ministral_chunk_size, ministral_overlap
        )

        if not results_per_detector:
            self.logger.warning("No detectors available")
            return [], stats

        merged_entities = self._merger.merge(results_per_detector)
        self._log_detection_summary(results_per_detector, merged_entities)
        return merged_entities, stats
    
    def _resolve_detector_flags(
        self,
        enable_ml: Optional[bool],
        enable_regex: Optional[bool],
        enable_presidio: Optional[bool],
        enable_openmed: Optional[bool] = None,
        enable_gliner2: Optional[bool] = None,
        enable_ministral: Optional[bool] = None,
    ) -> Tuple[bool, bool, bool, bool, bool, bool]:
        """Resolve runtime overrides into concrete detector activation flags."""
        use_ml = enable_ml if enable_ml is not None else (self.ml_detector is not None)
        use_regex = enable_regex if enable_regex is not None else self.enable_regex
        use_presidio = enable_presidio if enable_presidio is not None else self.enable_presidio
        use_openmed = enable_openmed if enable_openmed is not None else self.enable_openmed
        use_gliner2 = enable_gliner2 if enable_gliner2 is not None else self.enable_gliner2
        use_ministral = enable_ministral if enable_ministral is not None else self.enable_ministral
        return use_ml, use_regex, use_presidio, use_openmed, use_gliner2, use_ministral

    def _log_active_detectors(
        self, use_ml: bool, use_regex: bool, use_presidio: bool,
        use_openmed: bool = False, use_gliner2: bool = False,
        use_ministral: bool = False
    ) -> None:
        """Log which detectors are active for debugging."""
        if not self.logger.isEnabledFor(logging.DEBUG):
            return
        names = [
            n for flag, n in [
                (use_ml, "ML"),
                (use_regex, "Regex"),
                (use_presidio, "Presidio"),
                (use_openmed, "OpenMed"),
                (use_gliner2, "GLiNER2"),
                (use_ministral, "Ministral"),
            ] if flag
        ]
        self.logger.debug("Detecting PII with active detectors: %s", ', '.join(names) or 'NONE')

    def _collect_detection_results(
        self,
        text: str,
        threshold: Optional[float],
        use_ml: bool,
        use_regex: bool,
        use_presidio: bool,
        use_openmed: bool,
        use_gliner2: bool,
        use_ministral: bool,
        pii_type_configs: Optional[dict],
        chunk_size: Optional[int],
        ministral_chunk_size: Optional[int] = None,
        ministral_overlap: Optional[int] = None,
    ) -> Tuple[List[Tuple[PIIDetectorProtocol, List[PIIEntity]]], List[Dict]]:
        """Run each enabled detector, collect results and per-detector stats.

        Detectors run sequentially here, so the wall-clock measured around each
        ``_run_*_detection`` call is the real busy time of that detector for
        this request. Returns the (detector, entities) tuples used by the merger
        and a parallel list of stats dicts (one per detector that actually ran).
        """
        results: List[Tuple[PIIDetectorProtocol, List[PIIEntity]]] = []
        stats: List[Dict] = []

        def _run(detector: PIIDetectorProtocol, source: DetectorSource, fn) -> None:
            started = time.perf_counter()
            entities = fn()
            duration_ms = int((time.perf_counter() - started) * 1000)
            stats.append({
                "source": source,
                "duration_ms": duration_ms,
                "entities_found": len(entities),
            })
            results.append((detector, entities))

        if use_ml and self.ml_detector:
            _run(self.ml_detector, DetectorSource.GLINER,
                 lambda: self._run_ml_detection(text, threshold, pii_type_configs, chunk_size))
        if use_regex and self.regex_detector:
            _run(self.regex_detector, DetectorSource.REGEX,
                 lambda: self._run_regex_detection(text, threshold))
        if use_presidio and self.presidio_detector:
            _run(self.presidio_detector, DetectorSource.PRESIDIO,
                 lambda: self._run_presidio_detection(text, threshold))
        if use_openmed and self.openmed_detector:
            _run(self.openmed_detector, DetectorSource.OPENMED,
                 lambda: self._run_openmed_detection(text, threshold, pii_type_configs))
        if use_gliner2 and self.gliner2_detector:
            _run(self.gliner2_detector, DetectorSource.GLINER2,
                 lambda: self._run_gliner2_detection(text, threshold, pii_type_configs))
        if use_ministral and self.ministral_detector:
            _run(self.ministral_detector, DetectorSource.MINISTRAL,
                 lambda: self._run_ministral_detection(
                     text, threshold, pii_type_configs, ministral_chunk_size, ministral_overlap))
        return results, stats

    def _log_detection_summary(
        self,
        results_per_detector: List[Tuple[PIIDetectorProtocol, List[PIIEntity]]],
        merged_entities: List[PIIEntity],
    ) -> None:
        """Log per-detector entity counts after merge."""
        self._log_per_detector_counts(results_per_detector, merged_entities)
        self._log_parity_debug(results_per_detector, merged_entities)
        self._log_finding_tracker(results_per_detector, merged_entities)

    def _resolve_detector_label(self, detector: PIIDetectorProtocol) -> str:
        """Return a stable, human-readable label for a detector instance."""
        if detector is self.ml_detector:
            return "ML"
        if detector is self.regex_detector:
            return "Regex"
        if detector is self.presidio_detector:
            return "Presidio"
        if detector is self.openmed_detector:
            return "OpenMed"
        if detector is self.gliner2_detector:
            return "GLiNER2"
        if detector is self.ministral_detector:
            return "Ministral"
        return "Unknown"

    def _log_per_detector_counts(
        self,
        results_per_detector: List[Tuple[PIIDetectorProtocol, List[PIIEntity]]],
        merged_entities: List[PIIEntity],
    ) -> None:
        counts = {
            self.ml_detector: 0,
            self.regex_detector: 0,
            self.presidio_detector: 0,
            self.openmed_detector: 0,
            self.gliner2_detector: 0,
            self.ministral_detector: 0,
        }
        for detector, entities in results_per_detector:
            if detector in counts:
                counts[detector] = len(entities)
        self.logger.debug(
            f"Composite detection complete: {len(merged_entities)} entities "
            f"(ML: {counts[self.ml_detector]}, Regex: {counts[self.regex_detector]}, "
            f"Presidio: {counts[self.presidio_detector]}, "
            f"OpenMed: {counts[self.openmed_detector]}, "
            f"GLiNER2: {counts[self.gliner2_detector]}, "
            f"Ministral: {counts[self.ministral_detector]})"
        )

    def _log_parity_debug(
        self,
        results_per_detector: List[Tuple[PIIDetectorProtocol, List[PIIEntity]]],
        merged_entities: List[PIIEntity],
    ) -> None:
        # TEMPORARY: parity recall investigation — remove with git revert
        per_detector_per_type: dict = {}
        for detector, entities in results_per_detector:
            label = self._resolve_detector_label(detector)
            type_counts: dict = {}
            for ent in entities:
                type_counts[ent.pii_type] = type_counts.get(ent.pii_type, 0) + 1
            per_detector_per_type[label] = type_counts

        merged_type_counts: dict = {}
        for ent in merged_entities:
            merged_type_counts[ent.pii_type] = merged_type_counts.get(ent.pii_type, 0) + 1

        self.logger.info(
            "[PARITY_DEBUG] COMPOSITE_PRE_MERGE per_detector_per_type=%s",
            per_detector_per_type
        )
        self.logger.info(
            "[PARITY_DEBUG] COMPOSITE_POST_MERGE total=%d per_type=%s",
            len(merged_entities), merged_type_counts
        )

    def _log_finding_tracker(
        self,
        results_per_detector: List[Tuple[PIIDetectorProtocol, List[PIIEntity]]],
        merged_entities: List[PIIEntity],
    ) -> None:
        for detector, entities in results_per_detector:
            branch = self._resolve_detector_label(detector).upper()
            self.logger.info(
                "[FINDING_TRACKER] step=COMPOSITE_%s_RAW count=%d",
                branch, len(entities),
            )
        total_in = sum(len(e) for _, e in results_per_detector)
        self.logger.info(
            "[FINDING_TRACKER] step=COMPOSITE_AFTER_MERGE in=%d out=%d dropped=%d",
            total_in, len(merged_entities), total_in - len(merged_entities),
        )

    def mask_pii(
        self, text: str, threshold: Optional[float] = None
    ) -> Tuple[str, List[PIIEntity]]:
        """
        Mask PII in text using composite detection.
        
        Args:
            text: Text to mask
            threshold: Optional confidence threshold
            
        Returns:
            Tuple of (masked_text, detected_entities)
        """
        entities = self.detect_pii(text, threshold)
        masked_text = self._apply_masks(text, entities)
        
        return masked_text, entities
    
    def _run_ml_detection(
        self,
        text: str,
        threshold: Optional[float],
        pii_type_configs: Optional[dict] = None,
        chunk_size: Optional[int] = None
    ) -> List[PIIEntity]:
        """
        Run ML-based detection with error handling.

        Args:
            text: Text to analyze
            threshold: Optional confidence threshold
            pii_type_configs: Optional PII type configs for dynamic settings
            chunk_size: Optional chunk size for optimizing passes

        Returns:
            List of detected entities (empty if detection fails)
        """
        try:
            # Check if ML detector supports parameters using inspection
            import inspect
            sig = inspect.signature(self.ml_detector.detect_pii)

            kwargs = {}
            if 'pii_type_configs' in sig.parameters:
                kwargs['pii_type_configs'] = pii_type_configs

            if 'chunk_size' in sig.parameters and chunk_size is not None:
                kwargs['chunk_size'] = chunk_size

            return self.ml_detector.detect_pii(text, threshold, **kwargs)

        except Exception as e:
            # TEMPORARY: parity recall investigation — was silently returning [] on any ML
            # failure (OOM, model crash, thread deadlock), turning the request into a
            # Presidio/Regex-only response with no error visible to the caller.
            # Now we log the full stack trace at ERROR and re-raise so the gRPC client
            # gets a real INTERNAL error and we don't silently lose hundreds of findings.
            self.logger.error(
                "[PARITY_DEBUG] ML_DETECTION_FAILED text_len=%d threshold=%s configs=%s chunk_size=%s: %s",
                len(text) if text else 0, threshold,
                len(pii_type_configs) if pii_type_configs else None,
                chunk_size, e,
                exc_info=True
            )
            raise

    def _run_regex_detection(
        self, text: str, threshold: Optional[float]
    ) -> List[PIIEntity]:
        """
        Run regex-based detection with error handling.

        Args:
            text: Text to analyze
            threshold: Optional confidence threshold

        Returns:
            List of detected entities (empty if detection fails)
        """
        try:
            return self.regex_detector.detect_pii(text, threshold)
        except Exception as e:
            # TEMPORARY: parity recall investigation — log full trace before swallowing.
            # Regex detector silently failing should not kill the whole request, but we
            # need it visible in logs so we can correlate with missing findings.
            self.logger.error(
                "[PARITY_DEBUG] REGEX_DETECTION_FAILED text_len=%d threshold=%s: %s",
                len(text) if text else 0, threshold, e,
                exc_info=True
            )
            return []

    def _run_openmed_detection(
        self,
        text: str,
        threshold: Optional[float],
        pii_type_configs: Optional[dict] = None,
    ) -> List[PIIEntity]:
        """
        Run OpenMed detection with error handling.

        Args:
            text: Text to analyze
            threshold: Optional confidence threshold
            pii_type_configs: Optional fresh PII type configs from database

        Returns:
            List of detected entities (empty if detection fails or OpenMed disabled)
        """
        try:
            import inspect
            sig = inspect.signature(self.openmed_detector.detect_pii)
            kwargs: dict = {}
            if 'pii_type_configs' in sig.parameters:
                kwargs['pii_type_configs'] = pii_type_configs
            return self.openmed_detector.detect_pii(text, threshold, **kwargs)
        except Exception as e:
            # Do not fail the whole request if OpenMed crashes (e.g. transformers
            # version mismatch on a partially-upgraded environment).
            self.logger.error(
                "OPENMED_DETECTION_FAILED text_len=%d threshold=%s: %s",
                len(text) if text else 0, threshold, e,
                exc_info=True,
            )
            return []

    def _run_gliner2_detection(
        self,
        text: str,
        threshold: Optional[float],
        pii_type_configs: Optional[dict] = None,
    ) -> List[PIIEntity]:
        """Run GLiNER2 detection with graceful degradation.

        Returns an empty list (never raises) so a GLiNER2 failure (e.g. the
        gliner2 lib being absent on a partial environment) does not bring down
        the whole request — spec RG5 / R7.
        """
        try:
            self._ensure_gliner2_loaded()
            import inspect
            sig = inspect.signature(self.gliner2_detector.detect_pii)
            kwargs: dict = {}
            if 'pii_type_configs' in sig.parameters:
                kwargs['pii_type_configs'] = pii_type_configs
            return self.gliner2_detector.detect_pii(text, threshold, **kwargs)
        except Exception as e:
            self.logger.error(
                "GLINER2_DETECTION_FAILED text_len=%d threshold=%s: %s",
                len(text) if text else 0, threshold, e,
                exc_info=True,
            )
            return []

    def _ensure_gliner2_loaded(self) -> None:
        """Lazily load the GLiNER2 model the first time it is actually needed.

        The model is only eagerly loaded at startup when ``gliner2_enabled`` was
        already TRUE. Because the flag is hot-toggleable without a redeploy (spec
        RG6/O3), an operator can enable GLiNER2 after startup; in that case the
        model was never loaded. Load it on first use so detection works without a
        restart. A persistent failure (e.g. gliner2 lib absent) propagates to the
        caller's ``except`` and degrades gracefully to an empty result (RG5)."""
        if getattr(self.gliner2_detector, "model", None) is not None:
            return
        self.logger.info(
            "GLiNER2 enabled at runtime but model not loaded — loading on first use"
        )
        self.gliner2_detector.load_model()

    def _run_ministral_detection(
        self,
        text: str,
        threshold: Optional[float],
        pii_type_configs: Optional[dict] = None,
        chunk_size: Optional[int] = None,
        overlap: Optional[int] = None,
    ) -> List[PIIEntity]:
        """Run Ministral-PII detection with graceful degradation.

        Returns an empty list (never raises) so a Ministral failure (e.g. the
        remote LM Studio endpoint being unreachable) does not bring down the
        whole request. ``chunk_size``/``overlap`` here are the Ministral-specific
        chunking knobs (DB columns ministral_chunk_size / ministral_overlap),
        distinct from the GLiNER multi-pass labels-per-pass. They and
        ``pii_type_configs`` are forwarded only when the detector's
        ``detect_pii`` declares them (signature inspection).
        """
        try:
            import inspect
            sig = inspect.signature(self.ministral_detector.detect_pii)
            kwargs: dict = {}
            if 'pii_type_configs' in sig.parameters:
                kwargs['pii_type_configs'] = pii_type_configs
            if 'chunk_size' in sig.parameters and chunk_size is not None:
                kwargs['chunk_size'] = chunk_size
            if 'overlap' in sig.parameters and overlap is not None:
                kwargs['overlap'] = overlap
            return self.ministral_detector.detect_pii(text, threshold, **kwargs)
        except Exception as e:
            self.logger.error(
                "MINISTRAL_DETECTION_FAILED text_len=%d threshold=%s: %s",
                len(text) if text else 0, threshold, e,
                exc_info=True,
            )
            return []

    def _run_presidio_detection(
        self, text: str, threshold: Optional[float]
    ) -> List[PIIEntity]:
        """
        Run Presidio-based detection with error handling.

        Args:
            text: Text to analyze
            threshold: Optional confidence threshold

        Returns:
            List of detected entities (empty if detection fails)
        """
        try:
            return self.presidio_detector.detect_pii(text, threshold)
        except Exception as e:
            # TEMPORARY: parity recall investigation — log full trace before swallowing.
            self.logger.error(
                "[PARITY_DEBUG] PRESIDIO_DETECTION_FAILED text_len=%d threshold=%s: %s",
                len(text) if text else 0, threshold, e,
                exc_info=True
            )
            return []
    
    def _apply_masks(self, text: str, entities: List[PIIEntity]) -> str:
        """
        Apply masks to detected entities.
        
        Args:
            text: Original text
            entities: Detected entities
            
        Returns:
            Masked text with PII replaced by type labels
        """
        if not entities:
            return text

        # Sort by start position for linear scan
        sorted_entities = sorted(entities, key=lambda x: x.start)
        
        parts = []
        last_pos = 0
        
        for entity in sorted_entities:
            # Skip if entity overlaps with previous one
            if entity.start < last_pos:
                continue
                
            parts.append(text[last_pos:entity.start])
            parts.append(f"[{entity.pii_type}]")
            last_pos = entity.end
        
        # Append remaining text
        parts.append(text[last_pos:])
        
        return "".join(parts)


def should_use_composite_detector() -> bool:
    """
    Determine if composite detector should be used.
    
    Always returns True to enable dynamic runtime activation of detectors.
    
    Business Rule: CompositePIIDetector is always instantiated to allow runtime
    activation/deactivation of Regex and Presidio detectors via database config.
    The actual activation is controlled by `pii_detection_config` table fields:
    - gliner_enabled
    - presidio_enabled  
    - regex_enabled
    
    These are fetched at request time via `fetch_config_from_db=true` in gRPC.
    
    Returns:
        True (always) to enable runtime detector activation via database
    """
    logger.debug(
        "Composite detector always enabled for runtime activation via database config"
    )
    return True


def _load_detection_config() -> Tuple[bool, bool]:
    """
    Return default activation states for detectors.
    
    Note: Actual activation is now controlled by database config at runtime.
    These defaults are used when database config is not available.
    Both detectors are disabled by default to avoid unexpected behavior.
    
    Returns:
        Tuple of (regex_enabled_default, presidio_enabled_default)
        Both False to require explicit database activation.
    """
    # Default: both disabled (require explicit database activation)
    logger.debug(
        "Default detector activation: Regex=False, Presidio=False "
        "(use database config for runtime activation)"
    )
    return False, False


def _create_regex_detector_if_enabled(regex_enabled: bool) -> Optional[RegexDetector]:
    """
    Create RegexDetector instance (always created for runtime activation).
    
    IMPORTANT: This function now ALWAYS creates RegexDetector instance regardless
    of the regex_enabled parameter. The parameter is kept for backward compatibility
    but only used for logging purposes.
    
    Business rule: Detectors are instantiated at startup to avoid recreation overhead,
    but can be selectively activated at runtime via database configuration without
    requiring service restart.
    
    Args:
        regex_enabled: TOML configuration flag (for logging only)
        
    Returns:
        RegexDetector instance or None (only None if creation fails)
    """
    try:
        detector = RegexDetector()
        if regex_enabled:
            logger.debug("Created RegexDetector for composite (enabled by default in TOML)")
        else:
            logger.debug("Created RegexDetector for composite (disabled by default in TOML, can be activated at runtime)")
        return detector
    except Exception as e:
        logger.warning(f"Failed to create RegexDetector: {e}")
        return None


def _create_presidio_detector_if_enabled(presidio_enabled: bool) -> Optional[PresidioDetector]:
    """
    Create PresidioDetector instance (always created for runtime activation if available).
    
    IMPORTANT: This function now ALWAYS creates PresidioDetector instance (if library available)
    regardless of the presidio_enabled parameter. The parameter is kept for backward compatibility
    but only used for logging purposes.
    
    Business rule: Detectors are instantiated at startup to avoid recreation overhead,
    but can be selectively activated at runtime via database configuration without
    requiring service restart.
    
    Args:
        presidio_enabled: TOML configuration flag (for logging only)
        
    Returns:
        PresidioDetector instance or None (None only if library unavailable or creation fails)
    """
    if not PRESIDIO_AVAILABLE:
        logger.warning("PresidioDetector not available (library not installed)")
        return None
    
    try:
        detector = PresidioDetector()
        if presidio_enabled:
            logger.debug("Created PresidioDetector for composite (enabled by default in TOML)")
        else:
            logger.debug("Created PresidioDetector for composite (disabled by default in TOML, can be activated at runtime)")
        return detector
    except Exception as e:
        logger.warning(f"Failed to create PresidioDetector: {e}")
        return None


def _create_openmed_detector_if_available() -> Optional[PIIDetectorProtocol]:
    """
    Create an ``OpenMedDetector`` instance. The detector is always instantiated
    so it can be toggled on at runtime via the ``openmed_enabled`` DB flag,
    but the HF model is downloaded/loaded lazily — only when the composite
    actually activates it via ``enable_openmed``.
    """
    try:
        from pii_detector.infrastructure.detector.openmed_detector import OpenMedDetector

        detector = OpenMedDetector()
        logger.debug("Created OpenMedDetector instance (lazy model load)")
        return detector
    except Exception as exc:
        logger.warning(f"Failed to create OpenMedDetector: {exc}")
        return None


def _create_gliner2_detector_if_available() -> Optional[PIIDetectorProtocol]:
    """
    Create a ``Gliner2Detector`` instance. Always instantiated so it can be
    toggled on at runtime via the ``gliner2_enabled`` DB flag, but the model is
    downloaded/loaded lazily — only when the composite actually activates it via
    ``enable_gliner2``. Returns ``None`` if instantiation fails (e.g. gliner2
    library absent), keeping startup resilient.
    """
    try:
        from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector

        # No config -> detector falls back to its own GLINER2 default model_id.
        detector = Gliner2Detector()
        logger.debug("Created Gliner2Detector instance (lazy model load)")
        return detector
    except Exception as exc:
        logger.warning(f"Failed to create Gliner2Detector: {exc}")
        return None


def _create_ministral_detector_if_available() -> Optional[PIIDetectorProtocol]:
    """
    Create a ``MinistralDetector`` instance. Always instantiated so it can be
    toggled on at runtime via the ``ministral_enabled`` DB flag. Ministral-PII
    is served by a remote OpenAI-compatible endpoint, so there is no local model
    to load (``download_model`` / ``load_model`` are no-ops). Returns ``None`` if
    instantiation fails, keeping startup resilient.
    """
    try:
        from pii_detector.infrastructure.detector.ministral_detector import MinistralDetector

        detector = MinistralDetector()
        logger.debug("Created MinistralDetector instance (remote endpoint)")
        return detector
    except Exception as exc:
        logger.warning(f"Failed to create MinistralDetector: {exc}")
        return None


def _load_gliner2_enabled_from_db() -> bool:
    """Read the ``gliner2_enabled`` kill-switch from the DB at startup.

    Returns ``False`` defensively on any error (DB down, column missing on a
    not-yet-migrated deployment) so a hiccup never blocks startup — the runtime
    per-request flag plus lazy model load still activate GLiNER2 later (RG6)."""
    try:
        from pii_detector.infrastructure.adapter.out.database_config_adapter import (
            get_database_config_adapter,
        )

        cfg = get_database_config_adapter().fetch_config()
        return bool(cfg.get("gliner2_enabled", False)) if cfg else False
    except Exception:  # pragma: no cover - defensive
        return False


def create_composite_detector(
    ml_detector: Optional[PIIDetectorProtocol] = None
) -> CompositePIIDetector:
    """
    Factory function to create composite detector with default configuration.

    Args:
        ml_detector: Optional ML detector to use (creates default if None)

    Returns:
        Configured CompositePIIDetector instance
    """
    # Load detection configuration
    regex_enabled, presidio_enabled = _load_detection_config()

    # Create detectors based on configuration
    regex_detector = _create_regex_detector_if_enabled(regex_enabled)
    presidio_detector = _create_presidio_detector_if_enabled(presidio_enabled)
    openmed_detector = _create_openmed_detector_if_available()
    gliner2_detector = _create_gliner2_detector_if_available()
    ministral_detector = _create_ministral_detector_if_available()

    # GLiNER2: load the model eagerly at startup when the DB kill-switch is
    # already ON, so the first request isn't penalised by a cold model load.
    # When OFF, it stays disabled (D4) and is loaded lazily on first use if an
    # operator toggles it on at runtime (RG6) — see _ensure_gliner2_loaded.
    gliner2_enabled_at_startup = (
        gliner2_detector is not None and _load_gliner2_enabled_from_db()
    )

    # Create merger with provenance logging
    merger = DetectionMerger(log_provenance=True)

    # Create composite detector
    composite = CompositePIIDetector(
        ml_detector=ml_detector,
        regex_detector=regex_detector,
        presidio_detector=presidio_detector,
        openmed_detector=openmed_detector,
        gliner2_detector=gliner2_detector,
        ministral_detector=ministral_detector,
        merger=merger,
        enable_regex=regex_enabled and regex_detector is not None,
        enable_presidio=presidio_enabled and presidio_detector is not None,
        # OpenMed default: disabled at startup, activated at request time via DB flag
        enable_openmed=False,
        # GLiNER2: enabled at startup only when the DB flag is already ON (eager
        # load); otherwise lazy on first use after a runtime toggle (D4 / RG6).
        enable_gliner2=gliner2_enabled_at_startup,
        # Ministral-PII: disabled at startup, activated at request time via DB
        # flag (remote endpoint, nothing to load eagerly).
        enable_ministral=False,
    )

    return composite