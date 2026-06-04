"""Unit tests for wiring ``Gliner2Detector`` into ``CompositePIIDetector``.

Guards the ensemble contract (spec D2/D4, RG5/R7):
- the composite accepts a ``gliner2_detector`` slot + ``enable_gliner2`` override;
- ``detect_pii(enable_gliner2=True)`` triggers GLiNER2, ``False`` skips it,
  regardless of the construction default;
- a GLiNER2 branch failure degrades silently (does not break the request);
- NON-REGRESSION: with GLiNER2 off the composite behaves exactly as before
  (GLiNER2 never invoked, GLiNER results untouched).
"""
from __future__ import annotations

from unittest.mock import MagicMock

from pii_detector.application.orchestration.composite_detector import CompositePIIDetector
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity


def _stub_ml_detector(entities=None) -> MagicMock:
    detector = MagicMock()
    detector.model_id = "stub-ml"
    detector.detect_pii.return_value = entities or []
    return detector


def _stub_gliner2_detector(entities=None, raises: Exception = None) -> MagicMock:
    detector = MagicMock()
    detector.model_id = "fastino/gliner2-large-v1"
    if raises is not None:
        detector.detect_pii.side_effect = raises
    else:
        detector.detect_pii.return_value = entities or []
    return detector


def _gliner2_entity() -> PIIEntity:
    return PIIEntity(
        text="CH9300762011623852957",
        pii_type="IBAN",
        type_label="IBAN",
        start=0,
        end=22,
        score=0.95,
        source=DetectorSource.GLINER2,
    )


class TestCompositeGliner2Slot:
    def test_Should_AcceptGliner2Detector_When_PassedAtConstruction(self):
        gliner2 = _stub_gliner2_detector()
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )
        assert composite.gliner2_detector is gliner2
        assert composite.enable_gliner2 is True

    def test_Should_DisableGliner2_When_DetectorIsNoneEvenIfEnableTrue(self):
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=None,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )
        assert composite.enable_gliner2 is False

    def test_Should_CallGliner2Detector_When_RuntimeFlagTrue(self):
        gliner2 = _stub_gliner2_detector(entities=[_gliner2_entity()])
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=False,  # default disabled (D4)
        )

        result = composite.detect_pii(
            "CH9300762011623852957",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )

        gliner2.detect_pii.assert_called_once()
        assert any(e.source is DetectorSource.GLINER2 for e in result)

    def test_Should_LazyLoadGliner2Model_When_EnabledAtRuntimeAfterStartup(self):
        # Hot-toggle scenario (spec RG6/O3): the model was NOT loaded at startup
        # (gliner2_enabled was FALSE), then an operator enables GLiNER2 live. The
        # composite must load the model on first use instead of raising
        # ModelNotLoadedError.
        gliner2 = _stub_gliner2_detector(entities=[_gliner2_entity()])
        gliner2.model = None  # never loaded at startup
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=False,  # disabled at construction (D4)
        )

        result = composite.detect_pii(
            "CH9300762011623852957",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,  # toggled on at request time
        )

        gliner2.load_model.assert_called_once()
        gliner2.detect_pii.assert_called_once()
        assert any(e.source is DetectorSource.GLINER2 for e in result)

    def test_Should_NotReloadGliner2Model_When_AlreadyLoaded(self):
        # If the model is already loaded, no redundant load on each request.
        gliner2 = _stub_gliner2_detector(entities=[_gliner2_entity()])
        gliner2.model = object()  # already loaded
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )

        composite.detect_pii(
            "CH9300762011623852957",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )

        gliner2.load_model.assert_not_called()
        gliner2.detect_pii.assert_called_once()

    def test_Should_SkipGliner2_When_RuntimeFlagFalse(self):
        gliner2 = _stub_gliner2_detector(entities=[])
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,  # default enabled
        )

        composite.detect_pii(
            "anything",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=False,
        )

        gliner2.detect_pii.assert_not_called()

    def test_Should_DegradeSilently_When_Gliner2Raises(self):
        gliner2 = _stub_gliner2_detector(raises=RuntimeError("gliner2 lib absent"))
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )

        result = composite.detect_pii(
            "fallback",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=True,
        )
        assert result == []


class TestGliner2NonRegression:
    """gliner2_enabled=FALSE => strictly identical behaviour (spec R7)."""

    def test_Should_NotInvokeGliner2_When_DisabledByDefault(self):
        gliner2 = _stub_gliner2_detector(entities=[_gliner2_entity()])
        gliner_entity = PIIEntity(
            text="Jean Dupont", pii_type="PERSON_NAME", type_label="PERSON_NAME",
            start=0, end=11, score=0.9, source=DetectorSource.GLINER,
        )
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(entities=[gliner_entity]),
            gliner2_detector=gliner2,
            enable_regex=False,
            enable_presidio=False,
            enable_gliner2=False,  # OFF by default
        )

        result = composite.detect_pii("Jean Dupont", enable_regex=False, enable_presidio=False)

        gliner2.detect_pii.assert_not_called()
        # GLiNER results are returned untouched, no GLINER2 spans leak in.
        assert all(e.source is not DetectorSource.GLINER2 for e in result)
        assert any(e.source is DetectorSource.GLINER for e in result)
