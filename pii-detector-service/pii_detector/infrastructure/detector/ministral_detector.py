"""
Ministral-PII generative PII detector.

Unlike the token-classification detectors (OpenMed, GLiNER2), Ministral-PII is a
**specialised LLM extractor** served behind an OpenAI-compatible
``/chat/completions`` endpoint (LM Studio). We prompt the model to return a
strict JSON array of ``{text, label}`` objects, then locate each returned text
span back in the source to recover character offsets.

Why HTTP/1.1 + no proxy: LM Studio hangs on the h2c upgrade and local/LAN
endpoints must never be routed through a corporate proxy. We therefore build the
``httpx.Client`` with ``http2=False`` and ``trust_env=False`` (mirrors the Java
``LlmExtractorClient`` ``HTTP_1_1`` / ``NO_PROXY`` reference).

Chunking: long documents are split with :class:`FallbackChunker`; each chunk is
extracted independently and entity offsets are rebased to **global** coordinates
via ``chunk.start``. A per-chunk failure (timeout / HTTP error) is logged and
skipped (fail-open partial) so one bad chunk never sinks the whole detection.

Per-type thresholds and the Ministral label -> canonical ``pii_type`` mapping are
resolved from the ``pii_type_config`` DB rows (detector='MINISTRAL').

This detector is permanently exempt from the LLM-as-judge post-filter (same
model nature): its entities stay ``NOT_AUDITED``. It never sets ``judge_status``.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import httpx

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.domain.exception.exceptions import PIIDetectionError
from pii_detector.infrastructure.text_processing.semantic_chunker import FallbackChunker

DETECTOR_NAMESPACE = "MINISTRAL"
MINISTRAL_DEFAULT_MODEL_ID = "ministral-3b-pii-preview@q8_0"
DEFAULT_BASE_URL = "http://localhost:1234/v1"
# Permissive global threshold at this layer; the LLM does not emit a confidence,
# so detected entities carry this score unless a request threshold overrides it.
DEFAULT_GLOBAL_THRESHOLD = 0.5

# Default chunking parameters (tokens). Mirrors the DB defaults in
# init-script 013 (ministral_chunk_size / ministral_overlap).
DEFAULT_CHUNK_SIZE_TOKENS = 1024
DEFAULT_CHUNK_OVERLAP_TOKENS = 128
# Conservative chars/token used to translate token limits into char windows
# for the LLM context (multilingual-safe upper bound).
CHARS_PER_TOKEN = 4

# HTTP timeout (seconds) for one chat/completions call.
HTTP_TIMEOUT_SECONDS = 120.0
# Generation budget large enough to absorb a JSON array over a full chunk.
MAX_TOKENS = 2048

# Strict JSON Schema forcing an array of {text, label} objects (mirrors the
# Java LlmExtractorClient entityArraySchema()).
_ENTITY_ARRAY_SCHEMA: Dict[str, Any] = {
    "type": "array",
    "items": {
        "type": "object",
        "properties": {
            "text": {"type": "string"},
            "label": {"type": "string"},
        },
        "required": ["text", "label"],
    },
}

_SYSTEM_PROMPT = (
    "You are a strict PII extraction engine. Extract every personally "
    "identifiable information span that appears verbatim in the user text. "
    "Return ONLY a JSON array of objects, each with exactly two fields: "
    '"text" (the exact substring as it appears in the input) and "label" '
    "(the PII category). Do not paraphrase, translate or normalise the text. "
    "Do not add explanations. If there is no PII, return an empty array []."
)


class MinistralDetector:
    """PII detector backed by the Ministral-PII LLM (OpenAI-compatible endpoint).

    Implements ``PIIDetectorProtocol``: ``model_id``, ``detect_pii``,
    ``mask_pii``, ``download_model``, ``load_model``.
    """

    def __init__(self, config: Optional[DetectionConfig] = None):
        self.config = config or DetectionConfig(model_id=MINISTRAL_DEFAULT_MODEL_ID)
        # Remote endpoint + model id are env-driven (LM Studio host), with the
        # spec defaults as fallback. The generic config is accepted for parity
        # with the other detectors but the model id is forced to the env value.
        self.base_url = os.environ.get("LLM_MINISTRAL_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
        self._model_id = os.environ.get("LLM_MINISTRAL_MODEL", MINISTRAL_DEFAULT_MODEL_ID)
        self.threshold = (
            self.config.threshold
            if self.config.threshold is not None
            else DEFAULT_GLOBAL_THRESHOLD
        )

        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")
        self._client: Optional[httpx.Client] = None

    # ------------------------------------------------------------------
    # PIIDetectorProtocol
    # ------------------------------------------------------------------

    @property
    def model_id(self) -> str:
        return self._model_id

    def download_model(self) -> None:
        """No-op: the model lives behind a remote OpenAI-compatible endpoint."""
        return None

    def load_model(self) -> None:
        """No-op: nothing to load locally (remote inference endpoint)."""
        return None

    def detect_pii(
        self,
        text: str,
        threshold: Optional[float] = None,
        pii_type_configs: Optional[Dict] = None,
        chunk_size: Optional[int] = None,
        overlap: Optional[int] = None,
    ) -> List[PIIEntity]:
        if not text:
            return []

        effective_threshold = threshold if threshold is not None else self.threshold
        detection_id = f"ministral_{int(time.time() * 1000) % 10000}"

        try:
            label_mapping, scoring_overrides, type_labels = self._resolve_runtime_config(
                pii_type_configs
            )
            self.logger.debug(
                "[%s] Ministral runtime config: %d labels mapped, %d thresholds",
                detection_id, len(label_mapping), len(scoring_overrides),
            )
            if not label_mapping:
                return []

            entities = self._extract_over_chunks(
                text, effective_threshold, label_mapping, type_labels, chunk_size, overlap
            )
            entities = self._apply_per_type_thresholds(entities, scoring_overrides)

            self.logger.info(
                "[%s] Ministral detection complete: %d kept",
                detection_id, len(entities),
            )
            return entities
        except PIIDetectionError:
            raise
        except Exception as exc:  # pragma: no cover - defensive logging
            self.logger.error(
                "[%s] Ministral detection failed: %s", detection_id, exc, exc_info=True
            )
            raise PIIDetectionError(f"Ministral PII detection failed: {exc}") from exc

    def mask_pii(
        self, text: str, threshold: Optional[float] = None
    ) -> Tuple[str, List[PIIEntity]]:
        entities = self.detect_pii(text, threshold)
        masked = self._apply_masks(text, entities)
        return masked, entities

    # ------------------------------------------------------------------
    # Extraction over chunks (global offset rebasing)
    # ------------------------------------------------------------------

    def _extract_over_chunks(
        self,
        text: str,
        threshold: float,
        label_mapping: Dict[str, str],
        type_labels: Dict[str, str],
        chunk_size: Optional[int],
        overlap: Optional[int],
    ) -> List[PIIEntity]:
        """Chunk the text, extract per chunk, rebase offsets to global coords.

        Each chunk is extracted independently via the remote LLM. The returned
        text spans are located inside the chunk (chunk-local offsets) and then
        shifted by ``chunk.start`` so the final ``PIIEntity`` carries GLOBAL
        character offsets. A per-chunk HTTP/timeout failure is logged and skipped
        (fail-open partial).
        """
        chunker = FallbackChunker(
            chunk_size=chunk_size if chunk_size is not None else DEFAULT_CHUNK_SIZE_TOKENS,
            overlap=overlap if overlap is not None else DEFAULT_CHUNK_OVERLAP_TOKENS,
            chars_per_token=CHARS_PER_TOKEN,
            logger=self.logger,
        )
        chunks = chunker.chunk_text(text)
        entities: List[PIIEntity] = []
        for chunk in chunks:
            try:
                raw_pairs = self._extract_chunk(chunk.text)
            except (httpx.HTTPError, httpx.TimeoutException) as exc:
                self.logger.warning(
                    "MINISTRAL_CHUNK_FAILED start=%d len=%d: %s",
                    chunk.start, len(chunk.text), exc,
                )
                continue
            entities.extend(
                self._pairs_to_entities(
                    raw_pairs, chunk.text, chunk.start, threshold,
                    label_mapping, type_labels,
                )
            )
        return entities

    def _pairs_to_entities(
        self,
        raw_pairs: List[Dict[str, Any]],
        chunk_text: str,
        chunk_start: int,
        threshold: float,
        label_mapping: Dict[str, str],
        type_labels: Dict[str, str],
    ) -> List[PIIEntity]:
        """Map ``{text, label}`` pairs to ``PIIEntity`` with global offsets."""
        results: List[PIIEntity] = []
        for pair in raw_pairs:
            entity = self._pair_to_entity(
                pair, chunk_text, chunk_start, threshold, label_mapping, type_labels
            )
            if entity is not None:
                results.append(entity)
        return results

    def _pair_to_entity(
        self,
        pair: Dict[str, Any],
        chunk_text: str,
        chunk_start: int,
        threshold: float,
        label_mapping: Dict[str, str],
        type_labels: Dict[str, str],
    ) -> Optional[PIIEntity]:
        """Build a single ``PIIEntity`` from one ``{text, label}`` pair.

        Returns ``None`` when the label is unknown (skip) or the text cannot be
        located in the chunk (case-sensitive find; skip on miss).
        """
        if not isinstance(pair, dict):
            return None
        span_text = pair.get("text")
        label = pair.get("label")
        if not span_text or not label:
            return None
        pii_type = label_mapping.get(str(label))
        if not pii_type:
            return None
        local_start = chunk_text.find(str(span_text))
        if local_start < 0:
            return None
        global_start = chunk_start + local_start
        global_end = global_start + len(str(span_text))
        return PIIEntity(
            text=str(span_text),
            pii_type=pii_type,
            type_label=type_labels.get(pii_type, pii_type),
            start=global_start,
            end=global_end,
            score=float(threshold) if threshold is not None else DEFAULT_GLOBAL_THRESHOLD,
            source=DetectorSource.MINISTRAL,
        )

    # ------------------------------------------------------------------
    # Remote LLM call (OpenAI-compatible chat/completions, HTTP/1.1, no proxy)
    # ------------------------------------------------------------------

    def _get_client(self) -> httpx.Client:
        """Lazily build the httpx client: HTTP/1.1 forced, env/proxy ignored."""
        if self._client is None:
            self._client = httpx.Client(
                http2=False,
                trust_env=False,
                timeout=HTTP_TIMEOUT_SECONDS,
            )
        return self._client

    def _extract_chunk(self, chunk_text: str) -> List[Dict[str, Any]]:
        """POST one chunk to ``/chat/completions`` and parse the entity array."""
        client = self._get_client()
        url = f"{self.base_url}/chat/completions"
        payload = self._build_payload(chunk_text)
        resp = client.post(url, json=payload)
        resp.raise_for_status()
        content = self._extract_content(resp.json())
        return self._parse_entity_array(content)

    def _build_payload(self, chunk_text: str) -> Dict[str, Any]:
        """Build the OpenAI chat/completions body (temperature 0, json_schema)."""
        return {
            "model": self._model_id,
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": chunk_text},
            ],
            "temperature": 0,
            "stream": False,
            "max_tokens": MAX_TOKENS,
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": "pii_entities",
                    "strict": True,
                    "schema": _ENTITY_ARRAY_SCHEMA,
                },
            },
        }

    @staticmethod
    def _extract_content(response_json: Dict[str, Any]) -> str:
        """Read ``choices[0].message.content`` (fallback ``reasoning_content``)."""
        choices = response_json.get("choices") or []
        if not choices:
            return ""
        message = choices[0].get("message") or {}
        return str(message.get("content") or message.get("reasoning_content") or "")

    @staticmethod
    def _parse_entity_array(content: str) -> List[Dict[str, Any]]:
        """Tolerant extraction of the first balanced top-level JSON array.

        Handles markdown fences and surrounding prose by scanning for the first
        ``[`` and its matching ``]`` before parsing. Returns an empty list when
        no array is found or the payload is not a list.
        """
        if not content:
            return []
        snippet = MinistralDetector._first_json_array(content)
        if snippet is None:
            return []
        try:
            parsed = json.loads(snippet)
        except (ValueError, TypeError):
            return []
        return parsed if isinstance(parsed, list) else []

    @staticmethod
    def _first_json_array(content: str) -> Optional[str]:
        """Return the first balanced ``[...]`` substring, or ``None``."""
        cleaned = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL)
        start = cleaned.find("[")
        if start < 0:
            return None
        depth = 0
        for idx in range(start, len(cleaned)):
            char = cleaned[idx]
            if char == "[":
                depth += 1
            elif char == "]":
                depth -= 1
                if depth == 0:
                    return cleaned[start: idx + 1]
        return None

    # ------------------------------------------------------------------
    # Filtering
    # ------------------------------------------------------------------

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
    # Configuration resolution (mirrors OpenMedDetector)
    # ------------------------------------------------------------------

    def _resolve_runtime_config(
        self, pii_type_configs: Optional[Dict]
    ) -> Tuple[Dict[str, str], Dict[str, float], Dict[str, str]]:
        configs = pii_type_configs or self._fetch_configs_from_db()
        if not configs:
            self.logger.warning(
                "No MINISTRAL pii_type configs available — detector will emit nothing"
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

        Accepts both the ``MINISTRAL:<type>`` keyed shape and the flat shape
        where the row carries ``detector == "MINISTRAL"``. Returns ``None`` when
        the entry is not a MINISTRAL config (caller skips it).
        """
        if isinstance(key, str) and key.startswith(f"{DETECTOR_NAMESPACE}:"):
            return key.split(":", 1)[1]
        detector_value = cfg.get("detector") if isinstance(cfg, dict) else None
        if detector_value == DETECTOR_NAMESPACE:
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
        # Optional human-readable label forwarded to the LLM judge. When absent,
        # _pair_to_entity falls back to the pii_type.
        type_label = cfg.get("type_label")
        if type_label:
            type_labels[pii_type] = str(type_label)

    def _fetch_configs_from_db(self) -> Optional[Dict]:
        try:
            from pii_detector.infrastructure.adapter.out.database_config_adapter import (
                get_database_config_adapter,
            )

            adapter = get_database_config_adapter()
            return adapter.fetch_pii_type_configs(detector=DETECTOR_NAMESPACE)
        except Exception as exc:
            self.logger.warning(
                "Failed to fetch MINISTRAL pii_type configs from DB: %s", exc
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
