"""Unit tests for per-detector run stats in CompositePIIDetector.

Covers :meth:`CompositePIIDetector.detect_pii_with_stats`, which returns one
stats entry per detector that actually ran for a request (even with zero
detections), with the real wall-clock duration and the pre-merge raw count.

The composite is a singleton shared across concurrent gRPC worker threads, so
stats are returned by value (never stored on the instance) to avoid a race
condition; these tests assert the by-value contract.
"""

from unittest.mock import Mock, patch

import pytest

from pii_detector.application.orchestration.composite_detector import (
    CompositePIIDetector,
)
from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.domain.entity.pii_entity import PIIEntity


def _entity(text: str, pii_type: str, start: int) -> PIIEntity:
    return PIIEntity(
        text=text,
        pii_type=pii_type,
        type_label=pii_type,
        start=start,
        end=start + len(text),
        score=0.9,
    )


@pytest.fixture
def mock_ministral_detector():
    detector = Mock()
    detector.model_id = "mock-ministral-detector"
    detector.detect_pii = Mock(return_value=[])
    return detector


@pytest.fixture
def mock_regex_detector():
    detector = Mock()
    detector.model_id = "regex-detector"
    detector.detect_pii = Mock(return_value=[])
    return detector


class TestDetectPiiWithStats:
    @patch(
        "pii_detector.application.orchestration.composite_detector.PresidioDetector"
    )
    def test_Should_ReturnOneStatPerActiveDetector_When_BothRun(
        self, mock_presidio_class, mock_ministral_detector, mock_regex_detector
    ):
        """Two active detectors -> two stats entries with correct sources."""
        mock_presidio = Mock()
        mock_presidio.detect_pii.return_value = []
        mock_presidio_class.return_value = mock_presidio

        mock_ministral_detector.detect_pii.return_value = [
            _entity("John Doe", "PERSON", 0)
        ]
        mock_regex_detector.detect_pii.return_value = [
            _entity("a@b.com", "EMAIL", 20),
            _entity("c@d.com", "EMAIL", 40),
        ]

        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            regex_detector=mock_regex_detector,
            enable_presidio=False,
            enable_ministral=True,
        )

        entities, stats = composite.detect_pii_with_stats("John Doe a@b.com c@d.com")

        sources = {s["source"] for s in stats}
        assert sources == {DetectorSource.MINISTRAL, DetectorSource.REGEX}
        assert len(stats) == 2
        assert len(entities) >= 1

    @patch(
        "pii_detector.application.orchestration.composite_detector.PresidioDetector"
    )
    def test_Should_ReportPreMergeRawCount_When_DetectorsRun(
        self, mock_presidio_class, mock_ministral_detector, mock_regex_detector
    ):
        """entities_found is the raw per-detector count, before merge dedup."""
        mock_presidio = Mock()
        mock_presidio.detect_pii.return_value = []
        mock_presidio_class.return_value = mock_presidio

        mock_ministral_detector.detect_pii.return_value = [
            _entity("John Doe", "PERSON", 0)
        ]
        mock_regex_detector.detect_pii.return_value = [
            _entity("a@b.com", "EMAIL", 20),
            _entity("c@d.com", "EMAIL", 40),
        ]

        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            regex_detector=mock_regex_detector,
            enable_presidio=False,
            enable_ministral=True,
        )

        _entities, stats = composite.detect_pii_with_stats("John Doe a@b.com c@d.com")

        by_source = {s["source"]: s for s in stats}
        assert by_source[DetectorSource.MINISTRAL]["entities_found"] == 1
        assert by_source[DetectorSource.REGEX]["entities_found"] == 2

    @patch(
        "pii_detector.application.orchestration.composite_detector.PresidioDetector"
    )
    def test_Should_RecordZeroFound_When_DetectorFindsNothing(
        self, mock_presidio_class, mock_ministral_detector, mock_regex_detector
    ):
        """A detector that ran but found nothing still gets a stats entry."""
        mock_presidio = Mock()
        mock_presidio.detect_pii.return_value = []
        mock_presidio_class.return_value = mock_presidio

        mock_ministral_detector.detect_pii.return_value = []
        mock_regex_detector.detect_pii.return_value = []

        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            regex_detector=mock_regex_detector,
            enable_presidio=False,
            enable_ministral=True,
        )

        _entities, stats = composite.detect_pii_with_stats("nothing here")

        assert len(stats) == 2
        assert all(s["entities_found"] == 0 for s in stats)

    @patch(
        "pii_detector.application.orchestration.composite_detector.PresidioDetector"
    )
    def test_Should_ReportNonNegativeDuration_When_DetectorRuns(
        self, mock_presidio_class, mock_ministral_detector, mock_regex_detector
    ):
        """duration_ms is a non-negative int for every stats entry."""
        mock_presidio = Mock()
        mock_presidio.detect_pii.return_value = []
        mock_presidio_class.return_value = mock_presidio

        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            regex_detector=mock_regex_detector,
            enable_presidio=False,
            enable_ministral=True,
        )

        _entities, stats = composite.detect_pii_with_stats("some text")

        assert stats
        for s in stats:
            assert isinstance(s["duration_ms"], int)
            assert s["duration_ms"] >= 0

    @patch(
        "pii_detector.application.orchestration.composite_detector.PresidioDetector"
    )
    def test_Should_OnlyStatDetectorsThatRan_When_RegexDisabledPerRequest(
        self, mock_presidio_class, mock_ministral_detector, mock_regex_detector
    ):
        """A per-request override disabling regex excludes it from the stats."""
        mock_presidio = Mock()
        mock_presidio.detect_pii.return_value = []
        mock_presidio_class.return_value = mock_presidio

        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            regex_detector=mock_regex_detector,
            enable_presidio=False,
            enable_ministral=True,
        )

        _entities, stats = composite.detect_pii_with_stats(
            "some text", enable_regex=False
        )

        sources = {s["source"] for s in stats}
        assert sources == {DetectorSource.MINISTRAL}
        mock_regex_detector.detect_pii.assert_not_called()

    def test_Should_ReturnEmptyStats_When_TextIsEmpty(self, mock_ministral_detector):
        """Empty input runs no detector -> no stats."""
        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            enable_regex=False,
            enable_presidio=False,
            enable_ministral=True,
        )

        entities, stats = composite.detect_pii_with_stats("")

        assert entities == []
        assert stats == []

    @patch(
        "pii_detector.application.orchestration.composite_detector.PresidioDetector"
    )
    def test_Should_KeepDetectPiiContractIntact_When_StatsAdded(
        self, mock_presidio_class, mock_ministral_detector, mock_regex_detector
    ):
        """detect_pii still returns a bare entity list (no stats leak)."""
        mock_presidio = Mock()
        mock_presidio.detect_pii.return_value = []
        mock_presidio_class.return_value = mock_presidio

        mock_ministral_detector.detect_pii.return_value = [
            _entity("John Doe", "PERSON", 0)
        ]

        composite = CompositePIIDetector(
            ministral_detector=mock_ministral_detector,
            regex_detector=mock_regex_detector,
            enable_presidio=False,
            enable_ministral=True,
        )

        entities = composite.detect_pii("John Doe")

        assert isinstance(entities, list)
        assert all(isinstance(e, PIIEntity) for e in entities)
