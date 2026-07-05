"""
Asynchronous throughput logger for the PII detection pipeline.

The goal is to surface chars/sec per processing phase
(``detection``, ``format_prefilter``, ``total``) **without ever blocking** the
scan. Logging therefore goes through a bounded :class:`queue.Queue` whose
items are flushed by a daemon consumer thread. Producers (the gRPC
servicer's hot path) call :meth:`log_phase`, which puts the record via
:func:`Queue.put_nowait` and silently drops the record if the queue is
full -- preserving the scan throughput is more important than capturing
every metric (spec section 3.1).

Log format (spec section 3.1 grammar)::

    [THROUGHPUT] phase=detection request_id=req_42 chars=15234 \\
        duration_s=3.421 chars_per_s=4453.4 entities_in=42

Extra ``**kwargs`` are appended as ``key=value`` pairs in insertion order.
"""

from __future__ import annotations

import atexit
import logging
import threading
import time
from queue import Empty, Full, Queue
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

# Logger dedicated to the structured throughput traces. Kept separate from
# the module logger so callers can route ``[THROUGHPUT]`` lines to a
# different handler without affecting the rest of the service logs.
_throughput_logger = logging.getLogger("pii_detector.throughput")


# ---------------------------------------------------------------------------
# Module-level defaults
# ---------------------------------------------------------------------------

_DEFAULT_QUEUE_SIZE = 10_000
_LOG_PREFIX = "[THROUGHPUT]"


# ---------------------------------------------------------------------------
# ThroughputLogger
# ---------------------------------------------------------------------------


class ThroughputLogger:
    """Bounded queue + daemon consumer thread surfacing throughput logs.

    The class is intentionally lightweight: it exposes a single producer
    method (:meth:`log_phase`) and a thread that drains the queue.

    Args:
        queue_size: Maximum number of records buffered in memory. Defaults
            to :data:`_DEFAULT_QUEUE_SIZE` (10 000). The consumer is a
            daemon thread so it is torn down with the process.
        flush_on_exit: When True (default) the logger is flushed at
            interpreter shutdown via ``atexit``.
    """

    def __init__(
        self,
        queue_size: int = _DEFAULT_QUEUE_SIZE,
        flush_on_exit: bool = True,
    ) -> None:
        self._queue: Queue = Queue(maxsize=queue_size)
        self._dropped = 0  # Best-effort counter, no lock (single producer per record)
        self._stop_event = threading.Event()
        self._consumer = threading.Thread(
            target=self._consume,
            name="throughput-logger",
            daemon=True,
        )
        self._consumer.start()
        if flush_on_exit:
            atexit.register(self.shutdown)

    # -- Producer (hot path) -------------------------------------------------

    def log_phase(
        self,
        phase: str,
        request_id: str,
        chars: int,
        duration_s: float,
        **kwargs: Any,
    ) -> None:
        """Enqueue a throughput record. Never blocks.

        Args:
            phase: One of ``detection``, ``format_prefilter`` or ``total``.
            request_id: gRPC request identifier used to correlate phases.
            chars: Number of characters processed in this phase.
            duration_s: Wall-clock duration of the phase in seconds.
            **kwargs: Extra structured fields (rendered as ``key=value``).
        """
        record: Dict[str, Any] = {
            "phase": phase,
            "request_id": request_id,
            "chars": chars,
            "duration_s": duration_s,
            "chars_per_s": self._chars_per_second(chars, duration_s),
            "extras": kwargs,
        }
        try:
            self._queue.put_nowait(record)
        except Full:
            # Drop silently -- the scan throughput is sacred.
            self._dropped += 1

    @property
    def dropped_count(self) -> int:
        """Number of records dropped because the queue was full."""
        return self._dropped

    @property
    def pending_count(self) -> int:
        """Approximate number of records still waiting in the queue."""
        return self._queue.qsize()

    # -- Helpers -------------------------------------------------------------

    @staticmethod
    def _chars_per_second(chars: int, duration_s: float) -> float:
        """Return chars/sec, safe-guarded against zero / negative durations."""
        if duration_s <= 0:
            return 0.0
        return float(chars) / float(duration_s)

    @staticmethod
    def _format_record(record: Dict[str, Any]) -> str:
        head = (
            f"{_LOG_PREFIX} phase={record['phase']} "
            f"request_id={record['request_id']} "
            f"chars={record['chars']} "
            f"duration_s={record['duration_s']:.3f} "
            f"chars_per_s={record['chars_per_s']:.1f}"
        )
        extras = record.get("extras") or {}
        if not extras:
            return head
        tail = " ".join(f"{k}={v}" for k, v in extras.items())
        return f"{head} {tail}"

    # -- Consumer (background thread) ----------------------------------------

    def _consume(self) -> None:
        while not self._stop_event.is_set() or not self._queue.empty():
            try:
                record = self._queue.get(timeout=0.1)
            except Empty:
                continue
            try:
                _throughput_logger.info(self._format_record(record))
            except Exception:  # pragma: no cover - defensive
                logger.debug("throughput log emission failed", exc_info=True)
            finally:
                self._queue.task_done()

    # -- Lifecycle -----------------------------------------------------------

    def shutdown(self, timeout: float = 2.0) -> None:
        """Drain the queue then stop the consumer. Idempotent."""
        if self._stop_event.is_set():
            return
        self._stop_event.set()
        # Give the consumer a chance to drain.
        try:
            self._consumer.join(timeout=timeout)
        except RuntimeError:  # pragma: no cover - thread never started
            pass


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------


_INSTANCE: Optional[ThroughputLogger] = None
_INSTANCE_LOCK = threading.Lock()


def get_logger() -> ThroughputLogger:
    """Return the process-wide :class:`ThroughputLogger`.

    The logger is built lazily so importing this module does not spawn a
    thread when the caller has nothing to log yet.
    """
    global _INSTANCE
    if _INSTANCE is None:
        with _INSTANCE_LOCK:
            if _INSTANCE is None:
                _INSTANCE = ThroughputLogger()
    return _INSTANCE


def _reset_singleton_for_tests() -> None:
    """Test-only helper that tears down the singleton."""
    global _INSTANCE
    with _INSTANCE_LOCK:
        if _INSTANCE is not None:
            _INSTANCE.shutdown()
        _INSTANCE = None


__all__ = [
    "ThroughputLogger",
    "get_logger",
]
