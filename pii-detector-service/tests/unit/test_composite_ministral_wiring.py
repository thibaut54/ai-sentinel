"""Unit tests for wiring ``MinistralDetector`` into ``CompositePIIDetector``.

Guards the ensemble contract for the Ministral-PII detector:
- the composite accepts a ``ministral_detector`` slot + ``enable_ministral`` override;
- ``detect_pii(enable_ministral=True)`` triggers Ministral, ``False`` skips it;
- a Ministral branch failure degrades silently (does not break the request);
- REGRESSION: the operator-configured ``ministral_chunk_size`` / ``ministral_overlap``
  actually reach the detector, and the GLiNER multi-pass ``chunk_size``
  (nb_of_label_by_pass) is NEVER leaked into the Ministral chunking knobs.
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


class _RecordingMinistralDetector:
    """Real-signature stub: records the chunking knobs it actually receives.

    A bare ``MagicMock`` cannot be used here because the composite forwards
    kwargs via ``inspect.signature``; the stub must expose the real
    ``detect_pii(text, threshold, pii_type_configs, chunk_size, overlap)``
    parameter names for that introspection to forward them.
    """

    def __init__(self, entities=None, raises: Exception = None):
        self.model_id = "ministral-stub"
        self.model = object()  # already "loaded" (remote endpoint, no-op)
        self.received: dict = {}
        self._entities = entities or []
        self._raises = raises

    def download_model(self) -> None:  # pragma: no cover - no-op
        pass

    def load_model(self) -> None:  # pragma: no cover - no-op
        pass

    def detect_pii(self, text, threshold=None, pii_type_configs=None,
                   chunk_size=None, overlap=None):
        self.received = {
            "chunk_size": chunk_size,
            "overlap": overlap,
            "pii_type_configs": pii_type_configs,
        }
        if self._raises is not None:
            raise self._raises
        return self._entities


def _ministral_entity() -> PIIEntity:
    return PIIEntity(
        text="jane@example.com",
        pii_type="EMAIL",
        type_label="EMAIL",
        start=0,
        end=16,
        score=0.5,
        source=DetectorSource.MINISTRAL,
    )


class TestCompositeMinistralSlot:
    def test_Should_AcceptMinistralDetector_When_PassedAtConstruction(self):
        ministral = _RecordingMinistralDetector()
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            ministral_detector=ministral,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )
        assert composite.ministral_detector is ministral
        assert composite.enable_ministral is True

    def test_Should_DisableMinistral_When_DetectorIsNoneEvenIfEnableTrue(self):
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            ministral_detector=None,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )
        assert composite.enable_ministral is False

    def test_Should_CallMinistralDetector_When_RuntimeFlagTrue(self):
        ministral = _RecordingMinistralDetector(entities=[_ministral_entity()])
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            ministral_detector=ministral,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=False,  # default disabled (opt-in)
        )

        result = composite.detect_pii(
            "jane@example.com",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )

        assert any(e.source is DetectorSource.MINISTRAL for e in result)

    def test_Should_SkipMinistral_When_RuntimeFlagFalse(self):
        ministral = _RecordingMinistralDetector(entities=[_ministral_entity()])
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            ministral_detector=ministral,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,  # default enabled
        )

        composite.detect_pii(
            "jane@example.com",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=False,
        )

        assert ministral.received == {}  # detect_pii never reached

    def test_Should_DegradeSilently_When_MinistralRaises(self):
        ministral = _RecordingMinistralDetector(raises=RuntimeError("endpoint down"))
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            ministral_detector=ministral,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )

        result = composite.detect_pii(
            "jane@example.com",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )
        assert result == []


class TestMinistralChunkKnobWiring:
    """REGRESSION: dedicated ministral chunk knobs must reach the detector and
    the GLiNER multi-pass chunk_size must never leak into them."""

    def _run(self, *, chunk_size, ministral_chunk_size, ministral_overlap):
        ministral = _RecordingMinistralDetector()
        composite = CompositePIIDetector(
            ml_detector=_stub_ml_detector(),
            ministral_detector=ministral,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )
        composite.detect_pii(
            "some text with PII",
            enable_ml=False,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
            chunk_size=chunk_size,
            ministral_chunk_size=ministral_chunk_size,
            ministral_overlap=ministral_overlap,
        )
        return ministral.received

    def test_Should_ForwardDedicatedKnobs_When_Provided(self):
        received = self._run(chunk_size=35, ministral_chunk_size=1024, ministral_overlap=128)
        assert received["chunk_size"] == 1024
        assert received["overlap"] == 128

    def test_Should_NotLeakGlinerChunkSize_When_DedicatedKnobsAbsent(self):
        # The shared GLiNER nb_of_label_by_pass (=35) must NOT become the
        # Ministral token chunk size; absent dedicated values, the detector
        # receives None and falls back to its own token defaults.
        received = self._run(chunk_size=35, ministral_chunk_size=None, ministral_overlap=None)
        assert received["chunk_size"] is None
        assert received["overlap"] is None
