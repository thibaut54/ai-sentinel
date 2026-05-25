"""
Aggregate ``[THROUGHPUT]`` log lines into a Markdown report.

This standalone script consumes the structured throughput traces emitted by:

- the Python pipeline (see ``pii_detector.infrastructure.observability``)
  -- format from spec section 3.1::

    [THROUGHPUT] phase=detection request_id=<id> chars=<n> \
        duration_s=<float> chars_per_s=<float> entities_in=<n>

- the Java gRPC client (see ``GrpcPiiDetectorArmeriaClientAdapter``) --
  format from spec section 3.2 (note ``duration_ms`` instead of
  ``duration_s``)::

    [THROUGHPUT] phase=grpc.client request_id=<UUID> chars=<n> \
        duration_ms=<n> chars_per_s=<float>

The parser is intentionally permissive: it tolerates any logger/timestamp
prefix before the ``[THROUGHPUT]`` tag, silently skips malformed lines and
normalises ``duration_ms`` into ``duration_s`` so the rest of the pipeline
only deals with seconds.

Usage (CLI)::

    python -m scripts.parse_throughput_logs \\
        --input run-improved.log \\
        --baseline run-baseline.log \\
        --output target/corpus-data-sql-comparison/THROUGHPUT_REPORT.md

The script has **no third-party dependencies** -- it must stay runnable in
post-CI environments where the ``pii_detector`` package is not installed.

Spec reference: ``_bmad-output/planning-artifacts/llm-judge-qwen-spec.md``
sections 3.1, 3.2 and 3.3.
"""

from __future__ import annotations

import argparse
import logging
import re
import statistics
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Module-level constants
# ---------------------------------------------------------------------------

# Matches the ``[THROUGHPUT]`` tag wherever it appears in the line. Anything
# before the tag (logback/python timestamps, thread name, log level) is
# discarded. The capture group exposes the trailing ``key=value`` payload.
_THROUGHPUT_REGEX = re.compile(r"\[THROUGHPUT\]\s+(.*?)\s*$")

# Matches a single ``key=value`` pair tolerating quoted values. We restrict
# the key to a safe identifier (alphanumeric + dot + underscore) so dotted
# phases like ``grpc.client`` are captured intact.
_KV_REGEX = re.compile(r"([A-Za-z_][A-Za-z0-9_\.]*)=(\"[^\"]*\"|\S+)")

# Numeric fields are coerced from text. ``chars`` is an int, ``duration_s``
# is a float etc. We keep this explicit (rather than guessing) because the
# log emitter is well-defined.
_INT_FIELDS = frozenset(
    {
        "chars",
        "entities_in",
        "entities_kept",
        "entities_rejected",
        "entities_final",
        "batches",
        "llm_total_calls",
    }
)
_FLOAT_FIELDS = frozenset(
    {
        "duration_s",
        "duration_ms",
        "chars_per_s",
        "llm_total_call_duration_s",
    }
)

# Phases we recognise as canonical. Unknown phases are tolerated but logged
# at DEBUG level so corrupted data does not silently inflate aggregates.
_KNOWN_PHASES = frozenset({"detection", "llm_judge", "total", "grpc.client"})


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass
class ThroughputEntry:
    """One parsed ``[THROUGHPUT]`` line.

    ``duration_ms`` (Java emitter) is converted to ``duration_s`` on parse so
    downstream aggregation only ever sees seconds.
    """

    phase: str
    request_id: str
    chars: int
    duration_s: float
    chars_per_s: float
    extra: Dict[str, Any] = field(default_factory=dict)


@dataclass
class PhaseStats:
    """Aggregated stats for a single phase."""

    phase: str
    count: int
    p50: float
    p95: float
    p99: float
    mean_chars_per_s: float


@dataclass
class LlmJudgeStats:
    """LLM-judge specific aggregates (rejection rate, batches...)."""

    entries: int
    entities_in: int
    entities_kept: int
    entities_rejected: int
    rejection_rate: float
    average_batches: float
    average_llm_call_duration_s: float


