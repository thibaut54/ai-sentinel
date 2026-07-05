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
from pii_detector.infrastructure.postfilter.format_postfilter_validator import (
    FormatPostfilterValidator,
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
        source=DetectorSource.MINISTRAL,
    )


# ---------------------------------------------------------------------------
# filter_with_verdicts
# ---------------------------------------------------------------------------


class TestFilterWithVerdicts:
    def test_should_keep_unmapped_type_unchanged(self) -> None:
        validator = FormatPostfilterValidator()
        entity = _entity("alice@example.com", "EMAIL")
        kept, rejections = validator.filter_with_verdicts("text", [entity])
        assert kept == [entity]
        assert rejections == []

    def test_should_keep_real_ip_and_reject_fake_ip(self) -> None:
        validator = FormatPostfilterValidator()
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
        validator = FormatPostfilterValidator()
        fake_ip = _entity("0.244.999.7", "IP_ADDRESS")
        _kept, rejections = validator.filter_with_verdicts("text", [fake_ip])
        _entity_out, verdict = rejections[0]
        # Same shape as PiiVerdict so the shared discard channel reads it via
        # getattr(verdict, 'verdict'|'confidence'|'reason').
        assert verdict.verdict == "FALSE_POSITIVE"
        assert verdict.confidence == 1.0
        assert "parse failed" in verdict.reason

    def test_should_normalise_freetext_label_to_registry_key(self) -> None:
        # free-text label "ip address" -> normalised "IP_ADDRESS".
        validator = FormatPostfilterValidator()
        fake_ip = _entity("0.244.999.7", "ip address")
        kept, rejections = validator.filter_with_verdicts("text", [fake_ip])
        assert kept == []
        assert len(rejections) == 1

    def test_should_fail_open_when_strategy_raises(self) -> None:
        validator = FormatPostfilterValidator()
        entity = _entity("0.244.999.7", "IP_ADDRESS")

        class _BoomStrategy:
            pii_type = "IP_ADDRESS"

            def evaluate(self, value):
                raise RuntimeError("strategy blew up")

        with patch.dict(
            "pii_detector.infrastructure.postfilter.format_postfilter_validator.STRATEGIES",
            {"IP_ADDRESS": _BoomStrategy()},
            clear=True,
        ):
            kept, rejections = validator.filter_with_verdicts("text", [entity])
        # Defensive fail-open: entity kept, no rejection emitted.
        assert kept == [entity]
        assert rejections == []

    def test_filter_should_return_only_kept_entities(self) -> None:
        validator = FormatPostfilterValidator()
        real_ip = _entity("10.217.4.11", "IP_ADDRESS")
        fake_ip = _entity("0.244.999.7", "IP_ADDRESS")
        assert validator.filter("text", [real_ip, fake_ip]) == [real_ip]


# ---------------------------------------------------------------------------
# Cross-label technical-artifact denylist pass
# ---------------------------------------------------------------------------


class TestTechnicalArtifactPass:
    def test_should_reject_artifact_even_when_type_is_unmapped(self) -> None:
        # Ministral passthrough labels (OBJECT_ID, GUID...) are not in
        # STRATEGIES: the label-agnostic pass must still reject the span.
        validator = FormatPostfilterValidator()
        entity = _entity("507f1f77bcf86cd799439011", "OBJECT_ID")
        kept, rejections = validator.filter_with_verdicts("text", [entity])
        assert kept == []
        assert len(rejections) == 1
        _rejected, verdict = rejections[0]
        assert verdict.verdict == "FALSE_POSITIVE"
        assert "technical artifact" in verdict.reason

    def test_should_run_denylist_before_per_type_strategy(self) -> None:
        # A UUID labelled IP_ADDRESS is rejected by the denylist (uuid
        # motif), not by the IP strategy (parse failure) -> pinned by reason.
        validator = FormatPostfilterValidator()
        entity = _entity("3f2504e0-4f89-41d3-9a0c-0305e82c3301", "IP_ADDRESS")
        _kept, rejections = validator.filter_with_verdicts("text", [entity])
        assert len(rejections) == 1
        assert "uuid" in rejections[0][1].reason

    def test_should_fail_open_when_denylist_raises(self) -> None:
        validator = FormatPostfilterValidator()
        entity = _entity("507f1f77bcf86cd799439011", "OBJECT_ID")
        with patch(
            "pii_detector.infrastructure.postfilter.format_postfilter_validator"
            ".technical_artifact_denylist.evaluate",
            side_effect=RuntimeError("denylist blew up"),
        ):
            kept, rejections = validator.filter_with_verdicts("text", [entity])
        assert kept == [entity]
        assert rejections == []


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


class TestSingleton:
    def test_should_return_same_instance(self) -> None:
        _reset_singleton_for_tests()
        first = get_instance()
        second = get_instance()
        assert first is second
        assert first.name == "format-postfilter"
        _reset_singleton_for_tests()

    def test_should_rebuild_after_reset(self) -> None:
        first = get_instance()
        _reset_singleton_for_tests()
        second = get_instance()
        assert first is not second
        _reset_singleton_for_tests()
