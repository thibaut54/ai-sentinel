"""
Semantic text chunking with token-aware splitting.

This module provides intelligent text chunking that respects semantic boundaries
(sentences, paragraphs) while ensuring no chunk exceeds token limits.
Critical for models like GLiNER that have internal sentence-level token limits.
"""

import logging
import re
import time
from dataclasses import dataclass
from typing import Any, List, Optional, Tuple

try:
    import semchunk
    SEMCHUNK_AVAILABLE = True
except ImportError:
    SEMCHUNK_AVAILABLE = False


@dataclass
class ChunkResult:
    """Result of text chunking operation."""
    text: str
    start: int
    end: int
    token_count: Optional[int] = None


class SemanticTextChunker:
    """
    Token-aware semantic text chunker.
    
    Uses semchunk library to intelligently split text into chunks that:
    1. Respect semantic boundaries (sentences, paragraphs)
    2. Never exceed specified token limits
    3. Can overlap for context preservation at boundaries
    
    Critical for GLiNER which has a 768-token limit per sentence internally.
    Beneficial for other transformer models to optimize processing.
    """

    def __init__(
        self,
        tokenizer: Any,
        chunk_size: int,
        overlap: int = 0,
        logger: Optional[logging.Logger] = None
    ):
        """
        Initialize semantic chunker.
        
        Args:
            tokenizer: HuggingFace tokenizer or compatible tokenizer
            chunk_size: Maximum tokens per chunk (hard limit)
            overlap: Number of tokens to overlap between chunks (default: 0)
            logger: Optional logger instance
            
        Raises:
            ImportError: If semchunk is not installed
            ValueError: If chunk_size <= overlap
        """
        if not SEMCHUNK_AVAILABLE:
            raise ImportError(
                "semchunk library is required for semantic chunking. "
                "Install it with: pip install semchunk"
            )
        
        if chunk_size <= overlap:
            raise ValueError(f"chunk_size ({chunk_size}) must be greater than overlap ({overlap})")

        self.tokenizer = tokenizer
        self.chunk_size = chunk_size
        self.overlap = overlap
        self.logger = logger or logging.getLogger(__name__)
        
        # Initialize semchunk chunker
        # semchunk API: chunkerify(tokenizer, chunk_size, memoize=True)
        # overlap is handled differently - not a direct parameter
        self.chunker = semchunk.chunkerify(
            tokenizer, 
            chunk_size
        )

        self.logger.info(
            f"SemanticTextChunker initialized: chunk_size={chunk_size}, overlap={overlap}"
        )

    def chunk_text(self, text: str) -> List[ChunkResult]:
        """
        Split text into semantic chunks respecting token limits.
        
        Args:
            text: Text to chunk
            
        Returns:
            List of ChunkResult objects with text and position info
            
        Raises:
            RuntimeError: If chunking fails
        """
        start_time = time.time()

        if not text:
            return []
        
        try:
            # Use semchunk to get intelligent chunks
            chunks = self.chunker(text)
            
            # Convert to ChunkResult objects with position tracking
            results = []
            current_pos = 0
            
            for chunk_text in chunks:
                # Find chunk position in original text
                # Account for possible whitespace normalization
                start = text.find(chunk_text, current_pos)
                if start == -1:
                    # Fallback: chunk might be normalized, use current position
                    start = current_pos
                
                end = start + len(chunk_text)
                
                result = ChunkResult(
                    text=chunk_text,
                    start=start,
                    end=end,
                    token_count=None  # semchunk doesn't provide this
                )
                results.append(result)
                
                # Move position forward for next search
                current_pos = end
            
            self.logger.debug(
                f"Chunked {len(text)} chars into {len(results)} semantic chunks"
            )
            elapsed_time = time.time() - start_time
            print(f"[chunk_text] Chunking text completed in {elapsed_time:.2f}s")
            return results
            
        except Exception as e:
            self.logger.error(f"Semantic chunking failed: {str(e)}")
            raise RuntimeError(f"Failed to chunk text: {str(e)}") from e

    def get_chunk_info(self) -> dict:
        """
        Get information about chunker configuration.
        
        Returns:
            Dictionary with chunker settings
        """
        return {
            "chunk_size": self.chunk_size,
            "overlap": self.overlap,
            "library": "semchunk",
            "available": SEMCHUNK_AVAILABLE
        }