@dataclass
class RequestBreakdown:
    """Per-``request_id`` view used for the slowest-request table."""

    request_id: str
    phase: str
    chars: int
    duration_s: float
    chars_per_s: float


@dataclass
class ThroughputReport:
    """Aggregated report built from a list of :class:`ThroughputEntry`."""

    entry_count: int
    phase_stats: Dict[str, PhaseStats] = field(default_factory=dict)
    llm_judge: Optional[LlmJudgeStats] = None
    slowest_requests: List[RequestBreakdown] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------


def parse_log_lines(lines: Iterable[str]) -> List[ThroughputEntry]:
    """Parse an iterable of log lines into :class:`ThroughputEntry` objects.

    Lines without the ``[THROUGHPUT]`` tag are skipped silently. Lines with
    the tag but missing one of the mandatory fields (``phase``,
    ``request_id``, ``chars``, ``duration_s`` / ``duration_ms``) are skipped
    and counted as malformed.

    Args:
        lines: Any iterable of log strings (file contents, ``sys.stdin``...).

    Returns:
        The list of successfully parsed entries.
    """
    entries: List[ThroughputEntry] = []
    skipped = 0
    for raw_line in lines:
        if not raw_line:
            continue
        match = _THROUGHPUT_REGEX.search(raw_line)
        if not match:
            continue
        payload = match.group(1)
        kv_pairs = _parse_kv_pairs(payload)
        if not kv_pairs:
            skipped += 1
            continue
        entry = _build_entry(kv_pairs)
        if entry is None:
            skipped += 1
            continue
        entries.append(entry)
    if skipped:
        logger.info("Skipped %d malformed [THROUGHPUT] line(s)", skipped)
    return entries


def _parse_kv_pairs(payload: str) -> Dict[str, Any]:
    """Extract ``key=value`` pairs from the throughput payload."""
    result: Dict[str, Any] = {}
    for key, raw_value in _KV_REGEX.findall(payload):
        value = raw_value.strip('"')
        result[key] = _coerce_value(key, value)
    return result


def _coerce_value(key: str, value: str) -> Any:
    """Coerce a string value into int / float / str based on the key."""
    if key in _INT_FIELDS:
        try:
            return int(value)
        except ValueError:
            # Some emitters render integers as ``42.0``; tolerate it.
            try:
                return int(float(value))
            except ValueError:
                return value
    if key in _FLOAT_FIELDS:
        try:
            return float(value)
        except ValueError:
            return value
    return value


def _build_entry(kv: Dict[str, Any]) -> Optional[ThroughputEntry]:
    """Build a :class:`ThroughputEntry` from key/value pairs, or ``None``.

    Handles the ``duration_ms`` (Java) -> ``duration_s`` conversion.
    """
    phase = kv.get("phase")
    request_id = kv.get("request_id")
    chars = kv.get("chars")
    if not isinstance(phase, str) or not isinstance(request_id, str):
        return None
    if not isinstance(chars, int):
        return None

    duration_s = _extract_duration_s(kv)
    if duration_s is None:
        return None

    chars_per_s_raw = kv.get("chars_per_s")
    if isinstance(chars_per_s_raw, (int, float)):
        chars_per_s = float(chars_per_s_raw)
    else:
        chars_per_s = (chars / duration_s) if duration_s > 0 else 0.0

    extra = {
        key: value
        for key, value in kv.items()
        if key
        not in {
            "phase",
            "request_id",
            "chars",
            "duration_s",
            "duration_ms",
            "chars_per_s",
        }
    }

    if phase not in _KNOWN_PHASES:
        logger.debug("Unknown phase %r encountered (extras=%s)", phase, extra)

    return ThroughputEntry(
        phase=phase,
        request_id=request_id,
        chars=chars,
        duration_s=duration_s,
        chars_per_s=chars_per_s,
        extra=extra,
    )


