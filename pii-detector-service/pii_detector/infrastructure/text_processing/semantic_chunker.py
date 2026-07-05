"""
Text chunking with token-aware splitting.

This module provides text chunking for the Ministral-PII LLM extractor:
- MinistralTokenChunker: exact token-window chunking via the model's HuggingFace
  tokenizer offsets.
- FallbackChunker: char-ratio approximation used only when a tokenizer cannot be
  loaded.
"""

import logging
from dataclasses import dataclass
from typing import Any, List, Optional, Tuple


@dataclass
class ChunkResult:
    """Result of text chunking operation."""
    text: str
    start: int
    end: int
    token_count: Optional[int] = None


class FallbackChunker:
    """
    Simple character-based chunker with overlap support.

    Used as the degraded fallback when a real tokenizer is unavailable. Splits on
    character boundaries with approximate token estimation, using a conservative
    3 chars/token ratio for safety with multilingual text.
    """

    def __init__(
        self,
        chunk_size: int,
        overlap: int = 0,
        chars_per_token: int = 3,
        logger: Optional[logging.Logger] = None
    ):
        """
        Initialize character-based chunker.

        Args:
            chunk_size: Maximum tokens per chunk
            overlap: Number of tokens to overlap
            chars_per_token: Approximate characters per token (default: 3 for safety)
            logger: Optional logger instance
        """
        # Guard against a silently pathological configuration (overlap >= chunk
        # would make windows re-scan more than they advance) so a bad value fails
        # loudly instead of producing degenerate, infinitely-overlapping chunks.
        if chunk_size <= 0:
            raise ValueError(f"chunk_size ({chunk_size}) must be > 0")
        if overlap < 0:
            raise ValueError(f"overlap ({overlap}) must be >= 0")
        if overlap >= chunk_size:
            raise ValueError(
                f"chunk_size ({chunk_size}) must be greater than overlap ({overlap})"
            )

        self.chunk_size = chunk_size
        self.overlap = overlap
        self.chars_per_token = chars_per_token
        self.logger = logger or logging.getLogger(__name__)

        # Convert token limits to character limits
        self.chunk_chars = chunk_size * chars_per_token
        self.overlap_chars = overlap * chars_per_token

        self.logger.info(
            f"Character-based chunker initialized: "
            f"chunk_chars={self.chunk_chars}, overlap_chars={self.overlap_chars}"
        )

    def chunk_text(self, text: str) -> List[ChunkResult]:
        """
        Split text into character-based chunks.

        Args:
            text: Text to chunk

        Returns:
            List of ChunkResult objects
        """
        if not text:
            return []

        results = []
        offset = 0

        while offset < len(text):
            # Calculate chunk boundaries
            chunk_start = max(0, offset - self.overlap_chars if offset > 0 else 0)
            chunk_end = min(len(text), offset + self.chunk_chars)

            chunk_text = text[chunk_start:chunk_end]

            result = ChunkResult(
                text=chunk_text,
                start=chunk_start,
                end=chunk_end,
                token_count=None
            )
            results.append(result)

            offset += self.chunk_chars

        self.logger.debug(
            f"Fallback chunked {len(text)} chars into {len(results)} chunks"
        )

        return results

    def get_chunk_info(self) -> dict:
        """Get information about chunker configuration."""
        return {
            "chunk_size": self.chunk_size,
            "overlap": self.overlap,
            "chunk_chars": self.chunk_chars,
            "overlap_chars": self.overlap_chars,
            "chars_per_token": self.chars_per_token,
            "library": "fallback",
            "available": True
        }


