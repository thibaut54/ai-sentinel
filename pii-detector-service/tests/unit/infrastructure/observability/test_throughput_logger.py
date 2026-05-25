"""
Unit tests for :mod:`pii_detector.infrastructure.observability.throughput_logger`.

The contract under test (spec section 3.1):

- ``log_phase`` MUST never block, even when the queue is full.
- The structured log MUST contain the ``[THROUGHPUT]`` tag with the
  expected fields (``phase``, ``request_id``, ``chars``, ``duration_s``,
  ``chars_per_s``) and any extra kwargs as ``key=value`` pairs.
- ``chars_per_s`` MUST equal ``chars / duration_s``, or 0 when the
  duration is zero / negative (safe-guard for synthetic phases).
- The singleton accessor must hand back the same instance.
- An ``atexit`` flush MUST drain the queue.
- Per-call latency MUST stay sub-millisecond on the producer side.
"""

from __future__ import annotations

import logging
import threading
import time
from queue import Queue
from unittest.mock import patch

import pytest

from pii_detector.infrastructure.observability import throughput_logger as tl
from pii_detector.infrastructure.observability.throughput_logger import (
    ThroughputLogger,
    get_logger,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_singleton():
    tl._reset_singleton_for_tests()
    yield
    tl._reset_singleton_for_tests()


@pytest.fixture
def logger_factory():
    """Build a logger, ensure it is shut down at teardown."""
    created = []

    def _factory(**kwargs):
        instance = ThroughputLogger(**kwargs)
        created.append(instance)
        return instance

    yield _factory
    for instance in created:
        instance.shutdown(timeout=1.0)


def _wait_until(predicate, timeout: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return False


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


class TestSingleton:
    def test_should_return_same_instance(self) -> None:
        first = get_logger()
        second = get_logger()
        assert first is second


# ---------------------------------------------------------------------------
# log_phase
# ---------------------------------------------------------------------------


class TestLogPhase:
    def test_should_emit_log_matching_spec_format(
        self,
        logger_factory,
        caplog: pytest.LogCaptureFixture,
    ) -> None:
        logger = logger_factory()
        with caplog.at_level(logging.INFO, logger="pii_detector.throughput"):
            logger.log_phase(
                "detection",
                request_id="req_42",
                chars=15234,
                duration_s=3.421,
                entities_in=42,
            )
            assert _wait_until(
                lambda: any("[THROUGHPUT]" in r.message for r in caplog.records)
            ), "consumer did not emit log within deadline"
        records = [r for r in caplog.records if "[THROUGHPUT]" in r.message]
        assert records, "no [THROUGHPUT] record produced"
        message = records[0].message
        assert "phase=detection" in message
        assert "request_id=req_42" in message
        assert "chars=15234" in message
        assert "duration_s=3.421" in message
        # Exact arithmetic: 15234 / 3.421 -> 4453.084..., rendered to one decimal.
        assert "chars_per_s=4453.1" in message
        assert "entities_in=42" in message

    def test_should_compute_chars_per_s_correctly(
        self, logger_factory, caplog: pytest.LogCaptureFixture
    ) -> None:
        logger = logger_factory()
        with caplog.at_level(logging.INFO, logger="pii_detector.throughput"):
            logger.log_phase(
                "total",
                request_id="req_x",
                chars=1000,
                duration_s=2.0,
            )
            assert _wait_until(
                lambda: any("[THROUGHPUT]" in r.message for r in caplog.records)
            )
        record = next(r for r in caplog.records if "[THROUGHPUT]" in r.message)
        assert "chars_per_s=500.0" in record.message

    def test_should_handle_zero_duration_gracefully(
        self, logger_factory, caplog: pytest.LogCaptureFixture
    ) -> None:
        logger = logger_factory()
        with caplog.at_level(logging.INFO, logger="pii_detector.throughput"):
            logger.log_phase(
                "llm_judge", request_id="req_z", chars=42, duration_s=0.0
            )
            assert _wait_until(
                lambda: any("[THROUGHPUT]" in r.message for r in caplog.records)
            )
        record = next(r for r in caplog.records if "[THROUGHPUT]" in r.message)
        assert "chars_per_s=0.0" in record.message

    @pytest.mark.parametrize("negative_duration", [-0.5, -1.0])
    def test_should_handle_negative_duration_as_zero(
        self,
        logger_factory,
        caplog: pytest.LogCaptureFixture,
        negative_duration: float,
    ) -> None:
        logger = logger_factory()
        with caplog.at_level(logging.INFO, logger="pii_detector.throughput"):
            logger.log_phase(
                "total",
                request_id="req_neg",
                chars=10,
                duration_s=negative_duration,
            )
            assert _wait_until(
                lambda: any("[THROUGHPUT]" in r.message for r in caplog.records)
            )
        record = next(r for r in caplog.records if "[THROUGHPUT]" in r.message)
        assert "chars_per_s=0.0" in record.message


# ---------------------------------------------------------------------------
# Non-blocking guarantees
# ---------------------------------------------------------------------------


class TestNonBlocking:
    def test_should_not_block_when_queue_full(self, logger_factory) -> None:
        """When the bounded queue is saturated, ``log_phase`` must remain
        non-blocking and silently drop records (preserve scan throughput).
        """
        # Build a logger whose consumer is paused so the queue saturates.
        logger = logger_factory(queue_size=2, flush_on_exit=False)
        # Replace queue with a slow one by stalling the consumer via a
        # patched format that sleeps. Simpler approach: pre-fill the
        # internal queue while keeping the consumer blocked.
        # Use a dedicated lock to gate the consumer.
        gate = threading.Event()

        original_format = ThroughputLogger._format_record

        def slow_format(record):
            gate.wait(timeout=2.0)
            return original_format(record)

        with patch.object(
            ThroughputLogger, "_format_record", staticmethod(slow_format)
        ):
            for i in range(20):
                logger.log_phase(
                    "total",
                    request_id=f"req_{i}",
                    chars=10,
                    duration_s=0.1,
                )
            # The bounded queue is 2, so most records should be dropped.
            assert logger.dropped_count > 0
            # Release the consumer so the test can tear down cleanly.
            gate.set()

    def test_should_keep_call_latency_under_one_millisecond(
        self, logger_factory
    ) -> None:
        logger = logger_factory()
        # Warm up the queue lock.
        logger.log_phase("warmup", "req_warm", 1, 0.001)
        start = time.perf_counter()
        for i in range(100):
            logger.log_phase(
                "detection",
                request_id=f"req_{i}",
                chars=1024,
                duration_s=0.5,
                entities_in=5,
            )
        elapsed = time.perf_counter() - start
        per_call_ms = (elapsed / 100) * 1000.0
        assert per_call_ms < 1.0, (
            f"log_phase too slow: {per_call_ms:.3f} ms per call"
        )


# ---------------------------------------------------------------------------
# Lifecycle / shutdown
# ---------------------------------------------------------------------------


class TestShutdownFlush:
    def test_shutdown_should_drain_queue(
        self, logger_factory, caplog: pytest.LogCaptureFixture
    ) -> None:
        logger = logger_factory(queue_size=64)
        with caplog.at_level(logging.INFO, logger="pii_detector.throughput"):
            for i in range(10):
                logger.log_phase(
                    "total",
                    request_id=f"req_{i}",
                    chars=100 * i,
                    duration_s=0.5,
                )
            logger.shutdown(timeout=2.0)
        emitted = [r for r in caplog.records if "[THROUGHPUT]" in r.message]
        assert len(emitted) == 10

    def test_shutdown_should_be_idempotent(self, logger_factory) -> None:
        logger = logger_factory()
        logger.shutdown(timeout=1.0)
        # Second call MUST not raise.
        logger.shutdown(timeout=1.0)

    def test_should_register_atexit_hook_when_flush_on_exit(self) -> None:
        with patch("atexit.register") as register:
            logger = ThroughputLogger(flush_on_exit=True)
            try:
                register.assert_called_once_with(logger.shutdown)
            finally:
                logger.shutdown(timeout=1.0)

    def test_should_skip_atexit_hook_when_flag_off(self) -> None:
        with patch("atexit.register") as register:
            logger = ThroughputLogger(flush_on_exit=False)
            try:
                register.assert_not_called()
            finally:
                logger.shutdown(timeout=1.0)