def _extract_duration_s(kv: Dict[str, Any]) -> Optional[float]:
    """Return the duration in seconds, converting ``duration_ms`` if needed."""
    if "duration_s" in kv and isinstance(kv["duration_s"], (int, float)):
        return float(kv["duration_s"])
    if "duration_ms" in kv and isinstance(kv["duration_ms"], (int, float)):
        return float(kv["duration_ms"]) / 1000.0
    return None


# ---------------------------------------------------------------------------
# Aggregation
# ---------------------------------------------------------------------------


def aggregate(entries: Sequence[ThroughputEntry]) -> ThroughputReport:
    """Compute aggregated statistics from parsed entries.

    Args:
        entries: List of entries returned by :func:`parse_log_lines`.

    Returns:
        A :class:`ThroughputReport` ready for :func:`write_report`.
    """
    if not entries:
        return ThroughputReport(entry_count=0)

    phase_stats: Dict[str, PhaseStats] = {}
    grouped: Dict[str, List[ThroughputEntry]] = {}
    for entry in entries:
        grouped.setdefault(entry.phase, []).append(entry)
    for phase, items in grouped.items():
        values = [e.chars_per_s for e in items]
        phase_stats[phase] = PhaseStats(
            phase=phase,
            count=len(items),
            p50=_percentile(values, 50),
            p95=_percentile(values, 95),
            p99=_percentile(values, 99),
            mean_chars_per_s=statistics.fmean(values) if values else 0.0,
        )

    llm_judge = _compute_llm_judge_stats(grouped.get("llm_judge", []))
    slowest = _slowest_requests(entries, limit=10)

    return ThroughputReport(
        entry_count=len(entries),
        phase_stats=phase_stats,
        llm_judge=llm_judge,
        slowest_requests=slowest,
    )


def _percentile(values: Sequence[float], pct: int) -> float:
    """Pure-stdlib percentile (inclusive method).

    statistics.quantiles requires n >= 2 elements; we fall back to the single
    value otherwise. ``pct`` must be in ``[1, 99]``.
    """
    if not values:
        return 0.0
    if len(values) == 1:
        return float(values[0])
    quantiles = statistics.quantiles(values, n=100, method="inclusive")
    # ``quantiles(n=100)`` returns 99 cut-points: q[0] is the 1st percentile,
    # q[49] the 50th, q[98] the 99th.
    idx = max(0, min(98, pct - 1))
    return float(quantiles[idx])


def _compute_llm_judge_stats(
    judge_entries: Sequence[ThroughputEntry],
) -> Optional[LlmJudgeStats]:
    """Collect LLM-judge specific aggregates from ``llm_judge`` entries."""
    if not judge_entries:
        return None
    entities_in = sum(int(e.extra.get("entities_in", 0)) for e in judge_entries)
    entities_kept = sum(int(e.extra.get("entities_kept", 0)) for e in judge_entries)
    entities_rejected = sum(
        int(e.extra.get("entities_rejected", 0)) for e in judge_entries
    )
    rejection_rate = (
        entities_rejected / entities_in if entities_in > 0 else 0.0
    )
    batches_values = [
        int(e.extra.get("batches", 0))
        for e in judge_entries
        if "batches" in e.extra
    ]
    call_durations = [
        float(e.extra.get("llm_total_call_duration_s", 0.0))
        for e in judge_entries
        if "llm_total_call_duration_s" in e.extra
    ]
    return LlmJudgeStats(
        entries=len(judge_entries),
        entities_in=entities_in,
        entities_kept=entities_kept,
        entities_rejected=entities_rejected,
        rejection_rate=rejection_rate,
        average_batches=(
            statistics.fmean(batches_values) if batches_values else 0.0
        ),
        average_llm_call_duration_s=(
            statistics.fmean(call_durations) if call_durations else 0.0
        ),
    )