class MinistralTokenChunker:
    """Token-window chunker for the Ministral-PII LLM extractor.

    Splits text into windows of at most ``chunk_size`` *real* tokens (Ministral's
    HuggingFace tokenizer) with ``overlap`` tokens shared between consecutive
    windows. Window boundaries are taken from the tokenizer's per-token char
    offsets (``tokenizers.Encoding.offsets``), so each chunk's ``start``/``end``
    are exact character positions into the ORIGINAL text and
    ``text[chunk.start:chunk.end] == chunk.text`` holds for every chunk. That
    invariant is what keeps the detector's downstream offset rebasing
    (chunk-local ``str.find`` + ``chunk.start``) correct.

    Why tokens, not the char×N proxy of :class:`FallbackChunker`: the 3B model's
    recall degrades with the number of tokens in context, and chars→tokens drifts
    with content (≈3.3-3.7 chars/token for French, ≈2-3 for IDs/base64, ≈1 for
    CJK). A char window can therefore silently overrun the token sweet-spot on
    dense / non-English input. Measuring the size axis in the unit the model is
    actually bounded by makes the configured budget honest (2048 means 2048
    tokens) and reproducible against the chunking benchmark, which uses this same
    tokenizer.

    Overlap is intentionally NOT de-duplicated here: an entity sitting in the
    shared region is emitted by both windows with identical GLOBAL offsets and is
    collapsed downstream by ``DetectionMerger`` (key = start/end/type/text); a
    boundary-straddling entity is recovered whole by the overlapping window and
    wins ``DetectionMerger``'s longest-span resolution.
    """

    def __init__(
        self,
        tokenizer: Any,
        chunk_size: int,
        overlap: int = 0,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        if chunk_size <= 0:
            raise ValueError(f"chunk_size ({chunk_size}) must be > 0")
        if overlap < 0:
            raise ValueError(f"overlap ({overlap}) must be >= 0")
        if overlap >= chunk_size:
            raise ValueError(
                f"overlap ({overlap}) must be < chunk_size ({chunk_size})"
            )

        self._tokenizer = tokenizer
        self._chunk_size = chunk_size
        self._overlap = overlap
        self._stride = chunk_size - overlap
        self._logger = logger or logging.getLogger(__name__)

    def chunk_text(self, text: str) -> List[ChunkResult]:
        """Split ``text`` into windows bounded by ``chunk_size`` real tokens."""
        if not text:
            return []

        offsets = self._token_offsets(text)
        if not offsets:
            # No content tokens (e.g. whitespace-only input): emit the whole text
            # as one chunk so callers never silently drop input.
            return [ChunkResult(text=text, start=0, end=len(text), token_count=0)]

        total = len(offsets)
        results: List[ChunkResult] = []
        i = 0
        while i < total:
            j = min(i + self._chunk_size, total)
            # Anchor the first window at 0 and extend the last to len(text) so no
            # leading/trailing char (whitespace, BOM) is dropped; interior cuts
            # ride exact token offsets, keeping coverage gapless.
            start_char = 0 if i == 0 else offsets[i][0]
            end_char = len(text) if j >= total else offsets[j - 1][1]
            results.append(ChunkResult(
                text=text[start_char:end_char],
                start=start_char,
                end=end_char,
                token_count=j - i,
            ))
            if j >= total:
                break
            i += self._stride

        self._logger.debug(
            "MinistralTokenChunker: %d chars -> %d chunks (chunk_size=%d, overlap=%d)",
            len(text), len(results), self._chunk_size, self._overlap,
        )
        return results

    def _token_offsets(self, text: str) -> List[Tuple[int, int]]:
        """Per-token ``(char_start, char_end)`` spans into ``text``.

        Encodes without special tokens so the offsets map 1:1 to content and the
        token budget matches what the model consumes. Mirrors the chunking
        benchmark's ``tokenizers.Encoding.offsets`` usage.
        """
        encoding = self._tokenizer.encode(text, add_special_tokens=False)
        offsets = getattr(encoding, "offsets", None)
        return list(offsets) if offsets else []

    def get_chunk_info(self) -> dict:
        return {
            "chunk_size": self._chunk_size,
            "overlap": self._overlap,
            "library": "tokenizers",
            "available": True,
            "tokenizer": type(self._tokenizer).__name__,
        }
