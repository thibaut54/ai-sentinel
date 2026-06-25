"""Document sources for the two benchmark tracks.

Track A (quality): mega-documents built by concatenating gold docs from
``pii-dataset-eval`` up to target token lengths, recomputing every span offset
into the concatenated text. Because we know all gold spans, value-level P/R/F1
is exact. Reaching very large lengths (e.g. 256k tokens) needs a large gold set:
if the available gold is too small for a target, the builder caps at the
achievable length and records it (no silent truncation, no doc repetition —
repeating docs would bias value-level scoring).

Track B (latency only): real corpus text files, sliced to the same target token
lengths so latency is comparable. No gold, so no quality is scored — these
measure inference time/throughput on realistic, structured, PII-sparse content.
"""
from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Tuple

from mistral_tokenizer import Tokenizer

# Separator inserted between concatenated gold docs (blank line => also a valid
# paragraph boundary, so the paragraph chunker has seams to snap to).
_SEP = "\n\n"

# Track B: only these extensions are treated as readable text (binaries excluded).
_TEXT_EXTS = {".txt", ".html", ".htm", ".csv", ".json", ".xml", ".log", ".md",
              ".yaml", ".yml", ".sql", ".js", ".sh", ".bash", ".crt", ".pem",
              ".mobileconfig", ".conf", ".ini", ".tsv", ""}


@dataclass
class GoldDoc:
    id: str
    dataset: str
    text: str
    spans: List[Tuple[int, int, str]] = field(default_factory=list)  # (start, end, canonical)


@dataclass
class Doc:
    id: str
    text: str
    target_tokens: int
    actual_tokens: int
    capped: bool
    gold: Optional[GoldDoc]  # None for Track B (latency only)


def load_gold(gold_dir: Path) -> List[GoldDoc]:
    """Load every ``*.jsonl`` gold file into :class:`GoldDoc` objects."""
    docs: List[GoldDoc] = []
    for path in sorted(gold_dir.glob("*.jsonl")):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                d = json.loads(line)
                spans = [(s["start"], s["end"], s["label"]) for s in d.get("spans", [])]
                docs.append(GoldDoc(d["id"], d.get("dataset", path.stem), d["text"], spans))
    return docs


def build_megadocs(gold: List[GoldDoc], tok: Tokenizer, targets: List[int],
                   reps: int, seed: int) -> List[Doc]:
    """Build ``reps`` mega-docs per target length by concatenating gold docs."""
    docs: List[Doc] = []
    for target in sorted(targets):
        for rep in range(reps):
            order = list(range(len(gold)))
            random.Random(seed + rep * 1000 + target).shuffle(order)
            doc = _concat_until(gold, order, tok, target)
            doc_id = f"A-{target}-r{rep}"
            doc.id = doc_id
            doc.gold.id = doc_id
            docs.append(doc)
    return docs


def _concat_until(gold: List[GoldDoc], order: List[int], tok: Tokenizer, target: int) -> Doc:
    parts: List[str] = []
    spans: List[Tuple[int, int, str]] = []
    offset = 0
    used = 0
    running_tokens = 0  # incremental sum -> O(n) tokenization instead of O(n^2)
    for i in order:
        g = gold[i]
        chunk_text = (_SEP if parts else "") + g.text
        base = offset + (len(_SEP) if parts else 0)
        for (s, e, label) in g.spans:
            spans.append((base + s, base + e, label))
        parts.append(chunk_text)
        offset += len(chunk_text)
        running_tokens += tok.count(chunk_text)
        used += 1
        if running_tokens >= target:
            break
    text = "".join(parts)
    actual = tok.count(text)  # single precise count of the assembled doc
    capped = actual < target and used >= len(order)
    gold_doc = GoldDoc(id="", dataset="megadoc", text=text, spans=spans)
    return Doc(id="", text=text, target_tokens=target, actual_tokens=actual,
               capped=capped, gold=gold_doc)


def build_corpus_docs(corpus_dir: Path, tok: Tokenizer, targets: List[int],
                      max_files: int) -> List[Doc]:
    """Slice the largest readable corpus text files to each target length."""
    files = _largest_text_files(corpus_dir, max_files)
    docs: List[Doc] = []
    for target in sorted(targets):
        for path in files:
            text = _read_text(path)
            sliced, actual, capped = _slice_to_tokens(text, tok, target)
            docs.append(Doc(id=f"B-{target}-{path.stem}", text=sliced,
                            target_tokens=target, actual_tokens=actual,
                            capped=capped, gold=None))
    return docs


def _largest_text_files(corpus_dir: Path, max_files: int) -> List[Path]:
    candidates = [p for p in corpus_dir.rglob("*")
                  if p.is_file() and p.suffix.lower() in _TEXT_EXTS]
    candidates.sort(key=lambda p: p.stat().st_size, reverse=True)
    return candidates[:max_files]


def _read_text(path: Path) -> str:
    data = path.read_bytes()
    for enc in ("utf-8", "latin-1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", "replace")


def _slice_to_tokens(text: str, tok: Tokenizer, target: int) -> Tuple[str, int, bool]:
    """Return a prefix of ``text`` of about ``target`` tokens.

    Tokenises only an estimated prefix (not the whole multi-MB file): estimate
    chars/token on a sample, slice a slightly oversized prefix, then cut exactly
    on the target-th token. Grows the prefix if the estimate fell short."""
    if not text:
        return "", 0, True
    sample = text[:200_000]
    cpt = len(sample) / max(1, tok.count(sample))
    approx = min(len(text), int(target * cpt * 1.3) + 128)
    while True:
        prefix = text[:approx]
        offs = tok.offsets(prefix)
        if len(offs) >= target:
            end = offs[target - 1][1]
            sliced = text[:end]
            return sliced, tok.count(sliced), False
        if approx >= len(text):  # whole file is shorter than target
            return text, len(offs), True
        approx = min(len(text), approx * 2)
