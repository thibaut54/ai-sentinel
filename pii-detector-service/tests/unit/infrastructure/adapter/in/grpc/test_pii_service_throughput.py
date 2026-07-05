"""
Tests for the throughput instrumentation in
:mod:`pii_detector.infrastructure.adapter.in.grpc.pii_service`.

Scope:

- :meth:`PIIDetectionServicer._log_throughput` forwards records to the
  async logger and never raises (the scan must be resilient against an
  observability outage).
- The non-blocking guarantee mirrors the producer-side contract from
  :class:`ThroughputLogger` and is reaffirmed here to make regressions
  visible in CI.
"""

from __future__ import annotations

import importlib
import time
from unittest.mock import MagicMock, patch

import pytest


pii_service = importlib.import_module(
    "pii_detector.infrastructure.adapter.in.grpc.pii_service"
)
PIIDetectionServicer = pii_service.PIIDetectionServicer


class TestLogThroughputHelper:
    def test_should_forward_record_to_async_logger(self) -> None:
        with patch(
            "pii_detector.infrastructure.observability.throughput_logger.get_logger"
        ) as factory:
            stub = MagicMock()
            factory.return_value = stub
            PIIDetectionServicer._log_throughput(
                "detection",
                request_id="req_1",
                chars=1024,
                duration_s=0.5,
                entities_in=3,
            )
            stub.log_phase.assert_called_once_with(
                "detection",
                request_id="req_1",
                chars=1024,
                duration_s=0.5,
                entities_in=3,
            )

    def test_should_swallow_exceptions_silently(self) -> None:
        with patch(
            "pii_detector.infrastructure.observability.throughput_logger.get_logger"
        ) as factory:
            stub = MagicMock()
            stub.log_phase.side_effect = RuntimeError("queue ded")
            factory.return_value = stub
            # Must not raise (observability never breaks the scan).
            PIIDetectionServicer._log_throughput(
                "total",
                request_id="req_2",
                chars=500,
                duration_s=0.1,
                entities_final=2,
            )

    @pytest.mark.parametrize(
        "phase", ["detection", "total"]
    )
    def test_should_accept_each_spec_phase(self, phase: str) -> None:
        with patch(
            "pii_detector.infrastructure.observability.throughput_logger.get_logger"
        ) as factory:
            factory.return_value = MagicMock()
            PIIDetectionServicer._log_throughput(
                phase,
                request_id="req_x",
                chars=10,
                duration_s=0.001,
            )

    def test_should_keep_helper_under_one_millisecond_per_call(self) -> None:
        """Async logger contract: enqueueing must stay sub-ms.

        Reuse the real logger so the timing reflects the production
        path (queue + producer).
        """
        from pii_detector.infrastructure.observability import (
            throughput_logger as tl,
        )

        tl._reset_singleton_for_tests()
        try:
            # Warm up.
            PIIDetectionServicer._log_throughput(
                "warmup", request_id="req_warm", chars=1, duration_s=0.001
            )
            start = time.perf_counter()
            for i in range(200):
                PIIDetectionServicer._log_throughput(
                    "detection",
                    request_id=f"req_{i}",
                    chars=1024,
                    duration_s=0.1,
                    entities_in=2,
                )
            elapsed = time.perf_counter() - start
            per_call_ms = (elapsed / 200) * 1000.0
            assert per_call_ms < 1.0, (
                f"_log_throughput too slow: {per_call_ms:.3f} ms per call"
            )
        finally:
            tl._reset_singleton_for_tests()
