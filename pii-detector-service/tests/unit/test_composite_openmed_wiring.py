"""
Unit tests for the wiring of ``OpenMedDetector`` into ``CompositePIIDetector``.

These tests guard the contract between the gRPC layer and the composite:
- The composite accepts an ``openmed_detector`` slot and an ``enable_openmed``
  runtime override.
- ``detect_pii(enable_openmed=True)`` triggers OpenMed, ``enable_openmed=False``
  skips it, regardless of the default ``enable_openmed`` set at construction.
- A failure of the OpenMed branch does not propagate (defensive degradation).
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from pii_detector.application.orchestration.composite_detector import CompositePIIDetector
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity


def _stub_ml_detector() -> MagicMock:
    detector = MagicMock()
    detector.model_id = "stub-ml"
    detector.detect_pii.return_value = []
    return detector


def _stub_openmed_detector(entities=None, raises: Exception = None) -> MagicMock:
    detector = MagicMock()
    detector.model_id = "OpenMed/privacy-filter-multilingual"
    if raises is not None:
        detector.detect_pii.side_effect = raises
    else:
        detector.detect_pii.return_value = entities or []
    return detector


class TestCompositeOpenMedSlot:
    def test_Should_AcceptOpenMedDetector_When_PassedAtConstruction(self):
        openmed = _stub_openmed_detector()
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            openmed_detector=openmed,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=True,
        )
        assert composite.openmed_detector is openmed
        assert composite.enable_openmed is True

    def test_Should_DisableOpenMed_When_DetectorIsNoneEvenIfEnableTrue(self):
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            openmed_detector=None,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=True,
        )
        assert composite.enable_openmed is False

    def test_Should_CallOpenMedDetector_When_RuntimeFlagTrue(self):
        entity = PIIEntity(
            text="CH9300762011623852957",
            pii_type="IBAN",
            type_label="IBAN",
            start=0,
            end=22,
            score=0.95,
            source=DetectorSource.OPENMED,
        )
        openmed = _stub_openmed_detector(entities=[entity])
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            openmed_detector=openmed,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=False,  # default disabled
        )

        result = composite.detect_pii(
            "CH9300762011623852957",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=True,
        )

        openmed.detect_pii.assert_called_once()
        assert any(e.source is DetectorSource.OPENMED for e in result)

    def test_Should_SkipOpenMed_When_RuntimeFlagFalse(self):
        openmed = _stub_openmed_detector(entities=[])
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            openmed_detector=openmed,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=True,  # default enabled
        )

        composite.detect_pii(
            "anything",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=False,
        )

        openmed.detect_pii.assert_not_called()

    def test_Should_DegradeSilently_When_OpenMedRaises(self):
        openmed = _stub_openmed_detector(raises=RuntimeError("model not loaded"))
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            openmed_detector=openmed,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=True,
        )

        result = composite.detect_pii(
            "fallback",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_openmed=True,
        )
        # OpenMed failure must not break the whole request
        assert result == []
