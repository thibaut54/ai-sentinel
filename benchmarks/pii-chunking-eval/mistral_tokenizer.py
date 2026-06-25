"""Tokenizer wrapper for the chunking benchmark.

The whole point of the benchmark is to compare chunking strategies whose size
axis is expressed in *tokens*, with 256k tokens (Ministral's advertised context)
as the headline target. To make those budgets faithful we load the model's real
tokenizer; a char-ratio approximation is available as an explicit, clearly
labelled fallback for offline/plumbing runs.

Design notes
------------
* We use the lightweight ``tokenizers`` library (no torch / transformers needed).
  ``--tokenizer`` accepts a HF repo id (downloaded + cached via ``HF_HOME``), a
  ``tokenizer.json`` file path, or a directory containing one.
* For token-boundary chunking we need each token's char span in the *original*
  string. ``tokenizers`` returns those as ``Encoding.offsets``. For ByteLevel
  BPE on non-ASCII text these offsets can drift by a char or two; the gold
  (Track A, where quality is measured) is ASCII English so token boundaries are
  exact there, and Track B is latency-only so minor boundary drift is harmless.
"""
from __future__ import annotations

import math
import os
from pathlib import Path
from typing import List, Tuple


class Tokenizer:
    """Thin facade over a real tokenizer, or a char-ratio approximation."""

    def __init__(self, name: str, real, chars_per_token: float):
        self.name = name
        self._real = real  # tokenizers.Tokenizer or None (fallback)
        self._cpt = max(1.0, float(chars_per_token))

    @property
    def is_real(self) -> bool:
        return self._real is not None

    @classmethod
    def char_approx(cls, chars_per_token: float = 3.8) -> "Tokenizer":
        """A network-free char-ratio tokenizer (used by the offline self-test)."""
        return cls(f"char-approx(cpt={chars_per_token})", None, chars_per_token)

    # -- loading ------------------------------------------------------------
    @classmethod
    def load(cls, spec: str, allow_fallback: bool, chars_per_token: float = 3.8) -> "Tokenizer":
        """Load the real tokenizer for ``spec``; fall back to char-ratio only if
        ``allow_fallback`` is set (otherwise raise with actionable guidance)."""
        try:
            from tokenizers import Tokenizer as HfTokenizer
        except ImportError as exc:  # pragma: no cover - environment dependent
            if allow_fallback:
                return cls.char_approx(chars_per_token)
            raise RuntimeError(
                "The 'tokenizers' package is required for exact token budgets. "
                "Install it (`pip install -r requirements.txt`) or pass "
                "--allow-tokenizer-fallback to use a char-ratio approximation."
            ) from exc

        real = cls._try_load(HfTokenizer, spec)
        if real is not None:
            return cls(spec, real, chars_per_token)
        if allow_fallback:
            print(f"[tokenizer] WARNING: could not load '{spec}', "
                  f"falling back to char-approx (cpt={chars_per_token}).")
            return cls.char_approx(chars_per_token)
        raise RuntimeError(
            f"Could not load tokenizer '{spec}'. Provide a HF repo id, a "
            f"tokenizer.json path, or a directory; ensure network/HF_HOME cache "
            f"is available, or pass --allow-tokenizer-fallback."
        )

    @staticmethod
    def _try_load(HfTokenizer, spec: str):
        path = Path(spec)
        try:
            if path.is_file():
                return HfTokenizer.from_file(str(path))
            if path.is_dir():
                candidate = path / "tokenizer.json"
                if candidate.is_file():
                    return HfTokenizer.from_file(str(candidate))
                return None
            # Treat as a HF repo id (uses HF_HOME for caching).
            return HfTokenizer.from_pretrained(spec)
        except Exception as exc:  # noqa: BLE001 - any load failure -> fallback path
            print(f"[tokenizer] load error for '{spec}': {exc}")
            return None

    # -- API ----------------------------------------------------------------
    def count(self, text: str) -> int:
        if not text:
            return 0
        if self._real is not None:
            return len(self._real.encode(text, add_special_tokens=False).ids)
        return int(math.ceil(len(text) / self._cpt))

    def offsets(self, text: str) -> List[Tuple[int, int]]:
        """Per-token ``(char_start, char_end)`` spans into ``text``.

        For the fallback, synthesise fixed-width pseudo-tokens so the chunkers
        work unchanged."""
        if not text:
            return []
        if self._real is not None:
            enc = self._real.encode(text, add_special_tokens=False)
            # Drop zero-width offsets that some post-processors emit.
            return [(s, e) for (s, e) in enc.offsets if e > s]
        step = max(1, int(round(self._cpt)))
        n = len(text)
        return [(i, min(i + step, n)) for i in range(0, n, step)]
