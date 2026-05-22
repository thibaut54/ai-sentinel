"""
Unit tests for ``OpenMedDetector``.

The detector delegates inference to ``openmed.extract_pii``, which loads the
HuggingFace model (~2.8 GB) lazily. All tests here inject a mocked
``_extract_pii`` so no model is loaded.

Coverage focuses on:
- runtime config resolution (label mapping + per-type thresholds)
- conversion of ``openmed`` ``EntityPrediction`` -> domain ``PIIEntity``
- per-type threshold filtering
- masking
- transformers version guard
"""

from __future__ import annotations

from typing import Dict, List
from unittest.mock import MagicMock

import pytest

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity
from pii_detector.infrastructure.detector.openmed_detector import OpenMedDetector


def _fake_entity(label: str, confidence: float, start: int, end: int, text: str = ""):
    """Build a stand-in for ``openmed.EntityPrediction``."""
    ent = MagicMock()
    ent.label = label
    ent.confidence = confidence
    ent.start = start
    ent.end = end
    ent.text = text
    return ent


def _build_detector(entities) -> OpenMedDetector:
    """Wire a detector with a mocked ``extract_pii`` returning ``entities``."""
    detector = OpenMedDetector()
    fake_result = MagicMock()
    fake_result.entities = entities

    fake_extract = MagicMock(return_value=fake_result)
    detector._extract_pii = fake_extract
    detector._loaded = True
    return detector


class TestOpenMedDetectorBasics:
    def test_Should_HaveOpenMedAsModelId(self):
        detector = OpenMedDetector()
        assert detector.model_id == "OpenMed/privacy-filter-multilingual"

    def test_Should_ReturnEmptyList_When_TextIsEmpty(self):
        detector = _build_detector([])
        assert detector.detect_pii("") == []

    def test_Should_ReturnEntitiesWithOpenMedSource_When_LibraryDetects(self):
        detector = _build_detector([
            _fake_entity("IBAN", 0.95, 0, 22, "CH9300762011623852957"),
        ])
        configs = {
            "OPENMED:IBAN": {
                "enabled": True, "threshold": 0.85,
                "detector": "OPENMED", "detector_label": "IBAN",
            },
        }
        entities = detector.detect_pii("CH9300762011623852957", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "IBAN"
        assert entities[0].source is DetectorSource.OPENMED
        assert pytest.approx(entities[0].score, abs=1e-3) == 0.95


class TestRuntimeConfigResolution:
    def test_Should_FilterOutDisabledTypes_When_ConfigSaysEnabledFalse(self):
        detector = _build_detector([
            _fake_entity("IBAN", 0.99, 0, 22),
        ])
        configs = {
            "OPENMED:IBAN": {
                "enabled": False, "threshold": 0.85,
                "detector": "OPENMED", "detector_label": "IBAN",
            },
        }
        assert detector.detect_pii("XXXXXXXXXXXXXXXXXXXXXX", pii_type_configs=configs) == []

    def test_Should_SkipUnmappedLabels_When_LabelNotInConfig(self):
        detector = _build_detector([
            _fake_entity("EYECOLOR", 0.95, 0, 4, "blue"),
            _fake_entity("IBAN", 0.95, 10, 30, "CH..."),
        ])
        configs = {
            "OPENMED:IBAN": {
                "enabled": True, "threshold": 0.85,
                "detector": "OPENMED", "detector_label": "IBAN",
            },
        }
        entities = detector.detect_pii(
            "blue      CH9300762011623852957", pii_type_configs=configs
        )
        assert {e.pii_type for e in entities} == {"IBAN"}

    def test_Should_ApplyPerTypeThreshold_When_ScoreBelowOverride(self):
        detector = _build_detector([
            _fake_entity("PASSWORD", 0.70, 0, 8, "Hunter2!"),
        ])
        configs = {
            "OPENMED:PASSWORD": {
                "enabled": True, "threshold": 0.85,
                "detector": "OPENMED", "detector_label": "PASSWORD",
            },
        }
        assert detector.detect_pii("Hunter2!", pii_type_configs=configs) == []

    def test_Should_KeepEntity_When_ScoreEqualsOrExceedsOverride(self):
        detector = _build_detector([
            _fake_entity("PASSWORD", 0.90, 0, 8, "Hunter2!"),
        ])
        configs = {
            "OPENMED:PASSWORD": {
                "enabled": True, "threshold": 0.85,
                "detector": "OPENMED", "detector_label": "PASSWORD",
            },
        }
        entities = detector.detect_pii("Hunter2!", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "PASSWORD"

    def test_Should_MapDetectorLabelToCanonicalPiiType_When_LabelMatchesConfig(self):
        """OpenMed raw label 'CREDITCARD' should map to canonical 'CREDIT_CARD'."""
        detector = _build_detector([
            _fake_entity("CREDITCARD", 0.95, 0, 19, "4111 1111 1111 1111"),
        ])
        configs = {
            "OPENMED:CREDIT_CARD": {
                "enabled": True, "threshold": 0.85,
                "detector": "OPENMED", "detector_label": "CREDITCARD",
            },
        }
        entities = detector.detect_pii("4111 1111 1111 1111", pii_type_configs=configs)
        assert len(entities) == 1
        assert entities[0].pii_type == "CREDIT_CARD"


class TestMasking:
    def test_Should_ReplacePiiWithTypeLabels_When_MaskingIsCalled(self):
        detector = _build_detector([
            _fake_entity("IBAN", 0.95, 9, 31, "CH9300762011623852957XX"),
        ])
        configs = {
            "OPENMED:IBAN": {
                "enabled": True, "threshold": 0.5,
                "detector": "OPENMED", "detector_label": "IBAN",
            },
        }
        text = "MyIBAN = CH9300762011623852957XX is here"
        entities = detector.detect_pii(text, pii_type_configs=configs)
        masked_text = detector._apply_masks(text, entities)
        assert "[IBAN]" in masked_text


class TestErrorHandling:
    def test_Should_RaisePIIDetectionError_When_TransformersVersionTooLow(self, monkeypatch):
        detector = OpenMedDetector()

        class FakeOpenMed:
            extract_pii = MagicMock()

        class FakeTransformers:
            __version__ = "4.57.0"

        monkeypatch.setitem(__import__("sys").modules, "openmed", FakeOpenMed)
        monkeypatch.setitem(__import__("sys").modules, "transformers", FakeTransformers)

        from pii_detector.domain.exception.exceptions import PIIDetectionError
        with pytest.raises(PIIDetectionError, match="transformers >= 5.0"):
            detector._ensure_loaded()

    def test_Should_RaisePIIDetectionError_When_OpenMedLibraryMissing(self, monkeypatch):
        detector = OpenMedDetector()

        import builtins
        real_import = builtins.__import__

        def deny_openmed(name, *args, **kwargs):
            if name == "openmed":
                raise ImportError("openmed not installed")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", deny_openmed)
        # Also clear any cached openmed module
        monkeypatch.delitem(__import__("sys").modules, "openmed", raising=False)

        from pii_detector.domain.exception.exceptions import PIIDetectionError
        with pytest.raises(PIIDetectionError, match="openmed"):
            detector._ensure_loaded()