def _slowest_requests(
    entries: Sequence[ThroughputEntry], limit: int = 10
) -> List[RequestBreakdown]:
    """Return the ``limit`` slowest entries sorted by ``chars_per_s`` asc."""
    sortable = sorted(entries, key=lambda e: e.chars_per_s)
    return [
        RequestBreakdown(
            request_id=entry.request_id,
            phase=entry.phase,
            chars=entry.chars,
            duration_s=entry.duration_s,
            chars_per_s=entry.chars_per_s,
        )
        for entry in sortable[:limit]
    ]


# ---------------------------------------------------------------------------
# Markdown writer
# ---------------------------------------------------------------------------


def write_report(
    report: ThroughputReport,
    out_path: Path,
    baseline_report: Optional[ThroughputReport] = None,
) -> None:
    """Render the aggregated report as Markdown.

    Args:
        report: Aggregated report for the improved/current run.
        out_path: Destination file. Parent directory must exist.
        baseline_report: Optional baseline (without LLM judge) used to render
            the ``Delta vs baseline`` section.

    Raises:
        FileNotFoundError: If ``out_path.parent`` does not exist.
    """
    if not out_path.parent.exists():
        raise FileNotFoundError(
            f"Output directory does not exist: {out_path.parent}"
        )

    lines: List[str] = []
    lines.append("# Throughput report")
    lines.append("")
    lines.append(
        f"Total entries parsed: **{report.entry_count}**."
    )
    lines.append("")
    lines.extend(_render_phase_table(report))
    lines.append("")
    lines.extend(_render_llm_judge_section(report))
    lines.append("")
    lines.extend(_render_delta_section(report, baseline_report))
    lines.append("")
    lines.extend(_render_breakdown_section(report))
    lines.append("")
    lines.extend(_render_footer())

    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _render_phase_table(report: ThroughputReport) -> List[str]:
    lines = ["## Throughput per phase", ""]
    if not report.phase_stats:
        lines.append("_No data._")
        return lines
    lines.append("| phase | count | p50 chars/s | p95 chars/s | p99 chars/s | mean chars/s |")
    lines.append("| --- | ---: | ---: | ---: | ---: | ---: |")
    for phase in sorted(report.phase_stats):
        stats = report.phase_stats[phase]
        lines.append(
            f"| {phase} | {stats.count} | {stats.p50:.1f} | "
            f"{stats.p95:.1f} | {stats.p99:.1f} | "
            f"{stats.mean_chars_per_s:.1f} |"
        )
    return lines


def _render_llm_judge_section(report: ThroughputReport) -> List[str]:
    lines = ["## LLM judge stats", ""]
    stats = report.llm_judge
    if stats is None:
        lines.append("_No `llm_judge` entries found._")
        return lines
    lines.append("| metric | value |")
    lines.append("| --- | ---: |")
    lines.append(f"| entries | {stats.entries} |")
    lines.append(f"| entities_in | {stats.entities_in} |")
    lines.append(f"| entities_kept | {stats.entities_kept} |")
    lines.append(f"| entities_rejected | {stats.entities_rejected} |")
    lines.append(
        f"| rejection_rate | {stats.rejection_rate * 100:.2f}% |"
    )
    lines.append(f"| average batches | {stats.average_batches:.2f} |")
    lines.append(
        f"| average llm_total_call_duration_s | "
        f"{stats.average_llm_call_duration_s:.3f} |"
    )
    return lines


def _render_delta_section(
    report: ThroughputReport, baseline: Optional[ThroughputReport]
) -> List[str]:
    if baseline is None:
        return []
    lines = ["## Delta vs baseline", ""]
    lines.append(
        "| phase | baseline mean | improved mean | delta chars/s | delta % |"
    )
    lines.append("| --- | ---: | ---: | ---: | ---: |")
    phases = sorted(
        set(report.phase_stats) | set(baseline.phase_stats)
    )
    for phase in phases:
        improved_stats = report.phase_stats.get(phase)
        baseline_stats = baseline.phase_stats.get(phase)
        if improved_stats is None or baseline_stats is None:
            lines.append(
                f"| {phase} | "
                f"{_format_optional(baseline_stats)} | "
                f"{_format_optional(improved_stats)} | n/a | n/a |"
            )
            continue
        delta = improved_stats.mean_chars_per_s - baseline_stats.mean_chars_per_s
        delta_pct = (
            (delta / baseline_stats.mean_chars_per_s) * 100.0
            if baseline_stats.mean_chars_per_s > 0
            else 0.0
        )
        lines.append(
            f"| {phase} | {baseline_stats.mean_chars_per_s:.1f} | "
            f"{improved_stats.mean_chars_per_s:.1f} | "
            f"{delta:+.1f} | {delta_pct:+.2f}% |"
        )
    return lines


