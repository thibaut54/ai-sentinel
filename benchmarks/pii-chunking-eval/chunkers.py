"""Chunking strategies under test.

Each strategy splits a document into chunks whose target size is expressed in
tokens, with an optional overlap fraction. Every chunk carries its char span in
the original document so detections can be mapped back. Five boundary policies
plus a no-chunk baseline:

* ``whole``      — the whole document in a single request (today's behaviour).
* ``char``       — naive fixed-width character windows (may split tokens/values).
* ``token``      — cut exactly on token boundaries (never splits a token).
* ``line``       — extend each cut to the next newline (never splits a line).
* ``sentence``   — extend each cut to the next sentence terminator.
* ``paragraph``  — extend each cut to the next blank line.

The structural policies (line/sentence/paragraph) are the honest, production-
viable approximation of "do not split a PII value across a chunk boundary":
they cut on natural text seams without needing to know where the PII is (which,
on real documents, we don't).
"""
from __future__ import annotations

import bisect
import re
from dataclasses import dataclass
from typing import List

from mistral_tokenizer import Tokenizer

BOUNDARIES = ("char", "token", "line", "sentence", "paragraph")

# How far past the target a structural cut may extend to reach a seam before we
# give up and hard-cut at the target. Without this bound, sparse-seam text (or a
# seamless blob like minified HTML) produces chunks far larger than requested —
# distorting the size axis and blowing past the request timeout.
_BOUNDARY_TOLERANCE = 0.25

# Sentence terminator followed by whitespace (kept simple and language-agnostic).
_SENTENCE_END = re.compile(r"[.!?][\"')\]]?\s")
_PARAGRAPH_END = re.compile(r"\n[ \t]*\n")


@dataclass(frozen=True)
class Chunk:
    index: int
    start: int
    end: int
    text: str


def chunk(text: str, tok: Tokenizer, boundary: str, size_tokens: int, overlap: float) -> List[Chunk]:
    """Split ``text`` per ``boundary`` into ~``size_tokens`` chunks with ``overlap``."""
    if boundary == "whole" or not text:
        return [Chunk(0, 0, len(text), text)]
    if boundary == "token":
        return _token_chunks(text, tok, size_tokens, overlap)
    if boundary == "char":
        return _char_chunks(text, tok, size_tokens, overlap)
    if boundary in ("line", "sentence", "paragraph"):
        return _snapped_chunks(text, tok, size_tokens, overlap, boundary)
    raise ValueError(f"unknown boundary policy: {boundary}")


def _token_chunks(text: str, tok: Tokenizer, size: int, overlap: float) -> List[Chunk]:
    offs = tok.offsets(text)
    n = len(offs)
    if n == 0:
        return [Chunk(0, 0, len(text), text)]
    step = max(1, size - round(size * overlap))
    chunks: List[Chunk] = []
    i = 0
    idx = 0
    while i < n:
        j = min(i + size, n)
        start = offs[i][0]
        end = offs[j - 1][1]
        chunks.append(Chunk(idx, start, end, text[start:end]))
        idx += 1
        if j >= n:
            break
        i += step
    return chunks


def _chars_per_token(text: str, tok: Tokenizer) -> float:
    n_tok = max(1, tok.count(text))
    return len(text) / n_tok


def _char_chunks(text: str, tok: Tokenizer, size: int, overlap: float) -> List[Chunk]:
    """Naive fixed-width character windows (the baseline that ignores tokens).

    Sized via the document's average chars/token, then each chunk is token-capped
    at ``size * (1 + _BOUNDARY_TOLERANCE)`` so a token-dense region can't silently
    produce an oversized (timeout-prone) chunk. The next window starts from the
    actual chunk end, so coverage stays gapless even after a cap."""
    width = max(1, round(size * _chars_per_token(text, tok)))
    overlap_chars = round(width * overlap)
    cap = round(size * (1 + _BOUNDARY_TOLERANCE))
    n = len(text)
    chunks: List[Chunk] = []
    i = 0
    idx = 0
    while i < n:
        end = _cap_tokens(text, i, min(i + width, n), cap, tok)
        chunks.append(Chunk(idx, i, end, text[i:end]))
        idx += 1
        if end >= n:
            break
        i = max(i + 1, end - overlap_chars)
    return chunks


def _snapped_chunks(text: str, tok: Tokenizer, size: int, overlap: float, kind: str) -> List[Chunk]:
    """Token-anchored windows whose cut is snapped to a natural seam.

    Size and overlap are in TOKENS (not chars), so the policy is faithful even
    where chars/token varies sharply (PII-dense text). The forward snap may reach
    at most ``size * _BOUNDARY_TOLERANCE`` extra tokens; beyond that we hard-cut at
    the target token. The next window is re-anchored in token space from the
    snapped end, keeping coverage gapless and overshoot bounded in tokens."""
    offs = tok.offsets(text)
    if not offs:
        return [Chunk(0, 0, len(text), text)]
    n = len(offs)
    ends = [e for (_, e) in offs]
    overlap_tokens = round(size * overlap)
    max_extend = max(1, round(size * _BOUNDARY_TOLERANCE))
    chunks: List[Chunk] = []
    i = 0
    idx = 0
    while i < n:
        j = min(i + size, n)
        start_char = offs[i][0]
        if j >= n:
            end_char = len(text)
        else:
            target_char = offs[j - 1][1]
            limit_char = offs[min(n, j + max_extend) - 1][1]
            end_char = _snap_forward(text, target_char, kind, limit_char)
        chunks.append(Chunk(idx, start_char, end_char, text[start_char:end_char]))
        idx += 1
        if end_char >= len(text):
            break
        i_end = bisect.bisect_left(ends, end_char)
        i = max(i + 1, i_end - overlap_tokens)
    return chunks


def _cap_tokens(text: str, start: int, end: int, cap: int, tok: Tokenizer) -> int:
    """Shrink ``end`` so ``text[start:end]`` holds at most ``cap`` tokens."""
    offs = tok.offsets(text[start:end])
    if len(offs) <= cap:
        return end
    return start + offs[cap - 1][1]


def _snap_forward(text: str, pos: int, kind: str, limit: int) -> int:
    """Next ``kind`` boundary at/after ``pos`` but no further than ``limit``;
    falls back to ``pos`` (a hard cut at the target) when none is in range."""
    if kind == "line":
        nl = text.find("\n", pos, limit)
        return nl + 1 if nl >= 0 else pos
    if kind == "paragraph":
        m = _PARAGRAPH_END.search(text, pos, limit)
        return m.end() if m is not None else pos
    if kind == "sentence":
        m = _SENTENCE_END.search(text, pos, limit)
        return m.end() if m is not None else pos
    return pos
