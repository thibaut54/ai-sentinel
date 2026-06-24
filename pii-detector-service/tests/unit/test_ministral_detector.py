"""
Unit tests for ``MinistralDetector``.

Ministral-PII is a generative LLM extractor served behind an OpenAI-compatible
``/chat/completions`` endpoint. It returns a JSON array of ``{text, label}``
objects which the detector maps to canonical ``pii_type`` and locates back in the
source to recover character offsets. All tests here mock the HTTP client so no
remote endpoint is contacted.

Coverage focuses on:
- label -> canonical pii_type mapping
- global offset rebasing across multiple chunks
- source == DetectorSource.MINISTRAL and judge_status default (NOT_AUDITED)
- per-chunk HTTP failure does not crash detect_pii (fail-open partial)
- tolerant JSON-array parsing (markdown fences / surrounding prose)
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock

import httpx
import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.judge_status import JudgeStatus
from pii_detector.infrastructure.detector.ministral_detector import MinistralDetector


def _chat_response(pairs: List[Dict[str, str]], as_text: Optional[str] = None) -> MagicMock:
    """Build a fake httpx response whose body is an OpenAI chat completion.

    ``pairs`` is rendered as the JSON array content unless ``as_text`` is given
    (used to exercise the tolerant parser with fences / prose).
    """
    import json

    content = as_text if as_text is not None else json.dumps(pairs)
    response = MagicMock()
    response.raise_for_status = MagicMock()
    response.json = MagicMock(return_value={
        "choices": [{"message": {"content": content}}]
    })
    return response


def _build_detector(post_side_effect) -> MinistralDetector:
    """Wire a detector whose httpx client ``post`` is mocked.

    ``post_side_effect`` may be a single response, a list (one per chunk call),
    or a callable raising to simulate failures.
    """
    detector = MinistralDetector()
    client = MagicMock()
    client.post = MagicMock(side_effect=post_side_effect)
    detector._client = client
    return detector


def _configs(*pairs: tuple) -> Dict[str, Any]:
    """Build MINISTRAL pii_type_configs keyed by ``MINISTRAL:<type>``.

    Each ``pair`` is ``(pii_type, detector_label, threshold)``.
    """
    out: Dict[str, Any] = {}
    for pii_type, detector_label, threshold in pairs:
        out[f"MINISTRAL:{pii_type}"] = {
            "enabled": True,
            "threshold": threshold,
            "detector": "MINISTRAL",
            "detector_label": detector_label,
        }
    return out


class TestMinistralDetectorBasics:
    def test_Should_HaveEnvDrivenModelId_When_DefaultsApply(self):
        detector = MinistralDetector()
        assert detector.model_id == "ministral-3b-pii-preview@q8_0"

    def test_Should_ReturnEmptyList_When_TextIsEmpty(self):
        detector = _build_detector([_chat_response([])])
        assert detector.detect_pii("") == []

    def test_Should_ReturnEntitiesWithMinistralSource_When_LlmDetects(self):
        detector = _build_detector(
            [_chat_response([{"text": "john@acme.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("Contact: john@acme.com", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "EMAIL"
        assert entities[0].source is DetectorSource.MINISTRAL
        assert entities[0].start == len("Contact: ")
        assert entities[0].end == len("Contact: john@acme.com")


class TestLabelMapping:
    def test_Should_MapDetectorLabelToCanonicalPiiType_When_LabelMatchesConfig(self):
        detector = _build_detector(
            [_chat_response([{"text": "4111 1111 1111 1111", "label": "CREDITCARD"}])]
        )
        configs = _configs(("CREDIT_CARD", "CREDITCARD", 0.0))
        entities = detector.detect_pii("4111 1111 1111 1111", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "CREDIT_CARD"

    def test_Should_SkipUnknownLabels_When_LabelNotInConfig(self):
        detector = _build_detector([_chat_response([
            {"text": "blue", "label": "EYECOLOR"},
            {"text": "john@acme.com", "label": "EMAIL"},
        ])])
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("blue john@acme.com", pii_type_configs=configs)
        assert {e.pii_type for e in entities} == {"EMAIL"}

    def test_Should_SkipPair_When_TextNotFoundInChunk(self):
        detector = _build_detector(
            [_chat_response([{"text": "hallucinated@nowhere.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        assert detector.detect_pii("no pii here", pii_type_configs=configs) == []


class TestOffsetRebasingAcrossChunks:
    def test_Should_RebaseOffsetsToGlobalCoordinates_When_TwoChunks(self):
        # chunk_size=1 token * chars_per_token=4 -> 4-char windows, overlap 0,
        # so the text is split into multiple non-overlapping chunks. Each chunk
        # call returns its own entity; offsets must be rebased to GLOBAL coords.
        first = "AAA "          # chunk 0 -> [0:?]
        text = "AAA name BB id99"
        detector = MinistralDetector()
        client = MagicMock()

        def fake_post(url, json=None):
            chunk_text = json["messages"][1]["content"]
            if "name" in chunk_text:
                return _chat_response([{"text": "name", "label": "PERSON"}])
            if "id99" in chunk_text:
                return _chat_response([{"text": "id99", "label": "ID"}])
            return _chat_response([])

        client.post = MagicMock(side_effect=fake_post)
        detector._client = client
        configs = _configs(("PERSON", "PERSON", 0.0), ("ID", "ID", 0.0))

        entities = detector.detect_pii(
            text, pii_type_configs=configs, chunk_size=2, overlap=0
        )
        by_type = {e.pii_type: e for e in entities}
        assert "PERSON" in by_type and "ID" in by_type
        # Global offsets must match the actual positions in the FULL text.
        assert text[by_type["PERSON"].start: by_type["PERSON"].end] == "name"
        assert text[by_type["ID"].start: by_type["ID"].end] == "id99"
        assert by_type["ID"].start == text.index("id99")


class TestJudgeStatusDefault:
    def test_Should_NotSetJudgeStatus_So_DefaultsToNotAudited(self):
        detector = _build_detector(
            [_chat_response([{"text": "john@acme.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("john@acme.com", pii_type_configs=configs)
        assert len(entities) == 1
        # The detector must NOT attach judge_status (mirrors openmed/gliner2);
        # it defaults to NOT_AUDITED in the gRPC response builder.
        assert entities[0].get("judge_status", JudgeStatus.NOT_AUDITED) is JudgeStatus.NOT_AUDITED


class TestFailOpenPerChunk:
    def test_Should_NotCrash_When_OneChunkHttpFails(self):
        text = "AAA name BB id99"

        def fake_post(url, json=None):
            chunk_text = json["messages"][1]["content"]
            if "name" in chunk_text:
                raise httpx.ConnectError("endpoint unreachable")
            if "id99" in chunk_text:
                return _chat_response([{"text": "id99", "label": "ID"}])
            return _chat_response([])

        detector = MinistralDetector()
        client = MagicMock()
        client.post = MagicMock(side_effect=fake_post)
        detector._client = client
        configs = _configs(("PERSON", "PERSON", 0.0), ("ID", "ID", 0.0))

        # The failed chunk is skipped; the healthy chunk still yields its entity.
        entities = detector.detect_pii(
            text, pii_type_configs=configs, chunk_size=2, overlap=0
        )
        assert {e.pii_type for e in entities} == {"ID"}


class TestTolerantJsonParsing:
    def test_Should_ParseArray_When_WrappedInMarkdownFenceAndProse(self):
        fenced = (
            "Here are the entities I found:\n"
            "```json\n"
            '[{"text": "john@acme.com", "label": "EMAIL"}]\n'
            "```\n"
            "Hope this helps."
        )
        detector = _build_detector([_chat_response([], as_text=fenced)])
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        entities = detector.detect_pii("john@acme.com", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "EMAIL"

    def test_Should_ReturnEmpty_When_NoJsonArrayPresent(self):
        detector = _build_detector(
            [_chat_response([], as_text="I could not find any PII.")]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.0))
        assert detector.detect_pii("john@acme.com", pii_type_configs=configs) == []


class TestPerTypeThreshold:
    def test_Should_DropEntity_When_ScoreBelowPerTypeThreshold(self):
        # The LLM emits no confidence; entities carry the request threshold as
        # their score. A per-type threshold strictly above it filters them out.
        detector = _build_detector(
            [_chat_response([{"text": "john@acme.com", "label": "EMAIL"}])]
        )
        configs = _configs(("EMAIL", "EMAIL", 0.9))
        # threshold=0.5 -> score 0.5 < per-type 0.9 -> dropped
        assert detector.detect_pii(
            "john@acme.com", threshold=0.5, pii_type_configs=configs
        ) == []