def _format_optional(stats: Optional[PhaseStats]) -> str:
    if stats is None:
        return "_absent_"
    return f"{stats.mean_chars_per_s:.1f}"


def _render_breakdown_section(report: ThroughputReport) -> List[str]:
    lines = ["## Breakdown - top 10 slowest entries", ""]
    if not report.slowest_requests:
        lines.append("_No data._")
        return lines
    lines.append("| request_id | phase | chars | duration_s | chars_per_s |")
    lines.append("| --- | --- | ---: | ---: | ---: |")
    for item in report.slowest_requests:
        lines.append(
            f"| {item.request_id} | {item.phase} | {item.chars} | "
            f"{item.duration_s:.3f} | {item.chars_per_s:.1f} |"
        )
    return lines


def _render_footer() -> List[str]:
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    return [
        "---",
        "",
        f"Generated at {now} (UTC).",
        "",
        "Spec reference: `_bmad-output/planning-artifacts/"
        "llm-judge-qwen-spec.md` section 3.3.",
    ]


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _iter_lines(paths: Sequence[Path]) -> Iterator[str]:
    """Yield lines from a list of files, opening each one in turn."""
    for path in paths:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                yield line


def _build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="parse_throughput_logs",
        description=(
            "Aggregate [THROUGHPUT] log lines into a Markdown report "
            "(spec section 3.3)."
        ),
    )
    parser.add_argument(
        "--input",
        "-i",
        type=Path,
        nargs="+",
        default=None,
        help=(
            "One or more log files to parse. Reads from stdin when omitted."
        ),
    )
    parser.add_argument(
        "--baseline",
        "-b",
        type=Path,
        nargs="*",
        default=None,
        help=(
            "Optional baseline log file(s) used to compute the delta section."
        ),
    )
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        required=True,
        help="Destination Markdown file (parent directory must exist).",
    )
    parser.add_argument(
        "--log-level",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        default="INFO",
        help="Verbosity for the script's own logger.",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    """CLI entry point. Returns the process exit code."""
    parser = _build_argument_parser()
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    try:
        input_entries = _read_entries(args.input)
        baseline_entries = (
            _read_entries(args.baseline) if args.baseline else None
        )
    except FileNotFoundError as exc:
        logger.error("Input file not found: %s", exc)
        return 1

    if not args.output.parent.exists():
        logger.error(
            "Output directory does not exist: %s", args.output.parent
        )
        return 1

    report = aggregate(input_entries)
    baseline_report = (
        aggregate(baseline_entries) if baseline_entries is not None else None
    )
    write_report(report, args.output, baseline_report=baseline_report)
    logger.info(
        "Wrote throughput report to %s (entries=%d, baseline=%s)",
        args.output,
        report.entry_count,
        baseline_report.entry_count if baseline_report else "n/a",
    )
    return 0


def _read_entries(
    paths: Optional[Sequence[Path]],
) -> List[ThroughputEntry]:
    """Read entries from a list of files or stdin when ``paths`` is None."""
    if paths is None:
        return parse_log_lines(sys.stdin)
    return parse_log_lines(_iter_lines(paths))


if __name__ == "__main__":  # pragma: no cover - CLI guard
    raise SystemExit(main())


__all__ = [
    "ThroughputEntry",
    "PhaseStats",
    "LlmJudgeStats",
    "RequestBreakdown",
    "ThroughputReport",
    "parse_log_lines",
    "aggregate",
    "write_report",
    "main",
]
