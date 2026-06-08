"""Unit tests for the format pre-filter wiring in
:mod:`pii_detector.infrastructure.adapter.in.grpc.pii_service`.

Two narrow scopes are covered:

- :meth:`PIIDetectionServicer._is_prefilter_enabled` -- pure boolean gate
  driven by the ``detector_flags`` propagated from the database.
- :meth:`PIIDetectionServicer._apply_format_prefilter` -- lazy validator
  wiring, fail-open semantics, and zero-overhead guarantee when the feature
  is disabled.

The tests invoke the staticmethod :meth:`_is_prefilter_enabled` directly and
call :meth:`_apply_format_prefilter` through a lightweight ``_FakeServicer``
standing in for the servicer instance, so no detector / model is booted.
"""

from __future__ import annotations

import importlib
import sys
from typing import List

import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity


pii_service = importlib.import_module(
    "pii_detector.infrastructure.adapter.in.grpc.pii_service"
)
PIIDetectionServicer = pii_service.PIIDetectionServicer


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _ip_entity(text: str) -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type="IP_ADDRESS",
        type_label="IP_ADDRESS",
        start=0,
        end=len(text),
        score=0.9,
        source=DetectorSource.GLINER2,
    )


# ---------------------------------------------------------------------------
# _is_prefilter_enabled
# ---------------------------------------------------------------------------


class TestIsPrefilterEnabled:
    @pytest.mark.parametrize(
        "flags,expected",
        [
            (None, False),
            ({}, False),
            ({"prefilter_enabled": False}, False),
            ({"prefilter_enabled": True}, True),
            ({"gliner_enabled": True}, False),  # unrelated flags ignored
        ],
    )
    def test_should_default_to_false_when_flag_missing(
        self, flags, expected
    ) -> None:
        assert PIIDetectionServicer._is_prefilter_enabled(flags) is expected


# ---------------------------------------------------------------------------
# _apply_format_prefilter
# ---------------------------------------------------------------------------


class _FakeServicer:
    """Minimal stand-in exposing only the method under test.

    We bind the real ``_apply_format_prefilter`` to this instance so we can
    exercise its branches without booting the servicer.
    """

    _apply_format_prefilter = PIIDetectionServicer._apply_format_prefilter


class TestApplyFormatPrefilter:
    def test_should_discard_fake_ip_and_keep_real_ip(self) -> None:
        servicer = _FakeServicer()
        real_ip = _ip_entity("10.217.4.11")
        fake_ip = _ip_entity("0.244.999.7")
        kept, rejections = servicer._apply_format_prefilter(
            [real_ip, fake_ip], content="trace", request_id="req-1"
        )
        assert kept == [real_ip]
        assert len(rejections) == 1
        rejected_entity, verdict = rejections[0]
        assert rejected_entity is fake_ip
        assert verdict.verdict == "FALSE_POSITIVE"

    def test_should_passthrough_when_entities_empty(self) -> None:
        servicer = _FakeServicer()
        assert servicer._apply_format_prefilter([], "text", "req-2") == ([], [])

    def test_should_fail_open_when_validator_blows_up(self) -> None:
        servicer = _FakeServicer()
        entities: List[PIIEntity] = [_ip_entity("0.244.999.7")]
        from unittest.mock import MagicMock, patch

        validator = MagicMock()
        validator.filter_with_verdicts.side_effect = RuntimeError(
            "validator misconfigured"
        )
        with patch(
            "pii_detector.infrastructure.prefilter.format_prefilter_validator.get_instance",
            return_value=validator,
        ):
            result = servicer._apply_format_prefilter(entities, "text", "req-3")
        # Defense-in-depth: original entities returned untouched, no rejection.
        assert result == (entities, [])

    def test_should_emit_structured_log_with_counts(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        servicer = _FakeServicer()
        real_ip = _ip_entity("10.217.4.11")
        fake_ip = _ip_entity("0.244.999.7")
        with caplog.at_level("INFO", logger=pii_service.logger.name):
            servicer._apply_format_prefilter(
                [real_ip, fake_ip], "text", "req-4"
            )
        message = " ".join(r.message for r in caplog.records)
        assert "[PREFILTER] post-filter" in message
        assert "rejected=1" in message
        assert "2->1 entities" in message


# ---------------------------------------------------------------------------
# Zero-overhead guarantee when flag is disabled
# ---------------------------------------------------------------------------


class TestZeroOverheadWhenDisabled:
    def test_should_never_import_validator_when_flag_off(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """When ``prefilter_enabled=False`` the pipeline must not touch the
        validator module (zero-overhead criterion)."""
        for mod in list(sys.modules):
            if mod.startswith(
                "pii_detector.infrastructure.prefilter.format_prefilter_validator"
            ):
                monkeypatch.delitem(sys.modules, mod, raising=False)

        # The gate must return False -> no get_instance() ever called.
        assert PIIDetectionServicer._is_prefilter_enabled(
            {"prefilter_enabled": False}
        ) is False
        assert PIIDetectionServicer._is_prefilter_enabled(None) is False
        assert PIIDetectionServicer._is_prefilter_enabled({}) is False

        # The module must remain unloaded (the gate alone never imports it).
        assert (
            "pii_detector.infrastructure.prefilter.format_prefilter_validator"
            not in sys.modules
        ), "prefilter module imported even though flag is OFF"
