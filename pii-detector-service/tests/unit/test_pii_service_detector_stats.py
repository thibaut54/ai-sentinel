"""Unit tests for the ``detector_stats`` mapping in
:mod:`pii_detector.infrastructure.adapter.in.grpc.pii_service`.

Covers :meth:`PIIDetectionServicer._add_detector_stats_to_response`, which maps
the per-detector run-stats dicts produced by the composite detector onto the
proto ``detector_stats`` repeated field.

The test invokes the staticmethod directly against a real proto response object,
avoiding any servicer/model boot.
"""

from __future__ import annotations

import importlib

from pii_detector.domain.entity.detector_source import DetectorSource
from pii_detector.proto.generated import pii_detection_pb2


pii_service = importlib.import_module(
    "pii_detector.infrastructure.adapter.in.grpc.pii_service"
)
PIIDetectionServicer = pii_service.PIIDetectionServicer


class TestAddDetectorStatsToResponse:
    def test_Should_PopulateOneProtoStatPerDetector_When_StatsProvided(self):
        response = pii_detection_pb2.PIIDetectionResponse()
        stats = [
            {"source": DetectorSource.GLINER, "duration_ms": 12, "entities_found": 3},
            {"source": DetectorSource.REGEX, "duration_ms": 1, "entities_found": 0},
            {"source": DetectorSource.GLINER2, "duration_ms": 4200, "entities_found": 5},
        ]

        PIIDetectionServicer._add_detector_stats_to_response(
            response, stats, "req_1"
        )

        assert len(response.detector_stats) == 3
        by_source = {s.source: s for s in response.detector_stats}
        assert by_source[pii_detection_pb2.DetectorSource.GLINER].duration_ms == 12
        assert by_source[pii_detection_pb2.DetectorSource.GLINER].entities_found == 3
        assert by_source[pii_detection_pb2.DetectorSource.REGEX].entities_found == 0
        assert by_source[pii_detection_pb2.DetectorSource.GLINER2].duration_ms == 4200

    def test_Should_MapEnumNameToProtoEnum_When_DetectorSourceGiven(self):
        response = pii_detection_pb2.PIIDetectionResponse()
        stats = [
            {"source": DetectorSource.PRESIDIO, "duration_ms": 7, "entities_found": 1},
            {"source": DetectorSource.OPENMED, "duration_ms": 9, "entities_found": 2},
        ]

        PIIDetectionServicer._add_detector_stats_to_response(
            response, stats, "req_2"
        )

        sources = {s.source for s in response.detector_stats}
        assert sources == {
            pii_detection_pb2.DetectorSource.PRESIDIO,
            pii_detection_pb2.DetectorSource.OPENMED,
        }

    def test_Should_AcceptStringSource_When_NotAnEnum(self):
        response = pii_detection_pb2.PIIDetectionResponse()
        stats = [{"source": "GLINER2", "duration_ms": 5, "entities_found": 0}]

        PIIDetectionServicer._add_detector_stats_to_response(
            response, stats, "req_3"
        )

        assert len(response.detector_stats) == 1
        assert (
            response.detector_stats[0].source
            == pii_detection_pb2.DetectorSource.GLINER2
        )

    def test_Should_FallbackToUnknownSource_When_SourceUnrecognized(self):
        response = pii_detection_pb2.PIIDetectionResponse()
        stats = [{"source": "NOT_A_DETECTOR", "duration_ms": 0, "entities_found": 0}]

        PIIDetectionServicer._add_detector_stats_to_response(
            response, stats, "req_4"
        )

        assert (
            response.detector_stats[0].source
            == pii_detection_pb2.DetectorSource.UNKNOWN_SOURCE
        )

    def test_Should_LeaveStatsEmpty_When_StatsListEmpty(self):
        """Empty stats add nothing -> detector_stats stays empty (backward-compat)."""
        response = pii_detection_pb2.PIIDetectionResponse()

        PIIDetectionServicer._add_detector_stats_to_response(response, [], "req_5")

        assert len(response.detector_stats) == 0

    def test_Should_MapJudgeAndPostfilterPseudoDetectors_When_PostFilterStatsGiven(self):
        """The judge and pre-filter are surfaced as pseudo-detectors carrying the
        examined count (entities_found) and the discarded count (entities_discarded)."""
        response = pii_detection_pb2.PIIDetectionResponse()
        stats = [
            {"source": DetectorSource.GLINER2, "duration_ms": 4200, "entities_found": 5},
            {"source": DetectorSource.POSTFILTER, "duration_ms": 2, "entities_found": 8, "entities_discarded": 3},
            {"source": DetectorSource.JUDGE, "duration_ms": 1400, "entities_found": 5, "entities_discarded": 1},
        ]

        PIIDetectionServicer._add_detector_stats_to_response(
            response, stats, "req_judge"
        )

        by_source = {s.source: s for s in response.detector_stats}
        judge = by_source[pii_detection_pb2.DetectorSource.JUDGE]
        assert judge.duration_ms == 1400
        assert judge.entities_found == 5
        assert judge.entities_discarded == 1
        postfilter = by_source[pii_detection_pb2.DetectorSource.POSTFILTER]
        assert postfilter.entities_discarded == 3
        # Real detectors carry no discards.
        assert by_source[pii_detection_pb2.DetectorSource.GLINER2].entities_discarded == 0
