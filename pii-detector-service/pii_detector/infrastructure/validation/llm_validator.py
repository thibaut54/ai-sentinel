from __future__ import annotations

import atexit
import logging
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError
from typing import List, Optional, Set

from pii_detector.infrastructure.validation.prompt_templates import (
    build_batch_prompt,
)

logger = logging.getLogger(__name__)

# Compiled regex for O(N) response parsing -- compile once, use many times
_VERDICT_PATTERN = re.compile(
    r"^\s*\[?(\d+)\]?\s*[:\.]\s*(TRUE_POSITIVE|FALSE_POSITIVE)\s*$",
    re.IGNORECASE | re.MULTILINE,
)


class LLMValidator:
    """Validates PII detections using a local LLM (Gemma 4 E4B via llama-cpp-python).

    Acts as a precision filter post-detection to reduce false positives.

    Performance characteristics:
    - Model loading: O(1) singleton at startup (GGUF quantized model)
    - Batch validation: O(N/B) LLM calls where B=batch_size, plus O(N) prompt building
    - Response parsing: O(N) via compiled regex
    - Entity filtering: O(N) via set lookup
    """

    _HF_REPO = "unsloth/gemma-4-E4B-it-GGUF"
    _HF_FILENAME = "gemma-4-E4B-it-Q4_K_M.gguf"

    def __init__(
        self,
        model_path: str = "google/gemma-4-E4B-it",
        device: str = "auto",
        context_window: int = 200,
        max_batch_size: int = 20,
        timeout_seconds: float = 300.0,
        max_output_tokens: int = 200,
        temperature: float = 0.0,
        n_ctx: int = 4096,
        n_gpu_layers: int = -1,
    ) -> None:
        self.model_path = model_path
        self.device = device
        self.context_window = context_window
        self.max_batch_size = max_batch_size
        self.timeout_seconds = timeout_seconds
        self.max_output_tokens = max_output_tokens
        self.temperature = temperature
        self.n_ctx = n_ctx
        self.n_gpu_layers = n_gpu_layers
        self._model = None
        self._tokenizer = None
        self._device = "cpu"
        self._executor = ThreadPoolExecutor(max_workers=1)
        atexit.register(self.shutdown)

    def load_model(self) -> bool:
        try:
            from llama_cpp import Llama

            model_path = self._resolve_model_path()
            if model_path is None:
                return False

            n_gpu = self._resolve_gpu_layers()

            logger.info(
                "Loading Gemma 4 E4B GGUF from %s (n_ctx=%d, n_gpu_layers=%d) ...",
                model_path, self.n_ctx, n_gpu,
            )

            self._model = Llama(
                model_path=model_path,
                n_ctx=self.n_ctx,
                n_gpu_layers=n_gpu,
                verbose=False,
            )

            logger.info("LLM validator (Gemma 4 E4B GGUF) loaded successfully")
            return True
        except Exception:
            logger.warning(
                "Failed to load LLM validator model %s. "
                "Validation will be skipped (graceful degradation).",
                self.model_path,
                exc_info=True,
            )
            self._model = None
            return False

    def _resolve_model_path(self) -> Optional[str]:
        """Resolve the GGUF model file path.

        Checks in order:
        1. Direct file path (if model_path points to an existing .gguf file)
        2. HuggingFace Hub download via huggingface_hub.hf_hub_download
        """
        if os.path.isfile(self.model_path):
            return self.model_path

        # Try downloading from HuggingFace Hub
        try:
            from huggingface_hub import hf_hub_download

            hf_token = os.getenv("HF_TOKEN") or os.getenv("HUGGING_FACE_HUB_TOKEN")

            # If model_path looks like a HF model ID (no .gguf extension),
            # download from the official GGUF repo
            if self.model_path.endswith(".gguf"):
                # model_path is a filename hint, download from default repo
                repo_id = self._HF_REPO
                filename = self.model_path.split("/")[-1]
            elif "/" in self.model_path and "gguf" in self.model_path.lower():
                # Explicit GGUF repo like "ggml-org/gemma-4-E4B-it-GGUF"
                repo_id = self.model_path
                filename = self._HF_FILENAME
            else:
                # Generic model ID like "google/gemma-4-E4B-it" -> use official GGUF repo
                repo_id = self._HF_REPO
                filename = self._HF_FILENAME

            logger.info("Downloading GGUF from HuggingFace: %s / %s", repo_id, filename)
            path = hf_hub_download(
                repo_id=repo_id,
                filename=filename,
                token=hf_token,
            )
            logger.info("GGUF model downloaded to: %s", path)
            return path
        except Exception as e:
            logger.warning("Could not resolve model path '%s': %s", self.model_path, e)
            return None

    def _resolve_gpu_layers(self) -> int:
        """Resolve the number of GPU layers to offload."""
        if self.device == "cpu":
            return 0
        if self.n_gpu_layers >= 0:
            return self.n_gpu_layers
        # auto or cuda: offload all layers
        return -1

    @property
    def is_available(self) -> bool:
        return self._model is not None

    def shutdown(self) -> None:
        """Shutdown the executor thread pool. Safe to call multiple times."""
        if self._executor is not None:
            self._executor.shutdown(wait=False)
            self._executor = None

    def validate_entities(
        self,
        entities: List,
        source_text: str,
        timeout_seconds: Optional[float] = None,
    ) -> List:
        if not self.is_available:
            return entities
        if not entities:
            return []

        timeout = timeout_seconds if timeout_seconds is not None else self.timeout_seconds
        start_time = time.monotonic()
        confirmed: List = []

        for i in range(0, len(entities), self.max_batch_size):
            batch = entities[i : i + self.max_batch_size]
            try:
                future = self._executor.submit(
                    self._validate_batch, batch, source_text
                )
                batch_result = future.result(timeout=timeout)
                confirmed.extend(batch_result)
            except (FuturesTimeoutError, Exception):
                logger.warning(
                    "Batch %d-%d: timeout or error, keeping %d entities (conservative).",
                    i,
                    i + len(batch),
                    len(batch),
                    exc_info=True,
                )
                confirmed.extend(batch)

        elapsed = time.monotonic() - start_time
        rejected_count = len(entities) - len(confirmed)
        logger.info(
            "LLM validation: %d submitted, %d confirmed, %d rejected (%.2fs)",
            len(entities),
            len(confirmed),
            rejected_count,
            elapsed,
        )
        return confirmed

    def _validate_batch(self, entities: List, source_text: str) -> List:
        prompt = build_batch_prompt(entities, source_text, self.context_window)

        # If prompt is too large for context window, split the batch in half
        estimated_tokens = len(prompt) // 3  # rough estimate: ~3 chars per token
        max_prompt_tokens = self.n_ctx - self.max_output_tokens - 50  # reserve for output + overhead
        if estimated_tokens > max_prompt_tokens and len(entities) > 1:
            mid = len(entities) // 2
            logger.info(
                "Batch too large (~%d tokens, max %d), splitting %d entities into %d + %d",
                estimated_tokens, max_prompt_tokens, len(entities), mid, len(entities) - mid,
            )
            left = self._validate_batch(entities[:mid], source_text)
            right = self._validate_batch(entities[mid:], source_text)
            return left + right

        batch_start = time.monotonic()

        response = self._model.create_chat_completion(
            messages=[
                {"role": "user", "content": prompt},
            ],
            max_tokens=self.max_output_tokens,
            temperature=self.temperature,
        )

        response_text = response["choices"][0]["message"]["content"] or ""
        inference_time = time.monotonic() - batch_start

        logger.info(
            "Gemma 4 inference: %d entities, %.2fs, prompt_len=%d, response=%r",
            len(entities),
            inference_time,
            len(prompt),
            response_text.strip(),
        )

        rejected_indices = self._parse_batch_response(
            response_text, len(entities)
        )

        for idx in rejected_indices:
            e = entities[idx]
            logger.info(
                "Gemma 4 REJECTED: type=%s text=%r (index=%d)",
                getattr(e, "pii_type", "?"),
                getattr(e, "text", "?"),
                idx,
            )

        return [
            e for i, e in enumerate(entities) if i not in rejected_indices
        ]

    def _parse_batch_response(
        self, response: str, entity_count: int
    ) -> Set[int]:
        rejected: Set[int] = set()
        for match in _VERDICT_PATTERN.finditer(response):
            idx = int(match.group(1))
            verdict = match.group(2).upper()
            if idx < 0 or idx >= entity_count:
                logger.warning(
                    "Verdict index %d out of range [0, %d), ignoring.",
                    idx,
                    entity_count,
                )
                continue
            if verdict == "FALSE_POSITIVE":
                rejected.add(idx)
        return rejected
