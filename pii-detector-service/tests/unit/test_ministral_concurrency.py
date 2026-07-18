"""Unit tests for the parallel Ministral chunk loop (the ``concurrency`` knob).

``MinistralDetector._extract_over_chunks`` sends chunks to LM Studio sequentially
when ``concurrency <= 1`` (historical behaviour) and across a bounded thread pool
when ``concurrency > 1``. These tests assert:

- functional equivalence between the sequential and parallel paths (same entities,
  same global offsets — completion order is irrelevant);
- the parallel path actually overlaps requests in flight;
- per-chunk fail-open still holds under concurrency;
- ``concurrency`` above the chunk count does not error (pool capped at #chunks).

All HTTP is mocked; no endpoint is contacted.
"""
from __future__ import annotations

import json
import threading
import time
from typing import Any, Dict, List
from unittest.mock import MagicMock

import httpx

from pii_detector.infrastructure.detector.ministral_detector import MinistralDetector


def _chat_response(pairs: List[Dict[str, str]]) -> MagicMock:
    response = MagicMock()
    response.raise_for_status = MagicMock()
    response.json = MagicMock(
        return_value={"choices": [{"message": {"content": json.dumps(pairs)}}]}
    )
    return response


def _configs(*pairs: tuple) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for pii_type, detector_label, threshold in pairs:
        out[f"MINISTRAL:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "MINISTRAL",
            "detector_label": detector_label,
        }
    return out


# Whitespace-token text split into 2-token windows -> deterministic 4 chunks,
# each carrying one locatable marker in its own window.
_TEXT = "alpha JeanX gamma IBANY zeta MAILZ eta theta"
_CONFIGS = _configs(
    ("PERSON", "PERSON", 0.0), ("IBAN", "IBAN", 0.0), ("EMAIL", "EMAIL", 0.0)
)


class _WhitespaceFakeTokenizer:
    def encode(self, text, add_special_tokens=True):  # noqa: ARG002
        import re

        class _Enc:
            offsets = [(m.start(), m.end()) for m in re.finditer(r"\S+", text)]

        return _Enc()


def _marker_pairs(chunk_text: str) -> List[Dict[str, str]]:
    pairs: List[Dict[str, str]] = []
    if "JeanX" in chunk_text:
        pairs.append({"text": "JeanX", "label": "PERSON"})
    if "IBANY" in chunk_text:
        pairs.append({"text": "IBANY", "label": "IBAN"})
    if "MAILZ" in chunk_text:
        pairs.append({"text": "MAILZ", "label": "EMAIL"})
    return pairs


def _detector_with(post) -> MinistralDetector:
    detector = MinistralDetector()
    detector._get_tokenizer = lambda: _WhitespaceFakeTokenizer()
    client = MagicMock()
    client.post = MagicMock(side_effect=post)
    detector._client = client
    return detector


def _entity_keys(entities) -> set:
    return {(e.start, e.end, e.pii_type) for e in entities}


class TestSequentialParallelEquivalence:
    def test_Should_ProduceSameEntities_When_ConcurrencyOneVsMany(self):
        def post(url, json=None):  # noqa: A002
            return _chat_response(_marker_pairs(json["messages"][1]["content"]))

        seq = _detector_with(post).detect_pii(
            _TEXT, pii_type_configs=_CONFIGS, chunk_size=2, overlap=0, concurrency=1
        )
        par = _detector_with(post).detect_pii(
            _TEXT, pii_type_configs=_CONFIGS, chunk_size=2, overlap=0, concurrency=4
        )

        assert _entity_keys(seq) == _entity_keys(par)
        # Sanity: the three markers were found with correct global offsets.
        assert {e.pii_type for e in par} == {"PERSON", "IBAN", "EMAIL"}
        for e in par:
            assert _TEXT[e.start:e.end] == e.text


class TestActuallyParallel:
    def test_Should_OverlapRequestsInFlight_When_ConcurrencyGreaterThanOne(self):
        lock = threading.Lock()
        state = {"current": 0, "max": 0}

        def post(url, json=None):  # noqa: A002
            with lock:
                state["current"] += 1
                state["max"] = max(state["max"], state["current"])
            time.sleep(0.05)
            with lock:
                state["current"] -= 1
            return _chat_response([])

        _detector_with(post).detect_pii(
            _TEXT, pii_type_configs=_CONFIGS, chunk_size=2, overlap=0, concurrency=3
        )
        # With 4 chunks and concurrency 3, at least two requests must have been
        # in flight simultaneously (proves the loop is genuinely parallel).
        assert state["max"] >= 2

    def test_Should_StaySequential_When_ConcurrencyIsOne(self):
        lock = threading.Lock()
        state = {"current": 0, "max": 0}

        def post(url, json=None):  # noqa: A002
            with lock:
                state["current"] += 1
                state["max"] = max(state["max"], state["current"])
            time.sleep(0.02)
            with lock:
                state["current"] -= 1
            return _chat_response([])

        _detector_with(post).detect_pii(
            _TEXT, pii_type_configs=_CONFIGS, chunk_size=2, overlap=0, concurrency=1
        )
        assert state["max"] == 1


class TestFailOpenUnderConcurrency:
    def test_Should_SkipFailedChunk_When_OneChunkRaisesInParallel(self):
        def post(url, json=None):  # noqa: A002
            chunk_text = json["messages"][1]["content"]
            if "JeanX" in chunk_text:
                raise httpx.ConnectError("endpoint unreachable")
            return _chat_response(_marker_pairs(chunk_text))

        entities = _detector_with(post).detect_pii(
            _TEXT, pii_type_configs=_CONFIGS, chunk_size=2, overlap=0, concurrency=4
        )
        # The failed chunk (PERSON) is dropped; the others still surface.
        assert {e.pii_type for e in entities} == {"IBAN", "EMAIL"}


class TestConcurrencyAboveChunkCount:
    def test_Should_NotError_When_ConcurrencyExceedsChunkCount(self):
        def post(url, json=None):  # noqa: A002
            return _chat_response(_marker_pairs(json["messages"][1]["content"]))

        entities = _detector_with(post).detect_pii(
            _TEXT, pii_type_configs=_CONFIGS, chunk_size=2, overlap=0, concurrency=99
        )
        assert {e.pii_type for e in entities} == {"PERSON", "IBAN", "EMAIL"}
