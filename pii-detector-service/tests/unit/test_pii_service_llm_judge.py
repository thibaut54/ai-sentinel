"""
Unit tests for the LLM judge wiring in
:mod:`pii_detector.infrastructure.adapter.in.grpc.pii_service`.

Two narrow scopes are covered:

- :meth:`PIIDetectionServicer._is_llm_judge_enabled` -- pure boolean gate
  driven by the ``detector_flags`` propagated from the database.
- :meth:`PIIDetectionServicer._apply_llm_judge` -- lazy validator wiring,
  fail-open semantics, and zero-overhead guarantee when the feature is
  disabled.

The tests intentionally avoid spinning up the full servicer (which loads
detectors, models, GLiNER, ...). They invoke the staticmethod
:meth:`_is_llm_judge_enabled` directly and call :meth:`_apply_llm_judge`
through a lightweight ``MagicMock`` standing in for the servicer
instance.
"""

from __future__ import annotations

import importlib
import sys
from typing import List
from unittest.mock import MagicMock, patch

import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.validation.prompt_templates import PiiVerdict


pii_service = importlib.import_module(
    "pii_detector.infrastructure.adapter.in.grpc.pii_service"
)
PIIDetectionServicer = pii_service.PIIDetectionServicer


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _gliner_entity(text: str = "John Doe") -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type="PERSON",
        type_label="PERSON",
        start=0,
        end=len(text),
        score=0.9,
        source=DetectorSource.GLINER,
    )


def _regex_entity(text: str = "alice@example.com") -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type="EMAIL",
        type_label="EMAIL",
        start=0,
        end=len(text),
        score=1.0,
        source=DetectorSource.REGEX,
    )


def _fp_verdict(reason: str = "not a real PII") -> PiiVerdict:
    return PiiVerdict(
        verdict="FALSE_POSITIVE", confidence=0.95, reason=reason
    )


# ---------------------------------------------------------------------------
# _is_llm_judge_enabled
# ---------------------------------------------------------------------------


class TestIsLlmJudgeEnabled:
    @pytest.mark.parametrize(
        "flags,expected",
        [
            (None, False),
            ({}, False),
            ({"llm_judge_enabled": False}, False),
            ({"llm_judge_enabled": True}, True),
            ({"gliner_enabled": True}, False),  # unrelated flags ignored
        ],
    )
    def test_should_default_to_false_when_flag_missing(
        self, flags, expected
    ) -> None:
        assert PIIDetectionServicer._is_llm_judge_enabled(flags) is expected


# ---------------------------------------------------------------------------
# _apply_llm_judge
# ---------------------------------------------------------------------------


class _FakeServicer:
    """Minimal stand-in exposing only the method under test.

    We bind the real ``_apply_llm_judge`` to this instance so we can
    exercise its branches without booting the servicer.
    """

    _apply_llm_judge = PIIDetectionServicer._apply_llm_judge


