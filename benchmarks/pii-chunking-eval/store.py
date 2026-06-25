"""Durable, resumable state: results, failures, and a response cache.

* ``results.jsonl``  — one line per completed (track, doc, config) unit. The set
  of their keys is the *done-set*: a re-run skips them, so the benchmark resumes
  exactly where it stopped.
* ``failures.jsonl`` — one line per failed chunk call (timeout / HTTP / parse).
  ``--retry-failures`` re-runs only the units that failed and are not yet done.
* ``cache.jsonl``    — content-addressed LLM responses, keyed by
  ``sha256(model + params + chunk_text)``. This gives chunk-level resume (a
  crashed unit re-runs, but already-called chunks return instantly) and replay
  (temperature=0 is deterministic), and deduplicates identical chunks.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Dict, List, Optional, Set

from llmclient import CallResult


def call_key(model: str, max_tokens: int, json_schema: bool, chunk_text: str) -> str:
    h = hashlib.sha256()
    h.update(f"{model}\n{max_tokens}\n{json_schema}\n".encode("utf-8"))
    h.update(chunk_text.encode("utf-8"))
    return h.hexdigest()


def unit_key(track: str, doc_id: str, config_id: str) -> str:
    return f"{track}|{doc_id}|{config_id}"


class Store:
    def __init__(self, out_dir: Path):
        self.out_dir = out_dir
        out_dir.mkdir(parents=True, exist_ok=True)
        self.results_path = out_dir / "results.jsonl"
        self.failures_path = out_dir / "failures.jsonl"
        self.cache_path = out_dir / "cache.jsonl"
        self._done: Set[str] = set()
        self._cache: Dict[str, dict] = {}
        self._failed_units: Set[str] = set()
        self._load()

    def _load(self) -> None:
        if self.results_path.exists():
            for row in _iter_jsonl(self.results_path):
                if "unit_key" in row:
                    self._done.add(row["unit_key"])
        if self.cache_path.exists():
            for row in _iter_jsonl(self.cache_path):
                self._cache[row["k"]] = row["v"]
        if self.failures_path.exists():
            for row in _iter_jsonl(self.failures_path):
                if "unit_key" in row:
                    self._failed_units.add(row["unit_key"])

    # -- done-set -----------------------------------------------------------
    def is_done(self, key: str) -> bool:
        return key in self._done

    def retry_units(self) -> Set[str]:
        """Units that failed and are not yet completed."""
        return self._failed_units - self._done

    # -- results / failures -------------------------------------------------
    def add_result(self, row: dict) -> None:
        _append(self.results_path, row)
        self._done.add(row["unit_key"])

    def add_failure(self, row: dict) -> None:
        _append(self.failures_path, row)
        if "unit_key" in row:
            self._failed_units.add(row["unit_key"])

    def load_results(self) -> List[dict]:
        return list(_iter_jsonl(self.results_path)) if self.results_path.exists() else []

    def load_failures(self) -> List[dict]:
        return list(_iter_jsonl(self.failures_path)) if self.failures_path.exists() else []

    # -- cache --------------------------------------------------------------
    def cache_get(self, key: str) -> Optional[CallResult]:
        v = self._cache.get(key)
        return CallResult.from_cache(v) if v is not None else None

    def cache_put(self, key: str, result: CallResult) -> None:
        v = result.as_cache()
        self._cache[key] = v
        _append(self.cache_path, {"k": key, "v": v})


def _append(path: Path, row: dict) -> None:
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        fh.flush()


def _iter_jsonl(path: Path):
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                yield json.loads(line)