class FallbackChunker:
    """
    Simple character-based chunker with overlap support.

    This implementation splits on character boundaries with approximate token
    estimation. Works well for GLiNER PII detection as proven by testing.
    Uses conservative 3 chars/token ratio for safety with multilingual text.
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
        # would make windows re-scan more than they advance). Mirrors the guards
        # in SemanticTextChunker / WhitespaceWordWindowChunker so a bad value
        # fails loudly instead of producing degenerate, infinitely-overlapping
        # chunks.
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


class WhitespaceWordWindowChunker:
    """
    Word-window chunker aligned with GLiNER's internal ``WhitespaceTokenSplitter``.

    GLiNER applies its ``max_len`` limit (e.g. 384) to *whitespace tokens* — words
    plus isolated punctuation — not to subword tokens. Char-based or subword-based
    chunking upstream may leave chunks that exceed the word limit on dense input
    (URLs, identifiers, code), causing GLiNER to silently truncate beyond
    ``max_len`` and drop entities at the tail.

    This chunker reproduces the exact regex GLiNER uses
    (``\\w+(?:[-_]\\w+)*|\\S``) to count whitespace tokens, so configuring
    ``chunk_size`` strictly below ``max_len`` guarantees no internal truncation.

    Char positions are taken from the regex match offsets, keeping
    char-based offset alignment with the rest of the pipeline.
    """

    # Aligned with gliner.data_processing.tokenizer.WhitespaceTokenSplitter
    _GLINER_WHITESPACE_PATTERN = re.compile(r"\w+(?:[-_]\w+)*|\S")

    def __init__(
        self,
        chunk_size: int,
        overlap: int,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        if chunk_size <= overlap:
            raise ValueError(
                f"chunk_size ({chunk_size}) must be greater than overlap ({overlap})"
            )
        if chunk_size <= 0:
            raise ValueError(f"chunk_size ({chunk_size}) must be > 0")

        self._chunk_size = chunk_size
        self._overlap = overlap
        self._stride = chunk_size - overlap
        self._logger = logger or logging.getLogger(__name__)

        self._logger.info(
            "WhitespaceWordWindowChunker initialized: "
            f"chunk_size={chunk_size} (whitespace tokens), overlap={overlap}, "
            "aligned with GLiNER WhitespaceTokenSplitter"
        )

    def chunk_text(self, text: str) -> List[ChunkResult]:
        """Split ``text`` into windows bounded by GLiNER-aligned token counts."""
        if not text:
            return []

        token_spans: List[Tuple[int, int]] = [
            (m.start(), m.end())
            for m in self._GLINER_WHITESPACE_PATTERN.finditer(text)
        ]
        if not token_spans:
            return []

        total = len(token_spans)
        if total <= self._chunk_size:
            start_char = token_spans[0][0]
            end_char = token_spans[-1][1]
            return [ChunkResult(
                text=text[start_char:end_char],
                start=start_char,
                end=end_char,
                token_count=total,
            )]

        chunks: List[ChunkResult] = []
        i = 0
        while i < total:
            j = min(i + self._chunk_size, total)
            start_char = token_spans[i][0]
            end_char = token_spans[j - 1][1]
            chunks.append(ChunkResult(
                text=text[start_char:end_char],
                start=start_char,
                end=end_char,
                token_count=j - i,
            ))
            if j >= total:
                break
            i += self._stride
        return chunks

    def get_chunk_info(self) -> dict:
        return {
            "chunk_size": self._chunk_size,
            "overlap": self._overlap,
            "library": "gliner-aligned-whitespace",
            "available": True,
        }


class GlinerSubwordChunker:
    """
    Token-aware chunker aligned with GLiNER's actual subword tokenizer.

    GLiNER's max_len (e.g. 384) applies to the **subword tokens** produced by its
    HuggingFace tokenizer (DeBERTa for ``nvidia/gliner-PII``), not to whitespace
    tokens. On accent-rich / contraction-rich French text, 380 whitespace tokens
    can correspond to 600+ subword tokens, causing GLiNER to silently truncate.

    This chunker uses ``semchunk.chunkerify(tokenizer, chunk_size)`` so the chunk
    size is measured in the same unit GLiNER consumes, with native ``overlap``
    support and exact char-offset return (``chunks[i] == text[off[i][0]:off[i][1]]``).
    """

    def __init__(
        self,
        tokenizer: Any,
        chunk_size: int,
        overlap: int,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        if not SEMCHUNK_AVAILABLE:
            raise ImportError(
                "semchunk library is required for GlinerSubwordChunker. "
                "Install it with: pip install semchunk>=2.2.0"
            )
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
        self._logger = logger or logging.getLogger(__name__)

        # semchunk.chunkerify accepts a HF tokenizer, a model name, or a callable.
        # The returned chunker is reusable and memoized.
        self._chunker = semchunk.chunkerify(tokenizer, chunk_size)

        self._logger.info(
            "GlinerSubwordChunker initialized: chunk_size=%d (subword tokens), "
            "overlap=%d, tokenizer=%s",
            chunk_size,
            overlap,
            type(tokenizer).__name__,
        )

    def chunk_text(self, text: str) -> List[ChunkResult]:
        """Split ``text`` into chunks bounded by ``chunk_size`` subword tokens."""
        if not text:
            return []

        # semchunk returns (chunks, offsets) with offsets=True; offsets is a list
        # of (start, end) char positions guaranteeing chunks[i] == text[s:e].
        # The overlap parameter is supported natively since semchunk 2.2.
        try:
            chunks, offsets = self._chunker(
                text,
                offsets=True,
                overlap=self._overlap if self._overlap > 0 else None,
            )
        except TypeError:
            # Older semchunk without overlap kwarg: fall back to no-overlap call.
            chunks, offsets = self._chunker(text, offsets=True)
            self._logger.warning(
                "Installed semchunk does not support overlap kwarg; "
                "running without overlap (chunks=%d)",
                len(chunks),
            )

        results: List[ChunkResult] = []
        for chunk_text, (start, end) in zip(chunks, offsets):
            # Encode without special tokens to match how the chunk size is
            # measured by semchunk (raw subword token count). Lets predict_chunked
            # log accurate token coverage instead of falling back to 0.
            try:
                token_count = len(self._tokenizer.encode(chunk_text, add_special_tokens=False))
            except Exception:
                token_count = None
            results.append(ChunkResult(
                text=chunk_text,
                start=int(start),
                end=int(end),
                token_count=token_count,
            ))

        self._logger.debug(
            "GlinerSubwordChunker: %d chars -> %d chunks (chunk_size=%d, overlap=%d)",
            len(text), len(results), self._chunk_size, self._overlap,
        )
        return results

    def get_chunk_info(self) -> dict:
        return {
            "chunk_size": self._chunk_size,
            "overlap": self._overlap,
            "library": "semchunk",
            "available": SEMCHUNK_AVAILABLE,
            "tokenizer": type(self._tokenizer).__name__,
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


def create_chunker(
    tokenizer: Optional[Any] = None,
    chunk_size: int = 378,
    overlap: int = 50,
    use_semantic: bool = False,
    logger: Optional[logging.Logger] = None
) -> Any:
    """
    Factory function to create appropriate chunker.

    Args:
        tokenizer: HuggingFace tokenizer (required for semantic chunking)
        chunk_size: Maximum tokens per chunk (default: 378 for GLiNER)
        overlap: Number of tokens to overlap (default: 50)
        use_semantic: Use semantic chunking (default: False, semchunk doesn't support overlap)
        logger: Optional logger instance

    Returns:
        SemanticTextChunker or FallbackChunker instance

    Note:
        Character-based chunking is preferred because:
        1. It properly supports overlap for context continuity
        2. It has been tested and works well with GLiNER PII detection
        3. semchunk library doesn't support overlap parameter
    """
    logger = logger or logging.getLogger(__name__)

    # Use semantic chunking only if explicitly requested AND overlap is 0
    # (semchunk doesn't support overlap)
    if use_semantic and SEMCHUNK_AVAILABLE and tokenizer is not None and overlap == 0:
        try:
            return SemanticTextChunker(
                tokenizer=tokenizer,
                chunk_size=chunk_size,
                overlap=overlap,
                logger=logger
            )
        except Exception as e:
            logger.warning(f"Failed to create semantic chunker: {e}, using character-based")

    return FallbackChunker(
        chunk_size=chunk_size,
        overlap=overlap,
        logger=logger
    )