class TestApplyLlmJudge:
    def test_should_call_validator_and_return_filtered_entities(self) -> None:
        servicer = _FakeServicer()
        gliner = _gliner_entity()
        verdict = _fp_verdict()
        validator = MagicMock()
        validator.filter_with_verdicts.return_value = ([], [(gliner, verdict)])
        with patch(
            "pii_detector.infrastructure.validation.llm_validator.get_instance",
            return_value=validator,
        ):
            result = servicer._apply_llm_judge(
                [gliner], content="hello", request_id="req-1"
            )
        validator.filter_with_verdicts.assert_called_once_with("hello", [gliner])
        assert result == ([], [(gliner, verdict)])

    def test_should_passthrough_when_entities_empty(self) -> None:
        servicer = _FakeServicer()
        with patch(
            "pii_detector.infrastructure.validation.llm_validator.get_instance"
        ) as factory:
            assert servicer._apply_llm_judge([], "text", "req-2") == ([], [])
            factory.assert_not_called()

    def test_should_fail_open_when_validator_blows_up(self) -> None:
        servicer = _FakeServicer()
        entities: List[PIIEntity] = [_gliner_entity(), _regex_entity()]
        validator = MagicMock()
        validator.filter_with_verdicts.side_effect = RuntimeError(
            "validator misconfigured"
        )
        with patch(
            "pii_detector.infrastructure.validation.llm_validator.get_instance",
            return_value=validator,
        ):
            result = servicer._apply_llm_judge(
                entities, "text", "req-3"
            )
        # Defense-in-depth: original entities returned untouched, no rejection.
        assert result == (entities, [])

    def test_should_emit_structured_log_with_counts(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        servicer = _FakeServicer()
        gliner_kept = _gliner_entity(text="Alice")
        gliner_rejected = _gliner_entity(text="DGAIC")
        validator = MagicMock()
        validator.filter_with_verdicts.return_value = (
            [gliner_kept],
            [(gliner_rejected, _fp_verdict())],
        )
        with patch(
            "pii_detector.infrastructure.validation.llm_validator.get_instance",
            return_value=validator,
        ):
            with caplog.at_level("INFO", logger=pii_service.logger.name):
                servicer._apply_llm_judge(
                    [gliner_kept, gliner_rejected],
                    "text",
                    "req-4",
                )
        message = " ".join(r.message for r in caplog.records)
        assert "[LLM-JUDGE] post-filter" in message
        assert "rejected=1" in message
        assert "2->1 entities" in message


# ---------------------------------------------------------------------------
# _add_discarded_entities_to_response
# ---------------------------------------------------------------------------


class _FakeResponseServicer:
    """Stand-in binding the response-building methods under test."""

    _add_discarded_entities_to_response = (
        PIIDetectionServicer._add_discarded_entities_to_response
    )
    _populate_proto_entity = staticmethod(
        PIIDetectionServicer._populate_proto_entity
    )


class TestAddDiscardedEntitiesToResponse:
    def test_should_expose_rejected_entity_with_verdict_fields(self) -> None:
        from pii_detector.proto.generated import pii_detection_pb2

        servicer = _FakeResponseServicer()
        response = pii_detection_pb2.PIIDetectionResponse()
        gliner = _gliner_entity(text="DGAIC")
        verdict = _fp_verdict(reason="acronym, not a person")

        servicer._add_discarded_entities_to_response(
            response, [(gliner, verdict)], "req-5"
        )

        assert len(response.discarded_entities) == 1
        discarded = response.discarded_entities[0]
        assert discarded.entity.text == "DGAIC"
        assert discarded.entity.type == "PERSON"
        assert discarded.entity.source == pii_detection_pb2.DetectorSource.GLINER
        assert discarded.judge_verdict == "FALSE_POSITIVE"
        assert discarded.judge_confidence == pytest.approx(0.95)
        assert discarded.judge_reason == "acronym, not a person"

    def test_should_leave_field_empty_when_no_rejection(self) -> None:
        from pii_detector.proto.generated import pii_detection_pb2

        servicer = _FakeResponseServicer()
        response = pii_detection_pb2.PIIDetectionResponse()
        servicer._add_discarded_entities_to_response(response, [], "req-6")
        assert len(response.discarded_entities) == 0


# ---------------------------------------------------------------------------
# Zero-overhead guarantee when flag is disabled
# ---------------------------------------------------------------------------


class TestZeroOverheadWhenDisabled:
    def test_should_never_import_llm_validator_when_flag_off(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """When ``llm_judge_enabled=False`` the pipeline must not touch the
        validator module (spec section 5.3 zero-overhead criterion)."""
        # Drop any cached import so an accidental new import would be
        # picked up by the assertion below.
        for mod in list(sys.modules):
            if mod.startswith(
                "pii_detector.infrastructure.validation.llm_validator"
            ):
                monkeypatch.delitem(sys.modules, mod, raising=False)

        # The gate must return False -> no get_instance() ever called.
        assert PIIDetectionServicer._is_llm_judge_enabled(
            {"llm_judge_enabled": False}
        ) is False
        assert PIIDetectionServicer._is_llm_judge_enabled(None) is False
        assert PIIDetectionServicer._is_llm_judge_enabled({}) is False

        # The module must remain unloaded (the gate alone never imports it).
        assert (
            "pii_detector.infrastructure.validation.llm_validator"
            not in sys.modules
        ), "judge module imported even though flag is OFF"
