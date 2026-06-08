"""Unit tests for :class:`FormatPrefilterValidator`.

Covers the ``filter_with_verdicts`` contract pinned in ``PLAN.md`` T3:

- an unmapped ``pii_type`` passes through unchanged,
- a rejected entity is returned with a duck-typed verdict exposing
  ``.verdict`` / ``.confidence`` / ``.reason`` (shared discard channel),
- a strategy raising keeps the entity (defensive fail-open).
"""

from __future__ import annotations

from unittest.mock import patch

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.prefilter.format_prefilter_validator import (
    FormatPrefilterValidator,
    _reset_singleton_for_tests,
    get_instance,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _entity(text: str, pii_type: str) -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type=pii_type,
        type_label=pii_type,
        start=0,
        end=len(text),
        score=0.9,
        source=DetectorSource.GLINER,
    )


# ---------------------------------------------------------------------------
# filter_with_verdicts
# ---------------------------------------------------------------------------


class TestFilterWithVerdicts:
    def test_should_keep_unmapped_type_unchanged(self) -> None:
        validator = FormatPrefilterValidator()
        entity = _entity("alice@example.com", "EMAIL")
        kept, rejections = validator.filter_with_verdicts("text", [entity])
        assert kept == [entity]
        assert rejections == []

    def test_should_keep_real_ip_and_reject_fake_ip(self) -> None:
        validator = FormatPrefilterValidator()
        real_ip = _entity("10.217.4.11", "IP_ADDRESS")
        fake_ip = _entity("0.244.999.7", "IP_ADDRESS")
        kept, rejections = validator.filter_with_verdicts(
            "text", [real_ip, fake_ip]
        )
        assert kept == [real_ip]
        assert len(rejections) == 1
        rejected_entity, verdict = rejections[0]
        assert rejected_entity is fake_ip

    def test_should_expose_duck_typed_verdict_fields(self) -> None:
        validator = FormatPrefilterValidator()
        fake_ip = _entity("0.244.999.7", "IP_ADDRESS")
        _kept, rejections = validator.filter_with_verdicts("text", [fake_ip])
        _entity_out, verdict = rejections[0]
        # Same shape as PiiVerdict so the shared discard channel reads it via
        # getattr(verdict, 'verdict'|'confidence'|'reason').
        assert verdict.verdict == "FALSE_POSITIVE"
        assert verdict.confidence == 1.0
        assert "parse failed" in verdict.reason

    def test_should_normalise_freetext_label_to_registry_key(self) -> None:
        # GLiNER2 free-text label "ip address" -> normalised "IP_ADDRESS".
        validator = FormatPrefilterValidator()
        fake_ip = _entity("0.244.999.7", "ip address")
        kept, rejections = validator.filter_with_verdicts("text", [fake_ip])
        assert kept == []
        assert len(rejections) == 1

    def test_should_fail_open_when_strategy_raises(self) -> None:
        validator = FormatPrefilterValidator()
        entity = _entity("0.244.999.7", "IP_ADDRESS")

        class _BoomStrategy:
            pii_type = "IP_ADDRESS"

            def evaluate(self, value):
                raise RuntimeError("strategy blew up")

        with patch.dict(
            "pii_detector.infrastructure.prefilter.format_prefilter_validator.STRATEGIES",
            {"IP_ADDRESS": _BoomStrategy()},
            clear=True,
        ):
            kept, rejections = validator.filter_with_verdicts("text", [entity])
        # Defensive fail-open: entity kept, no rejection emitted.
        assert kept == [entity]
        assert rejections == []

    def test_filter_should_return_only_kept_entities(self) -> None:
        validator = FormatPrefilterValidator()
        real_ip = _entity("10.217.4.11", "IP_ADDRESS")
        fake_ip = _entity("0.244.999.7", "IP_ADDRESS")
        assert validator.filter("text", [real_ip, fake_ip]) == [real_ip]


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


class TestSingleton:
    def test_should_return_same_instance(self) -> None:
        _reset_singleton_for_tests()
        first = get_instance()
        second = get_instance()
        assert first is second
        assert first.name == "format-prefilter"
        _reset_singleton_for_tests()

    def test_should_rebuild_after_reset(self) -> None:
        first = get_instance()
        _reset_singleton_for_tests()
        second = get_instance()
        assert first is not second
        _reset_singleton_for_tests()
